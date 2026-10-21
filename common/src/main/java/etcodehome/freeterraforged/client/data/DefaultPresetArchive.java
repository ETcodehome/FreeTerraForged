package etcodehome.freeterraforged.client.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import etcodehome.freeterraforged.platform.ConfigUtil;

public final class DefaultPresetArchive {
	private static final Path SAVED_ARCHIVE = ConfigUtil.ftf("default-preset.zip");
	private static final Path SAVED_SOURCE = ConfigUtil.ftf("default-preset-source.txt");

	private DefaultPresetArchive() {
	}

	public static Path path() {
		return SAVED_ARCHIVE;
	}

	public static boolean exists() {
		return Files.isRegularFile(SAVED_ARCHIVE);
	}

	public static boolean isSource(Path presetPath) throws IOException {
		if (!Files.isRegularFile(SAVED_SOURCE)) {
			return false;
		}

		String savedPath = Files.readString(SAVED_SOURCE, StandardCharsets.UTF_8).trim();
		if (savedPath.isEmpty()) {
			return false;
		}

		Path configRoot = ConfigUtil.getConfigPath().toAbsolutePath().normalize();
		Path source = Path.of(savedPath);
		if (!source.isAbsolute()) {
			source = configRoot.resolve(source);
		}
		return source.toAbsolutePath().normalize().equals(presetPath.toAbsolutePath().normalize());
	}

	public static void setSource(Path presetPath) throws IOException {
		if (presetPath == null) {
			Files.deleteIfExists(SAVED_SOURCE);
			return;
		}

		Path configRoot = ConfigUtil.getConfigPath().toAbsolutePath().normalize();
		Path source = presetPath.toAbsolutePath().normalize();
		if (!source.startsWith(configRoot)) {
			throw new IOException("Default preset source is outside the Minecraft config directory: " + source);
		}

		Path parent = SAVED_SOURCE.getParent();
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, "default-preset-source-", ".tmp");
		try {
			Files.writeString(temporary, configRoot.relativize(source).toString(), StandardCharsets.UTF_8);
			try {
				Files.move(temporary, SAVED_SOURCE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, SAVED_SOURCE, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public static void updateSource(Path oldPath, Path newPath) throws IOException {
		if (isSource(oldPath)) {
			setSource(newPath);
		}
	}

	public static void clearSourceIfMatches(Path presetPath) throws IOException {
		if (isSource(presetPath)) {
			setSource(null);
		}
	}

	public static void copyTo(Path dataPackDirectory) throws IOException {
		if (!exists()) {
			throw new IOException("No saved default preset datapack exists at " + SAVED_ARCHIVE);
		}

		Files.createDirectories(dataPackDirectory);
		Path target = dataPackDirectory.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
		Path temporary = Files.createTempFile(dataPackDirectory, "freeterraforged-default-", ".tmp");
		try {
			Files.copy(SAVED_ARCHIVE, temporary, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
