package etcodehome.freeterraforged.world.worldgen.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.platform.ConfigUtil;

/**
 * Creates, migrates, and stably captures the native biome replacement file.
 */
public final class BiomeReplacementConfigStore {
	public static final String NATIVE_FILE_NAME = "biome_replacements.properties";
	public static final String LEGACY_FILE_NAME = "biome_replacer.properties";
	private static final int MAX_BYTES = 4 * 1024 * 1024;
	private static final int STABLE_READ_ATTEMPTS = 3;
	private static final String TEMPLATE = """
		format_version=1

		! ===============================================================================

		! d888888b .88b  d88. d8888b.  .d88b.  d8888b. d888888b  .d8b.  d8b   db d888888b
		!   `88'   88'YbdP`88 88  `8D .8P  Y8. 88  `8D `~~88~~' d8' `8b 888o  88 `~~88~~'
		!    88    88  88  88 88oodD' 88    88 88oobY'    88    88ooo88 88V8o 88    88
		!    88    88  88  88 88~~~   88    88 88`8b      88    88~~~88 88 V8o88    88
		!   .88.   88  88  88 88      `8b  d8' 88 `88.    88    88   88 88  V888    88
		! Y888888P YP  YP  YP 88       `Y88P'  88   YD    YP    YP   YP VP   V8P    YP

		! This file is only for replacing biomes within the Overworld of an FTF world!
		!
		! If you want to replace biomes in other dimensions (e.g. minecraft:warped_forest > null),
		! then use the Biome Replacer mod (https://modrinth.com/mod/biome-replacer).
		! Put the rules for the other dimensions in config/biome_replacer.properties,
		! and the rules for the Overworld here, in this file.

		! ===============================================================================

		! To replace one biome with another:
		! minecraft:dark_forest > minecraft:cherry_grove
		!
		! Be sure to remove the ! at the front, otherwise it won't work.

		! To replace every biome in a tag:
		! #minecraft:is_forest > minecraft:desert


		! To remove a biome:
		! minecraft:deep_dark > null
		""";
	private static final byte[] MIGRATION_SUFFIX = """

		! Imported by FreeTerraForged from config/biome_replacer.properties.
		format_version=1
		migration_source=biome_replacer
		""".getBytes(StandardCharsets.UTF_8);

	private static BiomeReplacementConfigSnapshot latest;
	private static long revision;

	private BiomeReplacementConfigStore() {
	}

	public static void initialize() {
		BiomeReplacementConfigSnapshot snapshot = capture();
		FTFCommon.LOGGER.info(
			"Biome replacement config ready: path={} origin={} revision={} fingerprint={} legacy_present={}",
			snapshot.path(), snapshot.origin(), snapshot.revision(), snapshot.fingerprint(),
			snapshot.legacyFilePresent()
		);
	}

	public static BiomeReplacementConfigSnapshot capture() {
		return capture(ConfigUtil.getConfigPath());
	}

	static synchronized BiomeReplacementConfigSnapshot capture(Path configRoot) {
		Objects.requireNonNull(configRoot, "configRoot");
		Path normalizedRoot = configRoot.toAbsolutePath().normalize();
		Path nativeDirectory = normalizedRoot.resolve(FTFCommon.MOD_ID);
		Path nativeFile = nativeDirectory.resolve(NATIVE_FILE_NAME);
		Path legacyFile = normalizedRoot.resolve(LEGACY_FILE_NAME);
		Creation creation = ensureDestination(nativeDirectory, nativeFile, legacyFile);
		byte[] bytes = stableRead(nativeFile);
		String content = decodeUtf8(bytes, nativeFile);
		String fingerprint = sha256(bytes);
		boolean legacyPresent = Files.isRegularFile(legacyFile);
		BiomeReplacementConfig parsed = BiomeReplacementConfig.parse(content);
		String path = nativeFile.toString();
		if (latest != null && latest.fingerprint().equals(fingerprint)
			&& latest.path().equals(path)
			&& latest.legacyFilePresent() == legacyPresent) {
			return latest;
		}
		long nextRevision = Math.incrementExact(revision);
		revision = nextRevision;
		latest = new BiomeReplacementConfigSnapshot(
			nextRevision,
			fingerprint,
			path,
			creation.origin(),
			content,
			parsed,
			legacyPresent
		);
		return latest;
	}

	private static Creation ensureDestination(Path directory, Path nativeFile, Path legacyFile) {
		try {
			Files.createDirectories(directory);
			if (Files.isRegularFile(nativeFile)) {
				return new Creation(BiomeReplacementConfigSnapshot.Origin.NATIVE);
			}
			if (Files.exists(nativeFile, LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalStateException(
					"Biome replacement path exists but is not a regular file: " + nativeFile
				);
			}
			byte[] content;
			BiomeReplacementConfigSnapshot.Origin origin;
			if (Files.isRegularFile(legacyFile)) {
				byte[] legacy = stableRead(legacyFile);
				content = new byte[Math.addExact(legacy.length, MIGRATION_SUFFIX.length)];
				System.arraycopy(legacy, 0, content, 0, legacy.length);
				System.arraycopy(MIGRATION_SUFFIX, 0, content, legacy.length, MIGRATION_SUFFIX.length);
				origin = BiomeReplacementConfigSnapshot.Origin.MIGRATED_LEGACY;
			} else {
				content = TEMPLATE.getBytes(StandardCharsets.UTF_8);
				origin = BiomeReplacementConfigSnapshot.Origin.CREATED_TEMPLATE;
			}
			boolean created = atomicCreate(nativeFile, content);
			return new Creation(created ? origin : BiomeReplacementConfigSnapshot.Origin.NATIVE);
		} catch (IOException failure) {
			throw new IllegalStateException(
				"Failed to create FreeTerraForged biome replacement config at " + nativeFile,
				failure
			);
		}
	}

	private static boolean atomicCreate(Path target, byte[] content) throws IOException {
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
		try {
			try (FileChannel channel = FileChannel.open(
				temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
			)) {
				ByteBuffer buffer = ByteBuffer.wrap(content);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
			}
			try {
				// A hard link publishes the already-complete inode atomically without any
				// replace-existing behavior. It is supported by the normal Minecraft filesystems.
				Files.createLink(target, temporary);
			} catch (FileAlreadyExistsException race) {
				return false;
			} catch (UnsupportedOperationException | IOException unsupportedLink) {
				// Same-directory moves do not expose partial contents and, without REPLACE_EXISTING,
				// retain an independently-created destination.
				try {
					Files.move(temporary, target);
				} catch (FileAlreadyExistsException race) {
					return false;
				}
			}
			return true;
		} catch (FileAlreadyExistsException race) {
			return false;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static byte[] stableRead(Path file) {
		IOException lastFailure = null;
		for (int attempt = 1; attempt <= STABLE_READ_ATTEMPTS; attempt++) {
			try {
				BasicFileAttributes before = Files.readAttributes(
					file, BasicFileAttributes.class
				);
				if (!before.isRegularFile()) {
					throw new IOException("Path is not a regular file");
				}
				if (before.size() > MAX_BYTES) {
					throw new IOException(
						"File exceeds the " + MAX_BYTES + " byte biome replacement limit"
					);
				}
				byte[] bytes = Files.readAllBytes(file);
				BasicFileAttributes after = Files.readAttributes(
					file, BasicFileAttributes.class
				);
				if (sameFileState(before, after) && after.size() == bytes.length) {
					return bytes;
				}
				lastFailure = new IOException("File changed while it was being read");
			} catch (IOException failure) {
				lastFailure = failure;
			}
		}
		throw new IllegalStateException(
			"Could not capture a stable biome replacement config from " + file,
			lastFailure
		);
	}

	private static boolean sameFileState(BasicFileAttributes first, BasicFileAttributes second) {
		return first.size() == second.size()
			&& first.lastModifiedTime().equals(second.lastModifiedTime())
			&& Objects.equals(first.fileKey(), second.fileKey());
	}

	private static String decodeUtf8(byte[] bytes, Path file) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		} catch (CharacterCodingException failure) {
			throw new IllegalStateException(
				"Biome replacement config is not valid UTF-8: " + file,
				failure
			);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("JVM does not provide SHA-256", impossible);
		}
	}

	private record Creation(BiomeReplacementConfigSnapshot.Origin origin) {
	}
}
