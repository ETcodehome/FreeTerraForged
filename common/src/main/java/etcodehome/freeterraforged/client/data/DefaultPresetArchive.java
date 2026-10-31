package etcodehome.freeterraforged.client.data;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.platform.ConfigUtil;

public final class DefaultPresetArchive {
	private static final Path SAVED_SOURCE = ConfigUtil.ftf("default-preset-source.txt");
	private static final String BUILTIN_SOURCE_PREFIX = "builtin:";

	private DefaultPresetArchive() {
	}

	public static boolean hasSource() {
		if (!Files.isRegularFile(SAVED_SOURCE)) {
			return false;
		}
		try {
			return !Files.readString(SAVED_SOURCE, StandardCharsets.UTF_8).trim().isEmpty();
		} catch (IOException exception) {
			return false;
		}
	}

	public static boolean isSource(Path presetPath) throws IOException {
		if (presetPath == null || !Files.isRegularFile(SAVED_SOURCE)) {
			return false;
		}

		String savedPath = Files.readString(SAVED_SOURCE, StandardCharsets.UTF_8).trim();
		if (savedPath.isEmpty() || savedPath.startsWith(BUILTIN_SOURCE_PREFIX)) {
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

		writeSource(configRoot.relativize(source).toString());
	}

	public static boolean isBuiltinSource(String builtinId) throws IOException {
		if (builtinId == null || !Files.isRegularFile(SAVED_SOURCE)) {
			return false;
		}
		return Files.readString(SAVED_SOURCE, StandardCharsets.UTF_8).trim()
			.equals(BUILTIN_SOURCE_PREFIX + builtinId);
	}

	public static void setBuiltinSource(String builtinId) throws IOException {
		if (builtinId == null || builtinId.isBlank()) {
			throw new IllegalArgumentException("Built-in default preset ID must not be blank");
		}
		writeSource(BUILTIN_SOURCE_PREFIX + builtinId);
	}

	private static void writeSource(String source) throws IOException {
		Path parent = SAVED_SOURCE.getParent();
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, "default-preset-source-", ".tmp");
		try {
			Files.writeString(temporary, source, StandardCharsets.UTF_8);
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

	public static Preset getActiveDefaultPreset() {
		if (!hasSource()) {
			return Presets.makeFTFDefault();
		}

		try {
			String savedPath = Files.readString(SAVED_SOURCE, StandardCharsets.UTF_8).trim();
			if (savedPath.startsWith(BUILTIN_SOURCE_PREFIX)) {
				String builtinId = savedPath.substring(BUILTIN_SOURCE_PREFIX.length());
				return getBuiltinPreset(builtinId);
			}

			Path configRoot = ConfigUtil.getConfigPath().toAbsolutePath().normalize();
			Path rawSource = Path.of(savedPath);
			Path source = rawSource.isAbsolute() ? rawSource : configRoot.resolve(rawSource);

			if (Files.isRegularFile(source)) {
				try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
					Preset preset = Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
						.resultOrPartial(err -> FTFCommon.LOGGER.warn("Failed to parse default preset at {}: {}", source, err))
						.orElseGet(Presets::makeFTFDefault);
					if (preset.presentation().presetName.isBlank()) {
						preset.presentation().setPresetName(fileName(source));
					}
					return preset;
				}
			}
		} catch (Exception exception) {
			FTFCommon.LOGGER.warn("Failed to load active default preset", exception);
		}

		return Presets.makeFTFDefault();
	}

	public static Preset getBuiltinPreset(String builtinId) {
		Preset preset;
		if (FTFTranslationKeys.GUI_RIVERS_PRESET_NAME.equals(builtinId)) {
			preset = Presets.modernDefaultWithRivers();
		} else if (FTFTranslationKeys.GUI_COMMUNITY1_PRESET_NAME.equals(builtinId)) {
			preset = Presets.makeCommunityPreset1();
		} else if (FTFTranslationKeys.GUI_DEFAULT_LEGACY_PRESET_NAME.equals(builtinId)) {
			preset = Presets.makeLegacyDefault();
		} else if (FTFTranslationKeys.GUI_BEAUTIFUL_PRESET_NAME.equals(builtinId)) {
			preset = Presets.makeLegacyBeautiful();
		} else if (FTFTranslationKeys.GUI_HUGE_BIOMES_PRESET_NAME.equals(builtinId)) {
			preset = Presets.makeLegacyHugeBiomes();
		} else if (FTFTranslationKeys.GUI_LITE_PRESET_NAME.equals(builtinId)) {
			preset = Presets.makeLegacyLite();
		} else if (FTFTranslationKeys.GUI_VANILLAISH_PRESET_NAME.equals(builtinId)) {
			preset = Presets.makeLegacyVanillaish();
		} else {
			return Presets.makeFTFDefault();
		}
		preset.presentation().setPresetName(net.minecraft.network.chat.Component.translatable(builtinId).getString());
		return preset;
	}

	public static String getActiveDefaultPresetName() {
		if (!hasSource()) {
			return "FreeTerraForged";
		}
		try {
			String savedPath = Files.readString(SAVED_SOURCE, StandardCharsets.UTF_8).trim();
			if (savedPath.startsWith(BUILTIN_SOURCE_PREFIX)) {
				String builtinId = savedPath.substring(BUILTIN_SOURCE_PREFIX.length());
				return net.minecraft.network.chat.Component.translatable(builtinId).getString();
			}
			Path source = Path.of(savedPath);
			String fileName = source.getFileName().toString();
			if (fileName.endsWith(".json")) {
				return fileName.substring(0, fileName.length() - 5);
			}
			return fileName;
		} catch (Exception exception) {
			return "FreeTerraForged";
		}
	}

	private static String fileName(Path source) {
		String fileName = source.getFileName().toString();
		return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
	}
}
