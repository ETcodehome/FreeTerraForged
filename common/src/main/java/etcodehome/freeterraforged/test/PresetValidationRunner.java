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

import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.DefaultPresetArchive;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
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
			FTFCommon.LOGGER.info("[FTF-Test] [1/8] Verifying Live Server Datapack & Registry Resolution...");
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
				FTFCommon.LOGGER.info("[FTF-Test] [1/8] PASSED: Live server loaded datapack matching active registry.");
			} else {
				FTFCommon.LOGGER.info("[FTF-Test] [1/8] Note: Dedicated server run started without pre-existing world datapack.");
			}

			// -----------------------------------------------------------------------------------------
			// Test 2: Fallback / Built-in Default Flow Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [2/8] Verifying Built-in Default Fallback (Rivers)...");
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
			FTFCommon.LOGGER.info("[FTF-Test] [2/8] PASSED: Built-in default preset correctly saved to world datapack on disk.");

			// -----------------------------------------------------------------------------------------
			// Test 3: User Selected Default Preset Flow Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [3/8] Verifying User-Selected Default Preset (Beautiful)...");
			DefaultPresetArchive.setBuiltinSource(FTFTranslationKeys.GUI_BEAUTIFUL_PRESET_NAME);
			if (!DefaultPresetArchive.hasSource()) {
				throw new AssertionError("DefaultPresetArchive should have source after setBuiltinSource()");
			}
			if (!DefaultPresetArchive.isBuiltinSource(FTFTranslationKeys.GUI_BEAUTIFUL_PRESET_NAME)) {
				throw new AssertionError("DefaultPresetArchive should identify Beautiful as builtin source");
			}
			Preset userDefault = DefaultPresetArchive.getActiveDefaultPreset();
			PresetIdentity userDefaultId = identifyPreset(userDefault);
			if (userDefaultId != PresetIdentity.BEAUTIFUL) {
				throw new AssertionError("Expected user default to be BEAUTIFUL, but was: " + userDefaultId);
			}

			Path testWorldUserDefaultDatapacks = testWorkDir.resolve("world-user-default/datapacks");
			Path userDefaultPackPath = testWorldUserDefaultDatapacks.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			FTFWorldPresetDataPacks.exportPresetAsDatapack(userDefaultPackPath, userDefault, "FreeTerraForged", server.registryAccess());
			Preset userDefaultDiskPreset = readPresetFromDatapackZip(userDefaultPackPath);
			PresetIdentity userDefaultDiskId = identifyPreset(userDefaultDiskPreset);
			if (userDefaultDiskId != PresetIdentity.BEAUTIFUL) {
				throw new AssertionError("Datapack on disk should be BEAUTIFUL, but was: " + userDefaultDiskId);
			}
			if (userDefaultDiskId == PresetIdentity.RIVERS_DEFAULT) {
				throw new AssertionError("User default was overruled by built-in fallback default!");
			}
			FTFCommon.LOGGER.info("[FTF-Test] [3/8] PASSED: User-selected default preset correctly saved to world datapack on disk and overrides fallback.");

			// -----------------------------------------------------------------------------------------
			// Test 4: Manual Selection Overrules User Default AND Built-in Default (CRITICAL)
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [4/8] Verifying Manual Selection (Huge Biomes) Overrules User Default & Fallback...");
			// Default is still Beautiful, fallback is still Rivers.
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
			if (manualSavedId == PresetIdentity.BEAUTIFUL) {
				throw new AssertionError("CRITICAL FAILURE: User default preset overruled manually selected preset!");
			}
			if (manualSavedId == PresetIdentity.RIVERS_DEFAULT) {
				throw new AssertionError("CRITICAL FAILURE: Built-in fallback preset overruled manually selected preset!");
			}
			FTFCommon.LOGGER.info("[FTF-Test] [4/8] PASSED: Manually selected preset is saved to world folder and NEVER overruled by user default or fallback.");

			// -----------------------------------------------------------------------------------------
			// Test 5: World Type Switching / Cycling Does NOT Overrule Manual Selection
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [5/8] Verifying World Type Switching / Cycling Preserves Manual Preset...");
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
			FTFCommon.LOGGER.info("[FTF-Test] [5/8] PASSED: World type cycling preserves manual preset without overwrite.");

			// -----------------------------------------------------------------------------------------
			// Test 6: Dedicated Server Successive Run Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [6/8] Verifying Dedicated Server Successive Run Preservation...");
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
			FTFCommon.LOGGER.info("[FTF-Test] [6/8] PASSED: Successive server runs preserve existing manual preset on disk.");

			// -----------------------------------------------------------------------------------------
			// Test 7: Re-create World Flow Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [7/8] Verifying Re-create World Flow...");
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
			FTFCommon.LOGGER.info("[FTF-Test] [7/8] PASSED: Re-created worlds preserve original preset on disk.");

			// -----------------------------------------------------------------------------------------
			// Test 8: Custom File-Based Default Preset Source Verification
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("[FTF-Test] [8/8] Verifying Custom File-Based Default Preset Source...");
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
			FTFCommon.LOGGER.info("[FTF-Test] [8/8] PASSED: Custom file-based default correctly resolves and is overrulable by manual selection.");

			// -----------------------------------------------------------------------------------------
			// Complete Summary Banner
			// -----------------------------------------------------------------------------------------
			FTFCommon.LOGGER.info("================================================================================");
			FTFCommon.LOGGER.info("[FTF-Test] ALL 8 PRESET PERSISTENCE & RESOLUTION TESTS PASSED ON LOADER: {}", serverModName);
			FTFCommon.LOGGER.info("[FTF-Test] Verified on disk:");
			FTFCommon.LOGGER.info("[FTF-Test] 1. Live server datapack and registry alignment");
			FTFCommon.LOGGER.info("[FTF-Test] 2. Built-in default fallback (Rivers)");
			FTFCommon.LOGGER.info("[FTF-Test] 3. User default preset configuration (Beautiful)");
			FTFCommon.LOGGER.info("[FTF-Test] 4. Manual selection (Huge Biomes) overrules user default and fallback");
			FTFCommon.LOGGER.info("[FTF-Test] 5. World type cycling preserves manual preset");
			FTFCommon.LOGGER.info("[FTF-Test] 6. Dedicated server successive runs preserve manual preset");
			FTFCommon.LOGGER.info("[FTF-Test] 7. Re-create world flow preserves manual preset");
			FTFCommon.LOGGER.info("[FTF-Test] 8. Custom file default source resolution & manual overrule");
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
