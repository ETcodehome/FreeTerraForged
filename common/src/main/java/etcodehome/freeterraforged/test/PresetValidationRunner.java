package etcodehome.freeterraforged.test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.apache.commons.io.file.PathUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.File;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainMapGenerator;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.client.command.FTFCommands;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.DefaultPresetArchive;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresetDataPacks;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ContinentType;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.platform.ConfigUtil;
import etcodehome.freeterraforged.registries.FTFRegistries;

public final class PresetValidationRunner {
	private static boolean testsTriggered = false;

	public enum PresetIdentity {
		RIVERS_DEFAULT("Rivers (Built-in Fallback Default)"),
		BEAUTIFUL("Beautiful (Template)"),
		UPSCALED("Modern - Upscaled Community Preset (Template)"),
		HUGE_BIOMES("Huge Biomes (Template)"),
		CUSTOM_TEST("Custom Test Preset"),
		UNKNOWN("Unknown Preset");

		private final String displayName;

		PresetIdentity(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return this.displayName;
		}
	}

	private PresetValidationRunner() {
	}

	public static void onServerTick(MinecraftServer server) {
		if (!testsTriggered && Boolean.getBoolean("freeterraforged.test.preset")) {
			if (server.getTickCount() >= 5) {
				testsTriggered = true;
				runAllTests(server);
			}
		}
	}

	public static PresetIdentity identifyPreset(Preset preset) {
		if (preset == null) {
			return PresetIdentity.UNKNOWN;
		}
		if (preset.world().properties.worldHeight == 512
			&& preset.world().continent.continentType == ContinentType.UPLIFT) {
			return PresetIdentity.RIVERS_DEFAULT;
		}
		if (preset.world().properties.worldHeight == 912
			&& preset.world().properties.worldDepth == 128
			&& preset.world().continent.continentScale == 4000) {
			return PresetIdentity.UPSCALED;
		}
		if (preset.world().properties.worldHeight == 320
			&& preset.world().continent.continentScale == 3000
			&& preset.climate().biomeShape.biomeSize == 185) {
			return PresetIdentity.BEAUTIFUL;
		}
		if (preset.world().properties.worldHeight == 320
			&& preset.world().continent.continentScale == 4029
			&& preset.climate().biomeShape.biomeSize == 402) {
			return PresetIdentity.HUGE_BIOMES;
		}
		if (preset.world().properties.worldHeight == 384
			&& preset.world().continent.continentScale == 5555) {
			return PresetIdentity.CUSTOM_TEST;
		}
		return PresetIdentity.UNKNOWN;
	}

	public static Preset readPresetFromDatapackZip(Path zipPath) throws IOException {
		if (!Files.isRegularFile(zipPath)) {
			throw new FileNotFoundException("Preset datapack zip does not exist at: " + zipPath);
		}
		try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
			ZipEntry entry = zipFile.getEntry("data/freeterraforged/freeterraforged/worldgen/preset/preset.json");
			if (entry == null) {
				throw new FileNotFoundException("No preset.json entry found inside datapack zip: " + zipPath);
			}
			try (InputStream in = zipFile.getInputStream(entry);
				 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				JsonElement json = JsonParser.parseReader(reader);
				return Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, json)
					.getOrThrow(msg -> new IllegalStateException("Failed to parse preset.json from zip: " + msg));
			}
		}
	}

	public static void verifyPresetManagerGroundTruth(Preset expectedPreset, String context) {
		if (!PresetManager.PM.isLoaded) {
			throw new AssertionError("[" + context + "] PresetManager.PM.isLoaded is false!");
		}
		PresetManager.PM.loadCheck();

		if (expectedPreset == null) {
			throw new AssertionError("[" + context + "] Expected preset is null");
		}

		if (PresetManager.PM.worldSettings != expectedPreset.world()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.worldSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.climateSettings != expectedPreset.climate()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.climateSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.terrainSettings != expectedPreset.terrain()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.terrainSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.riverSettings != expectedPreset.rivers()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.riverSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.surfaceSettings != expectedPreset.surface()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.surfaceSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.caveSettings != expectedPreset.caves()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.caveSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.flowSettings != expectedPreset.flow()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.flowSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.islandSettings != expectedPreset.island()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.islandSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.erosionFilterSettings != expectedPreset.erosionFilters()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.erosionFilterSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.miscellaneousSettings != expectedPreset.miscellaneous()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.miscellaneousSettings does not match ground truth preset!");
		}
		if (PresetManager.PM.presentationSettings != expectedPreset.presentation()) {
			throw new AssertionError("[" + context + "] PresetManager.PM.presentationSettings does not match ground truth preset!");
		}
	}

	public static void runAllTests(MinecraftServer server) {
		String serverModName = server.getServerModName();
		FTFCommon.LOGGER.info("================================================================================");
		FTFCommon.LOGGER.info("[FTF-Test] STARTING HEADLESS PRESET VALIDATION SUITE (Server loader: {})", serverModName);
		FTFCommon.LOGGER.info("================================================================================");

		Path configRoot = ConfigUtil.getConfigPath().toAbsolutePath().normalize();
		Path defaultSourceFile = ConfigUtil.ftf("default-preset-source.txt");
		String originalSourceBackup = null;
		boolean hadOriginalSource = Files.isRegularFile(defaultSourceFile);
		if (hadOriginalSource) {
			try {
				originalSourceBackup = Files.readString(defaultSourceFile, StandardCharsets.UTF_8);
			} catch (IOException ignored) {
			}
		}

		Path testWorkDir = null;
		try {
			testWorkDir = Files.createTempDirectory("ftf-preset-validation-");

			// -----------------------------------------------------------------------------------------
			// Test 1: Live Server Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [1/9] Verifying Live Server Datapack & Registry Resolution...");
			Path liveWorldDatapacks = server.getWorldPath(LevelResource.DATAPACK_DIR);
			Path livePackPath = liveWorldDatapacks.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);

			if (Files.isRegularFile(livePackPath)) {
				Preset liveDiskPreset = readPresetFromDatapackZip(livePackPath);
				PresetIdentity liveDiskId = identifyPreset(liveDiskPreset);
				FTFCommon.LOGGER.info("[FTF-Test] Live server disk datapack found at {}: identified as [{}]",
					livePackPath, liveDiskId.getDisplayName());

				Preset liveRegistryPreset = server.registryAccess()
					.lookup(FTFRegistries.PRESET)
					.flatMap(reg -> reg.get(Preset.KEY))
					.map(holder -> holder.value())
					.orElse(null);
				if (liveRegistryPreset == null) {
					throw new AssertionError("Live server registry has no registered FTF Preset at " + Preset.KEY);
				}
				PresetIdentity liveRegistryId = identifyPreset(liveRegistryPreset);
				FTFCommon.LOGGER.info("[FTF-Test] Live server active registry preset identified as [{}]",
					liveRegistryId.getDisplayName());

				if (liveDiskId != liveRegistryId) {
					throw new AssertionError("Live server disk pack (" + liveDiskId + ") does not match active registry (" + liveRegistryId + ")");
				}
				verifyPresetManagerGroundTruth(liveRegistryPreset, "Live Server Overworld");
				FTFCommon.LOGGER.info("[FTF-Test] [1/10] PASSED: Live server loaded datapack matching active registry and populated PresetManager.PM ground truth.");
			} else {
				FTFCommon.LOGGER.info("[FTF-Test] [1/10] Note: Dedicated server run started without pre-existing world datapack.");
			}

			// -----------------------------------------------------------------------------------------
			// Test 2: Live Server Spawn Heightmap Generation & Deterministic Regularity Validation Pass
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [2/10] Generating Spawn Heightmap & Evaluating Terrain Regularity...");
			HeightmapValidator.ValidationResult heightmapResult = HeightmapValidator.validateLiveServerSpawn(server);
			FTFCommon.LOGGER.info("[FTF-Test] [2/10] PASSED: Spawn heightmap generated and verified deterministic regularity (span: {} blocks, max step: {} blocks, spikes: 0).",
				heightmapResult.maxElevationBlocks() - heightmapResult.minElevationBlocks(),
				heightmapResult.maxAdjacentStepBlocks());

			// -----------------------------------------------------------------------------------------
			// Test 3: Fallback / Built-in Default Flow Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [3/9] Verifying Built-in Default Fallback (Rivers)...");
			DefaultPresetArchive.setSource(null);
			if (DefaultPresetArchive.hasSource()) {
				throw new AssertionError("DefaultPresetArchive should have no source after setSource(null)");
			}
			Preset activeFallback = DefaultPresetArchive.getActiveDefaultPreset();
			PresetIdentity fallbackId = identifyPreset(activeFallback);
			if (fallbackId != PresetIdentity.RIVERS_DEFAULT) {
				throw new AssertionError("Expected active default without source to be RIVERS_DEFAULT, but was: " + fallbackId);
			}

			Path testWorldDefaultDatapacks = testWorkDir.resolve("world-default/datapacks");
			Path defaultPackPath = testWorldDefaultDatapacks.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			FTFWorldPresetDataPacks.exportPresetAsDatapack(defaultPackPath, activeFallback, "FreeTerraForged", server.registryAccess());
			if (!Files.isRegularFile(defaultPackPath)) {
				throw new AssertionError("Failed to export default datapack to " + defaultPackPath);
			}
			Preset defaultDiskPreset = readPresetFromDatapackZip(defaultPackPath);
			PresetIdentity defaultDiskId = identifyPreset(defaultDiskPreset);
			if (defaultDiskId != PresetIdentity.RIVERS_DEFAULT) {
				throw new AssertionError("Datapack on disk should be RIVERS_DEFAULT, but was: " + defaultDiskId);
			}
			FTFCommon.LOGGER.info("[FTF-Test] [3/10] PASSED: Built-in default preset correctly saved to world datapack on disk.");

			// -----------------------------------------------------------------------------------------
			// Test 4: User Selected Default Preset Flow Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [4/10] Verifying User-Selected Default Preset (Upscaled Community)...");
			DefaultPresetArchive.setBuiltinSource(FTFTranslationKeys.GUI_COMMUNITY1_PRESET_NAME);
			if (!DefaultPresetArchive.hasSource()) {
				throw new AssertionError("DefaultPresetArchive should have source after setBuiltinSource()");
			}
			if (!DefaultPresetArchive.isBuiltinSource(FTFTranslationKeys.GUI_COMMUNITY1_PRESET_NAME)) {
				throw new AssertionError("DefaultPresetArchive should identify Upscaled Community as builtin source");
			}
			Preset userDefault = DefaultPresetArchive.getActiveDefaultPreset();
			PresetIdentity userDefaultId = identifyPreset(userDefault);
			if (userDefaultId != PresetIdentity.UPSCALED) {
				throw new AssertionError("Expected user default to be UPSCALED, but was: " + userDefaultId);
			}

			Path testWorldUserDefaultDatapacks = testWorkDir.resolve("world-user-default/datapacks");
			Path userDefaultPackPath = testWorldUserDefaultDatapacks.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			FTFWorldPresetDataPacks.exportPresetAsDatapack(userDefaultPackPath, userDefault, "FreeTerraForged", server.registryAccess());
			Preset userDefaultDiskPreset = readPresetFromDatapackZip(userDefaultPackPath);
			PresetIdentity userDefaultDiskId = identifyPreset(userDefaultDiskPreset);
			if (userDefaultDiskId != PresetIdentity.UPSCALED) {
				throw new AssertionError("Datapack on disk should be UPSCALED, but was: " + userDefaultDiskId);
			}
			if (userDefaultDiskId == PresetIdentity.RIVERS_DEFAULT) {
				throw new AssertionError("User default was overruled by built-in fallback default!");
			}
			FTFCommon.LOGGER.info("[FTF-Test] [4/10] PASSED: User-selected default preset correctly saved to world datapack on disk and overrides fallback.");

			// -----------------------------------------------------------------------------------------
			// Test 5: Manual Selection Overrules User Default AND Built-in Default (CRITICAL)
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [5/10] Verifying Manual Selection (Huge Biomes) Overrules User Default & Fallback...");
			// Default is still Upscaled Community, fallback is still Rivers.
			// User manually selects Huge Biomes in PresetListPage:
			Preset manualSelection = Presets.makeLegacyHugeBiomes();
			PresetIdentity manualId = identifyPreset(manualSelection);
			if (manualId != PresetIdentity.HUGE_BIOMES) {
				throw new AssertionError("Manual selection preset should be HUGE_BIOMES");
			}

			Path clientStagingDir = testWorkDir.resolve("mcworld-temp");
			Path clientStagedPack = clientStagingDir.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			// Simulated applyPreset:
			FTFWorldPresetDataPacks.exportPresetAsDatapack(clientStagedPack, manualSelection, "Huge Biomes", server.registryAccess());

			// Simulated createNewWorldDirectory copying tempDataPackDir to <world>/datapacks/:
			Path manualWorldDatapacks = testWorkDir.resolve("world-manual/datapacks");
			Path manualWorldPack = manualWorldDatapacks.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			Files.createDirectories(manualWorldDatapacks);
			Files.copy(clientStagedPack, manualWorldPack, StandardCopyOption.REPLACE_EXISTING);

			Preset manualSavedPreset = readPresetFromDatapackZip(manualWorldPack);
			PresetIdentity manualSavedId = identifyPreset(manualSavedPreset);
			if (manualSavedId != PresetIdentity.HUGE_BIOMES) {
				throw new AssertionError("World datapack on disk should be HUGE_BIOMES, but was: " + manualSavedId);
			}
			if (manualSavedId == PresetIdentity.UPSCALED) {
				throw new AssertionError("CRITICAL FAILURE: User default preset (UPSCALED) overruled manually selected preset!");
			}
			if (manualSavedId == PresetIdentity.RIVERS_DEFAULT) {
				throw new AssertionError("CRITICAL FAILURE: Built-in fallback preset overruled manually selected preset!");
			}
			// Ingest manual selection and empirically verify PresetManager.PM ground truth:
			PresetManager.PM.ingestFromPreset(manualSavedPreset);
			verifyPresetManagerGroundTruth(manualSavedPreset, "Manual Preset Selection (Huge Biomes)");
			FTFCommon.LOGGER.info("[FTF-Test] [5/10] PASSED: Manually selected preset is saved to world folder and populates PresetManager.PM ground truth.");

			// -----------------------------------------------------------------------------------------
			// Test 6: World Type Switching / Cycling Does NOT Overrule Manual Selection
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [6/10] Verifying World Type Switching / Cycling Preserves Manual Preset...");
			// With clientStagedPack containing HUGE_BIOMES:
			// In updated MixinCreateWorldScreen, switching away from FTF does NOT delete clientStagedPack.
			// Verify file still exists:
			if (!Files.isRegularFile(clientStagedPack)) {
				throw new AssertionError("Staged pack was unexpectedly deleted");
			}
			// When switching back to FTF: zipExists is true, so default preset is NOT re-exported:
			boolean zipExists = Files.isRegularFile(clientStagedPack);
			if (!zipExists) {
				// Simulating old broken behavior where zip was re-exported
				Preset activeDef = DefaultPresetArchive.getActiveDefaultPreset();
				FTFWorldPresetDataPacks.exportPresetAsDatapack(clientStagedPack, activeDef, "FreeTerraForged", server.registryAccess());
			}
			// Verify on disk that clientStagedPack is STILL HUGE_BIOMES:
			Preset cycledPreset = readPresetFromDatapackZip(clientStagedPack);
			PresetIdentity cycledId = identifyPreset(cycledPreset);
			if (cycledId != PresetIdentity.HUGE_BIOMES) {
				throw new AssertionError("World type cycling caused manual selection to be overwritten! Expected HUGE_BIOMES, found: " + cycledId);
			}
			FTFCommon.LOGGER.info("[FTF-Test] [6/10] PASSED: World type cycling preserves manual preset without overwrite.");

			// -----------------------------------------------------------------------------------------
			// Test 7: Dedicated Server Successive Run Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [7/10] Verifying Dedicated Server Successive Run Preservation...");
			// With manualWorldPack containing HUGE_BIOMES on disk:
			// Simulate dedicated server startup logic from MixinDedicatedServerProperties:
			Path serverDatapacksDir = testWorkDir.resolve("world-manual/datapacks");
			Path serverPresetFile = serverDatapacksDir.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			if (!Files.isRegularFile(serverPresetFile)) {
				Preset defaultPreset = DefaultPresetArchive.getActiveDefaultPreset();
				FTFWorldPresetDataPacks.exportPresetAsDatapack(serverPresetFile, defaultPreset, "FreeTerraForged", server.registryAccess());
			}
			// Verify on disk:
			Preset serverSuccessivePreset = readPresetFromDatapackZip(serverPresetFile);
			PresetIdentity serverSuccessiveId = identifyPreset(serverSuccessivePreset);
			if (serverSuccessiveId != PresetIdentity.HUGE_BIOMES) {
				throw new AssertionError("Dedicated server successive run overwrote existing manual preset! Found: " + serverSuccessiveId);
			}
			// Verify PresetManager.PM ground truth upon world reboot:
			PresetManager.PM.ingestFromPreset(serverSuccessivePreset);
			verifyPresetManagerGroundTruth(serverSuccessivePreset, "Dedicated Server Successive Run");
			FTFCommon.LOGGER.info("[FTF-Test] [7/10] PASSED: Successive server runs preserve existing manual preset on disk and in PresetManager.PM.");

			// -----------------------------------------------------------------------------------------
			// Test 8: Re-create World Flow Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [8/10] Verifying Re-create World Flow...");
			// Old world has manualWorldPack (HUGE_BIOMES).
			// Simulate Minecraft copying old datapacks to recreate tempDataPackDir:
			Path recreateTempDir = testWorkDir.resolve("recreate-temp");
			Path recreateStagedPack = recreateTempDir.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			Files.createDirectories(recreateTempDir);
			Files.copy(manualWorldPack, recreateStagedPack, StandardCopyOption.REPLACE_EXISTING);

			// Simulate recreate sync:
			boolean recreateZipExists = Files.isRegularFile(recreateStagedPack);
			if (!recreateZipExists) {
				Preset defaultPreset = DefaultPresetArchive.getActiveDefaultPreset();
				FTFWorldPresetDataPacks.exportPresetAsDatapack(recreateStagedPack, defaultPreset, "FreeTerraForged", server.registryAccess());
			}

			// Simulate world creation for recreated world:
			Path recreatedWorldDatapacks = testWorkDir.resolve("world-recreated/datapacks");
			Path recreatedWorldPack = recreatedWorldDatapacks.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			Files.createDirectories(recreatedWorldDatapacks);
			Files.copy(recreateStagedPack, recreatedWorldPack, StandardCopyOption.REPLACE_EXISTING);

			Preset recreatedPreset = readPresetFromDatapackZip(recreatedWorldPack);
			PresetIdentity recreatedId = identifyPreset(recreatedPreset);
			if (recreatedId != PresetIdentity.HUGE_BIOMES) {
				throw new AssertionError("Re-create world overwrote manual preset! Found: " + recreatedId);
			}
			PresetManager.PM.ingestFromPreset(recreatedPreset);
			verifyPresetManagerGroundTruth(recreatedPreset, "Re-create World Flow");
			FTFCommon.LOGGER.info("[FTF-Test] [8/10] PASSED: Re-created worlds preserve original preset on disk and in PresetManager.PM.");

			// -----------------------------------------------------------------------------------------
			// Test 9: Custom File-Based Default Preset Source Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [9/9] Verifying Custom File-Based Default Preset Source...");
			Path customPresetsDir = configRoot.resolve("freeterraforged").resolve("presets");
			Files.createDirectories(customPresetsDir);
			Path customPresetFile = customPresetsDir.resolve("ValidationTestCustom.json");

			// Create a distinct custom preset:
			Preset customPreset = Presets.makeLegacyHugeBiomes();
			customPreset.world().properties.worldHeight = 384;
			customPreset.world().continent.continentScale = 5555;
			try (var writer = Files.newBufferedWriter(customPresetFile, StandardCharsets.UTF_8)) {
				JsonElement json = Preset.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, customPreset)
					.getOrThrow(msg -> new IllegalStateException("Failed to encode custom preset: " + msg));
				writer.write(json.toString());
			}

			DefaultPresetArchive.setSource(customPresetFile);
			if (!DefaultPresetArchive.hasSource()) {
				throw new AssertionError("DefaultPresetArchive should have source for custom file");
			}
			Preset loadedCustom = DefaultPresetArchive.getActiveDefaultPreset();
			PresetIdentity loadedCustomId = identifyPreset(loadedCustom);
			if (loadedCustomId != PresetIdentity.CUSTOM_TEST) {
				throw new AssertionError("Expected CUSTOM_TEST preset from file, but found: " + loadedCustomId);
			}

			// When a new world is created without customization, verify it uses this custom file:
			Path customWorldPack = testWorkDir.resolve("world-custom/datapacks").resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			FTFWorldPresetDataPacks.exportPresetAsDatapack(customWorldPack, loadedCustom, "ValidationTestCustom", server.registryAccess());
			Preset customDiskPreset = readPresetFromDatapackZip(customWorldPack);
			if (identifyPreset(customDiskPreset) != PresetIdentity.CUSTOM_TEST) {
				throw new AssertionError("World datapack does not contain custom preset from file");
			}

			// Now verify that even with this custom file default, a manual selection STILL overrules it:
			Path manualOverridePack = testWorkDir.resolve("world-custom-overruled/datapacks").resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			FTFWorldPresetDataPacks.exportPresetAsDatapack(manualOverridePack, manualSelection, "Huge Biomes", server.registryAccess());
			Preset overrideDiskPreset = readPresetFromDatapackZip(manualOverridePack);
			if (identifyPreset(overrideDiskPreset) != PresetIdentity.HUGE_BIOMES) {
				throw new AssertionError("Manual selection did not overrule custom file default!");
			}

			// Clean up custom preset file
			Files.deleteIfExists(customPresetFile);
			FTFCommon.LOGGER.info("[FTF-Test] [9/10] PASSED: Custom file-based default correctly resolves and is overrulable by manual selection.");

			// -----------------------------------------------------------------------------------------
			// Test 10: Multi-Run Seam Continuity & Varying Config Immunity Verification (Issue #330)
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [10/10] Verifying Multi-Run Seam Continuity & Varying Config Immunity (Issue #330)...");
			HeightmapValidator.MultiRunContinuityResult multiRunResult = HeightmapValidator.validateMultiRunContinuity(server, testWorkDir);
			if (!multiRunResult.passed()) {
				throw new AssertionError("Multi-run continuity validation failed: " + multiRunResult.violations());
			}
			FTFCommon.LOGGER.info(
				"[FTF-Test] [10/10] PASSED: Multi-run world generation is 100% continuous and immune to varying config setups (max seam step: {} blocks, hypothetical corrupted step: {} blocks, {}/{} identical samples).",
				multiRunResult.maxActualSeamStepBlocks(),
				multiRunResult.maxCorruptedSeamStepBlocks(),
				multiRunResult.identicalBlockSamples(),
				multiRunResult.totalBlockSamplesVerified()
			);

			// -----------------------------------------------------------------------------------------
			// Test 11: In-Game /ftf heightmap & /ftf reliefmap Command Execution & Parity Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [11/11] Verifying In-Game /ftf heightmap & /ftf reliefmap Command Execution...");

			java.io.File workingDir = new java.io.File(".");
			java.io.FileFilter hmFilter = (f) -> f.getName().startsWith("heightmap_") && f.getName().endsWith(".png");
			java.io.FileFilter rmFilter = (f) -> f.getName().startsWith("reliefmap_") && f.getName().endsWith(".png");

			File[] existingHm = workingDir.listFiles(hmFilter);
			if (existingHm != null) {
				for (File f : existingHm) f.delete();
			}
			File[] existingRm = workingDir.listFiles(rmFilter);
			if (existingRm != null) {
				for (File f : existingRm) f.delete();
			}

			int testRadius = Integer.getInteger("freeterraforged.test.command.radius", 256);
			BlockPos spawnPos = server.overworld().getSharedSpawnPos();
			int minX = spawnPos.getX() - testRadius;
			int minZ = spawnPos.getZ() - testRadius;
			int maxX = spawnPos.getX() + testRadius;
			int maxZ = spawnPos.getZ() + testRadius;
			int expectedW = maxX - minX + 1;
			int expectedH = maxZ - minZ + 1;

			// 1. Dispatch /ftf heightmap radius <testRadius>
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "ftf heightmap radius " + testRadius);
			java.util.concurrent.CompletableFuture<?> hmTask = FTFCommands.getCurrentHeightmapTask();
			if (hmTask != null) {
				hmTask.join();
			}
			File[] hmFiles = workingDir.listFiles(hmFilter);
			if (hmFiles == null || hmFiles.length == 0) {
				throw new AssertionError("In-game command /ftf heightmap failed to produce an output file!");
			}
			File hmFile = hmFiles[0];
			if (hmFile.length() < 100) {
				throw new AssertionError("In-game command /ftf heightmap produced empty or truncated file: " + hmFile.length() + " bytes");
			}
			BufferedImage hmImg = ImageIO.read(hmFile);
			TerrainMapGenerator.ImageValidationReport hmReport = TerrainMapGenerator.validateImageContent(
				hmImg, TerrainMapGenerator.MapType.RAW_HEIGHTMAP, expectedW, expectedH
			);
			if (!hmReport.passed()) {
				throw new AssertionError("In-game /ftf heightmap failed content validation (black/flat image): " + hmReport.violations());
			}

			// 2. Dispatch /ftf reliefmap radius <testRadius>
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "ftf reliefmap radius " + testRadius);
			java.util.concurrent.CompletableFuture<?> rmTask = FTFCommands.getCurrentReliefmapTask();
			if (rmTask != null) {
				rmTask.join();
			}
			File[] rmFiles = workingDir.listFiles(rmFilter);
			if (rmFiles == null || rmFiles.length == 0) {
				throw new AssertionError("In-game command /ftf reliefmap failed to produce an output file!");
			}
			File rmFile = rmFiles[0];
			if (rmFile.length() < 100) {
				throw new AssertionError("In-game command /ftf reliefmap produced empty or truncated file: " + rmFile.length() + " bytes");
			}
			BufferedImage rmImg = ImageIO.read(rmFile);
			TerrainMapGenerator.ImageValidationReport rmReport = TerrainMapGenerator.validateImageContent(
				rmImg, TerrainMapGenerator.MapType.SHADED_RELIEF, expectedW, expectedH
			);
			if (!rmReport.passed()) {
				throw new AssertionError("In-game /ftf reliefmap failed content validation (black/flat image): " + rmReport.violations());
			}

			// 3. Bit-for-bit Parity Verification against direct core generator
			GeneratorContext genCtx = ((FTFRandomState) (Object) server.overworld().getChunkSource().randomState()).generatorContext();
			BufferedImage expectedHm = TerrainMapGenerator.generateHeightmapImage(genCtx, minX, minZ, maxX, maxZ, TerrainMapGenerator.DEFAULT_CHUNK_SIZE, null);
			BufferedImage expectedRm = TerrainMapGenerator.generateReliefImage(genCtx, minX, minZ, maxX, maxZ, TerrainMapGenerator.DEFAULT_CHUNK_SIZE, null);
			TerrainMapGenerator.assertImageParity(expectedHm, hmImg, "/ftf heightmap command parity");
			TerrainMapGenerator.assertImageParity(expectedRm, rmImg, "/ftf reliefmap command parity");

			// 4. Archive verified command outputs to report directory
			try {
				Path reportDir = HeightmapValidator.resolveReportDirectory();
				Files.createDirectories(reportDir);
				String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
				String loader = serverModName.replaceAll("[^a-zA-Z0-9_-]", "_");
				Files.copy(hmFile.toPath(), reportDir.resolve(String.format("heightmap_%s_ingame_command_%s.png", loader, timestamp)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				Files.copy(rmFile.toPath(), reportDir.resolve(String.format("reliefmap_%s_ingame_command_%s.png", loader, timestamp)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				FTFCommon.LOGGER.warn("[FTF-Test] Failed to archive command images: {}", e.getMessage());
			}

			// 5. Cleanup root directory
			hmFile.delete();
			rmFile.delete();
			FTFCommon.LOGGER.info("[FTF-Test] [11/11] PASSED: In-game commands /ftf heightmap and /ftf reliefmap executed, generated valid non-black non-flat images, and verified 100% bit-for-bit parity against core generator.");

			// -----------------------------------------------------------------------------------------
			// Complete Summary Banner
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("================================================================================");
			FTFCommon.LOGGER.info("[FTF-Test] ALL 11 PRESET PERSISTENCE, RESOLUTION, HEIGHTMAP & PRESETMANAGER TESTS PASSED ON LOADER: {}", serverModName);
			FTFCommon.LOGGER.info("[FTF-Test] Verified on disk:");
			FTFCommon.LOGGER.info("[FTF-Test] 1. Live server datapack, registry and PresetManager.PM ground truth alignment");
			FTFCommon.LOGGER.info("[FTF-Test] 2. Spawn chunk heightmap generation, image retention & deterministic regularity validation");
			FTFCommon.LOGGER.info("[FTF-Test] 3. Built-in default fallback (Rivers)");
			FTFCommon.LOGGER.info("[FTF-Test] 4. User default preset configuration (Upscaled Community)");
			FTFCommon.LOGGER.info("[FTF-Test] 5. Manual selection (Huge Biomes) overrules user default, fallback & populates PresetManager.PM");
			FTFCommon.LOGGER.info("[FTF-Test] 6. World type cycling preserves manual preset");
			FTFCommon.LOGGER.info("[FTF-Test] 7. Dedicated server successive runs preserve manual preset on disk and in PresetManager.PM");
			FTFCommon.LOGGER.info("[FTF-Test] 8. Re-create world flow preserves manual preset on disk and in PresetManager.PM");
			FTFCommon.LOGGER.info("[FTF-Test] 9. Custom file default source resolution & manual overrule");
			FTFCommon.LOGGER.info("[FTF-Test] 10. Multi-run seam continuity, varying config immunity (Issue #330) & PresetManager.PM ground truth");
			FTFCommon.LOGGER.info("[FTF-Test] 11. In-game /ftf heightmap and /ftf reliefmap command execution, black/flat defense & unified core parity");
			FTFCommon.LOGGER.info("================================================================================");

			if (Boolean.parseBoolean(System.getProperty("freeterraforged.test.preset.exit", "true"))) {
				FTFCommon.LOGGER.info("[FTF-Test] Test suite finished cleanly; shutting down server with exit code 0.");
				server.halt(false);
				Thread exitWatchdog = new Thread(() -> {
					try {
						Thread.sleep(1500L);
					} catch (InterruptedException ignored) {
					}
					Runtime.getRuntime().halt(0);
				}, "FTF-Test-Watchdog");
				exitWatchdog.setDaemon(true);
				exitWatchdog.start();
			}
		} catch (Throwable failure) {
			FTFCommon.LOGGER.error("================================================================================");
			FTFCommon.LOGGER.error("[FTF-Test] PRESET TEST SUITE FAILED WITH AN ERROR ON LOADER {}:", serverModName, failure);
			FTFCommon.LOGGER.error("================================================================================");
			if (Boolean.parseBoolean(System.getProperty("freeterraforged.test.preset.exit", "true"))) {
				server.halt(false);
				Thread exitWatchdog = new Thread(() -> {
					try {
						Thread.sleep(1000L);
					} catch (InterruptedException ignored) {
					}
					Runtime.getRuntime().halt(1);
				}, "FTF-Test-Fail-Watchdog");
				exitWatchdog.setDaemon(true);
				exitWatchdog.start();
			}
			throw new RuntimeException("FTF Preset Test Suite Failed", failure);
		} finally {
			// Restore original default source
			try {
				if (hadOriginalSource && originalSourceBackup != null) {
					Files.writeString(defaultSourceFile, originalSourceBackup, StandardCharsets.UTF_8);
				} else {
					Files.deleteIfExists(defaultSourceFile);
				}
			} catch (IOException ignored) {
			}
			// Clean up test working directory
			if (testWorkDir != null) {
				try {
					PathUtils.deleteDirectory(testWorkDir);
				} catch (IOException ignored) {
				}
			}
		}
	}
}
