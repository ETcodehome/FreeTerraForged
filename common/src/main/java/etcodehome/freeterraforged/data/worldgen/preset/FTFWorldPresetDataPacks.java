package etcodehome.freeterraforged.data.worldgen.preset;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.file.PathUtils;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.Datapacks;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;

public final class FTFWorldPresetDataPacks {
	private FTFWorldPresetDataPacks() {
	}

	public static boolean selectsFreeTerraForged(String levelType) {
		return levelType != null && (
			levelType.equalsIgnoreCase(FTFWorldPresets.SERVER_LEVEL_TYPE)
				|| levelType.equalsIgnoreCase(FTFWorldPresets.LEGACY_SERVER_LEVEL_TYPE)
				|| levelType.equalsIgnoreCase(FTFWorldPresets.FREETERRAFORGED.location().toString())
		);
	}

	public static WorldDataConfiguration setCustomPresetEnabled(
		WorldDataConfiguration configuration,
		boolean enabled
	) {
		DataPackConfig current = configuration.dataPacks();
		DataPackConfig updated = setCustomPresetEnabled(current, enabled);
		return updated == current ? configuration : new WorldDataConfiguration(updated, configuration.enabledFeatures());
	}

	public static DataPackConfig setCustomPresetEnabled(DataPackConfig current, boolean enabled) {
		String packId = FTFWorldPresets.GENERATED_PRESET_PACK_ID;
		List<String> enabledPacks = new ArrayList<>(current.getEnabled());
		List<String> disabledPacks = new ArrayList<>(current.getDisabled());

		if (enabled) {
			if (!enabledPacks.contains(packId)) {
				enabledPacks.add(packId);
			}
			disabledPacks.remove(packId);
		} else {
			enabledPacks.remove(packId);
		}

		if (enabledPacks.equals(current.getEnabled()) && disabledPacks.equals(current.getDisabled())) {
			return current;
		}

		return new DataPackConfig(enabledPacks, disabledPacks);
	}

	public static WorldDataConfiguration setDefaultPresetEnabled(
		WorldDataConfiguration configuration,
		boolean enabled
	) {
		return setCustomPresetEnabled(configuration, enabled);
	}

	public static DataPackConfig setDefaultPresetEnabled(DataPackConfig current, boolean enabled) {
		return setCustomPresetEnabled(current, enabled);
	}

	public static boolean requiresFullDataPackReload(WorldDataConfiguration configuration) {
		return configuration != null
			&& configuration.dataPacks() != null
			&& configuration.dataPacks().getEnabled().contains(FTFWorldPresets.GENERATED_PRESET_PACK_ID);
	}

	public static void exportPresetAsDatapack(
		Path outputPath,
		Preset preset,
		String presetName,
		HolderLookup.Provider registries
	) throws IOException {
		if (registries == null) {
			registries = VanillaRegistries.createLookup();
		}
		Path datagenPath = Files.createTempDirectory("datagen-target-");
		Path datagenOutputPath = datagenPath.resolve("output");
		try {
			DataGenerator dataGenerator = Datapacks.makePreset(
				preset,
				registries,
				datagenPath,
				datagenOutputPath,
				presetName
			);
			dataGenerator.run();
			writeZipAtomically(datagenOutputPath, outputPath);
		} finally {
			PathUtils.deleteDirectory(datagenPath);
		}
		FTFCommon.LOGGER.info("Exported datapack to {}", outputPath);
	}

	public static void writeZipAtomically(Path input, Path output) throws IOException {
		Path parent = output.getParent();
		if (parent == null) {
			throw new IOException("Preset datapack output has no parent directory: " + output);
		}
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
		try {
			Files.delete(temporary);
			copyToZip(input, temporary);
			try {
				Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public static void copyToZip(Path input, Path output) throws IOException {
		Map<String, String> env = ImmutableMap.of("create", "true");
		URI uri = URI.create("jar:" + output.toUri());
		try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
			PathUtils.copyDirectory(input, fs.getPath("/"), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
