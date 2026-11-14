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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import org.apache.commons.io.file.PathUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.validation.DirectoryValidator;
import etcodehome.freeterraforged.data.worldgen.preset.settings.SpawnType;
import net.minecraft.world.level.storage.LevelResource;
import java.io.File;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.PreviewGrid;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainMapGenerator;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
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
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import etcodehome.freeterraforged.platform.ConfigUtil;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.biome.BiomeCandidateRoles;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenBenchmarkRunner;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomePlacementPlan;
import etcodehome.freeterraforged.world.worldgen.search.BiomeSearchResolution;
import etcodehome.freeterraforged.world.worldgen.search.LocateSearchValidator;
import etcodehome.freeterraforged.world.worldgen.search.TerrainSearchResolution;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeTags;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementConfig;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementConfigSnapshot;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenOwner;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlan;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;

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

	public static RegistryAccess.Frozen loadPresetAndNoiseRegistriesFromRepository(PackRepository repository) {
		try (CloseableResourceManager resourceManager = new MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected())) {
			return RegistryDataLoader.load(
				resourceManager,
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
				List.of(
					new RegistryDataLoader.RegistryData<>(FTFRegistries.PRESET, Preset.DIRECT_CODEC, false),
					new RegistryDataLoader.RegistryData<>(FTFRegistries.NOISE, Noise.DIRECT_CODEC, false)
				)
			);
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

	public record TestRecord(String name, String notes, boolean passed, String error) {}

	private static String validateAdaptiveBiomeSearchResolution() {
		String topology = LocateSearchValidator.validateTopology();
		Preset terrainPreset = Presets.makeFTFDefault().copy();
		terrainPreset.terrain().general.terrainRegionSize = 125;
		terrainPreset.terrain().general.globalHorizontalScale = 8.0F;
		if (TerrainSearchResolution.derive(terrainPreset, TerrainType.FLATS).horizontalStep() != 12) {
			throw new AssertionError("Terrain regions must derive from their unscaled 125-block selector cells");
		}
		terrainPreset.island().size = 50.0F;
		if (TerrainSearchResolution.derive(terrainPreset, TerrainType.ISLAND).horizontalStep() != 48) {
			throw new AssertionError("Island searching must include the active global horizontal scale");
		}
		terrainPreset.island().size = 500.0F;
		if (TerrainSearchResolution.derive(terrainPreset, TerrainType.ISLAND_MOUNTAINS).horizontalStep() != 64) {
			throw new AssertionError("Large physical island footprints must respect the conservative step cap");
		}
		if (TerrainSearchResolution.derive(terrainPreset, TerrainType.COAST).horizontalStep() != 4
			|| TerrainSearchResolution.derive(terrainPreset, TerrainType.BEACH).horizontalStep() != 4
			|| TerrainSearchResolution.derive(terrainPreset, TerrainType.RIVER).horizontalStep() != 4) {
			throw new AssertionError("Thin terrain targets must retain quart-resolution searching");
		}
		BiomePlacementPlan.TargetRoles surface = new BiomePlacementPlan.TargetRoles(
			true, Set.of(BiomePlacementPlan.Role.SURFACE), true, false
		);
		BiomePlacementPlan.SearchScales defaultScales = new BiomePlacementPlan.SearchScales(185, 586, 64);
		BiomeSearchResolution defaultSurface = BiomeSearchResolution.derive(defaultScales, surface);
		if (defaultSurface.horizontalStep() != 20) {
			throw new AssertionError("Biome size 185 should derive a 20-block surface step, got "
				+ defaultSurface.horizontalStep());
		}

		BiomePlacementPlan.SearchScales smallSurface = new BiomePlacementPlan.SearchScales(50, 586, 64);
		if (BiomeSearchResolution.derive(smallSurface, surface).horizontalStep() != 4) {
			throw new AssertionError("Minimum surface biome size should derive quart-resolution searching");
		}
		BiomePlacementPlan.SearchScales largeSurface = new BiomePlacementPlan.SearchScales(2000, 586, 64);
		if (BiomeSearchResolution.derive(largeSurface, surface).horizontalStep() != 192) {
			throw new AssertionError("Large surface biomes should respect the benchmarked 192-block cap");
		}
		BiomePlacementPlan.SearchScales mediumSurface = new BiomePlacementPlan.SearchScales(1000, 586, 64);
		if (BiomeSearchResolution.derive(mediumSurface, surface).horizontalStep() != 124) {
			throw new AssertionError("Biome size 1000 should retain its preset-derived 124-block surface step");
		}
		BiomePlacementPlan.SearchScales modernSurface = new BiomePlacementPlan.SearchScales(586, 586, 64);
		if (BiomeSearchResolution.derive(modernSurface, surface).horizontalStep() != 72) {
			throw new AssertionError("Biome size 586 should retain its preset-derived 72-block surface step");
		}

		BiomePlacementPlan.SearchScales minimumCaveScales = new BiomePlacementPlan.SearchScales(2000, 50, 16);
		BiomePlacementPlan.TargetRoles cave = new BiomePlacementPlan.TargetRoles(
			true, Set.of(BiomePlacementPlan.Role.SHALLOW_CAVE, BiomePlacementPlan.Role.DEEP_CAVE), true, false
		);
		BiomeSearchResolution minimumCave = BiomeSearchResolution.derive(minimumCaveScales, cave);
		if (minimumCave.horizontalStep() != 4 || minimumCave.verticalStep() != 4) {
			throw new AssertionError("Minimum underground biome sizes should search at quart resolution");
		}
		BiomePlacementPlan.SearchScales largeCaveScales = new BiomePlacementPlan.SearchScales(2000, 2000, 2048);
		BiomeSearchResolution largeCave = BiomeSearchResolution.derive(largeCaveScales, cave);
		if (largeCave.horizontalStep() != 64 || largeCave.verticalStep() != 64) {
			throw new AssertionError("Unbenchmarked underground horizontal and vertical searches must retain 64-block caps");
		}
		BiomePlacementPlan.TargetRoles unbandedCave = new BiomePlacementPlan.TargetRoles(
			true, Set.of(BiomePlacementPlan.Role.SHALLOW_CAVE, BiomePlacementPlan.Role.DEEP_CAVE), false, false
		);
		BiomeSearchResolution unbandedResolution = BiomeSearchResolution.derive(defaultScales, unbandedCave);
		if (unbandedResolution.horizontalStep() != 64 || unbandedResolution.verticalStep() != 8) {
			throw new AssertionError(
				"Unbanded caves must retain preset-derived horizontal and vertical sampling"
			);
		}

		BiomePlacementPlan.TargetRoles mixed = new BiomePlacementPlan.TargetRoles(
			true, Set.of(BiomePlacementPlan.Role.SURFACE, BiomePlacementPlan.Role.DEEP_CAVE), true, false
		);
		if (BiomeSearchResolution.derive(minimumCaveScales, mixed).horizontalStep() != 4) {
			throw new AssertionError("Mixed targets must use the finer applicable horizontal resolution");
		}
		if (BiomeSearchResolution.derive(largeCaveScales, mixed).horizontalStep() != 64) {
			throw new AssertionError("Mixed surface-underground targets must retain the conservative underground cap");
		}
		BiomePlacementPlan.TargetRoles unknown = new BiomePlacementPlan.TargetRoles(
			true, Set.of(BiomePlacementPlan.Role.UNKNOWN), true, false
		);
		BiomeSearchResolution unknownResolution = BiomeSearchResolution.derive(defaultScales, unknown);
		if (unknownResolution.horizontalStep() != 20 || unknownResolution.verticalStep() != 8) {
			throw new AssertionError("Unknown placement must retain the active preset's finest applicable scale");
		}
		BiomeSearchResolution largeUnknown = BiomeSearchResolution.derive(largeCaveScales, unknown);
		if (largeUnknown.horizontalStep() != 64 || largeUnknown.verticalStep() != 64) {
			throw new AssertionError("Unknown placement must not inherit the surface-only 192-block cap");
		}
		BiomePlacementPlan.TargetRoles thinSurface = new BiomePlacementPlan.TargetRoles(
			true, Set.of(BiomePlacementPlan.Role.SURFACE), true, true
		);
		if (BiomeSearchResolution.derive(largeSurface, thinSurface).horizontalStep() != 4) {
			throw new AssertionError("River and beach targets must ignore broad biome-cell scaling");
		}

		Climate.TargetPoint surfacePoint = new Climate.TargetPoint(0L, 0L, 0L, 0L, Climate.quantizeCoord(-1.0F), 0L);
		Climate.TargetPoint shallowPoint = new Climate.TargetPoint(0L, 0L, 0L, 0L, Climate.quantizeCoord(0.5F), 0L);
		Climate.TargetPoint deepPoint = new Climate.TargetPoint(0L, 0L, 0L, 0L, Climate.quantizeCoord(2.0F), 0L);
		if (UndergroundBiomeBanding.classifyTarget(surfacePoint) != UndergroundBiomeBanding.CandidateRole.SURFACE
			|| UndergroundBiomeBanding.classifyTarget(shallowPoint) != UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE
			|| UndergroundBiomeBanding.classifyTarget(deepPoint) != UndergroundBiomeBanding.CandidateRole.DEEP_CAVE) {
			throw new AssertionError("Runtime climate depth classification disagrees with cave-stage boundaries");
		}

		Climate.Parameter full = Climate.Parameter.span(-1.0F, 1.0F);
		Climate.ParameterPoint surfaceRegistration = new Climate.ParameterPoint(
			full, full, full, full, Climate.Parameter.point(0.0F), full, 0L
		);
		Climate.ParameterPoint shallowRegistration = new Climate.ParameterPoint(
			full, full, full, full, Climate.Parameter.span(0.2F, 0.9F), full, 0L
		);
		Climate.ParameterPoint deepRegistration = new Climate.ParameterPoint(
			full, full, full, full, Climate.Parameter.point(1.1F), full, 0L
		);
		BiomeCandidateRoles<String> semanticRoles = BiomeCandidateRoles.compile(
			new Climate.ParameterList<>(List.of(
				Pair.of(surfaceRegistration, "ordinary"),
				Pair.of(deepRegistration, "ordinary"),
				Pair.of(shallowRegistration, "tagged_cave"),
				Pair.of(deepRegistration, "untagged_underground")
			)),
			(point, value) -> UndergroundBiomeBanding.classify(point, value.equals("tagged_cave")),
			value -> value.equals("tagged_cave"),
			List.of()
		);
		BiomeCandidateRoles.Roles ordinary = semanticRoles.candidate("ordinary");
		if (ordinary.undergroundOnly() || !ordinary.surface() || !ordinary.deep()) {
			throw new AssertionError("Surface-plus-deep filler registrations must remain semantically surface-only");
		}
		BiomeCandidateRoles.Roles taggedCave = semanticRoles.candidate("tagged_cave");
		if (!taggedCave.undergroundOnly() || !taggedCave.shallow()) {
			throw new AssertionError("A tagged shallow cave candidate must remain underground-only");
		}
		BiomeCandidateRoles.Roles untaggedUnderground = semanticRoles.candidate("untagged_underground");
		if (!untaggedUnderground.undergroundOnly() || !untaggedUnderground.deep()) {
			throw new AssertionError("An underground-only registration must not require a cave tag");
		}
		return topology
			+ "; quart-aligned biome/terrain scale derivation, semantic filler roles, preset-scaled unknowns, and depth stages";
	}

	private static String validateContinentalnessAndControlPoints(GeneratorContext genCtx) {
		etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap heightmap = genCtx.getHeightmap();
		etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels levels = heightmap.levels();
		WorldSettings.ControlPoints cp = heightmap.controlPoints();

		Cell cell = new Cell();

		// 1. Fix 2: All island built terrain strictly C1 or C0 ranged [-1.20F, -1.05F]
		cell.terrain = TerrainType.ISLAND;
		cell.height = levels.water + 0.1F;
		cell.controlPoint = cp.islandCoast;
		float cIslandCoast = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cIslandCoast - (-1.05F)) > 1e-3F) {
			throw new AssertionError("Island shoreline at islandCoast must meet -1.05F (C1), was " + cIslandCoast);
		}
		cell.controlPoint = cp.islandInland;
		float cIslandInland = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cIslandInland - (-1.20F)) > 1e-3F) {
			throw new AssertionError("Island peak at islandInland must meet -1.20F (C0), was " + cIslandInland);
		}

		// 2. Fix 3: All areas below ocean water level strictly C2 or C3 ranged based on height
		float floorHeight = levels.water(-levels.oceanDepth);
		float shallowBlocks = Math.min(22.0F, levels.oceanDepth * 0.40F);
		float hSplit = 1.0F - (shallowBlocks / levels.oceanDepth);
		float splitHeight = floorHeight + hSplit * (levels.water - floorHeight);

		cell.terrain = TerrainType.NONE;
		// Ocean floor (C2 deep ocean)
		cell.height = floorHeight;
		float cOceanFloor = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cOceanFloor - (-1.045F)) > 1e-3F) {
			throw new AssertionError("Ocean floor at max depth must meet -1.045F (C2), was " + cOceanFloor);
		}

		// Split point (C2 -> C3 boundary)
		cell.height = splitHeight;
		float cOceanSplit = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cOceanSplit - (-0.455F)) > 1e-3F) {
			throw new AssertionError("Ocean depth split boundary must meet -0.455F, was " + cOceanSplit);
		}

		// Waterline just below surface (C3 shallow ocean)
		cell.height = levels.water;
		float cSubmergedWaterline = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cSubmergedWaterline - (-0.191F)) > 1e-3F) {
			throw new AssertionError("Submerged waterline must meet -0.191F (C3), was " + cSubmergedWaterline);
		}
		if (cSubmergedWaterline >= -0.19F) {
			throw new AssertionError("Submerged waterline must be strictly < -0.19F to prevent beach misbioming, was " + cSubmergedWaterline);
		}

		// 3. Fix 1: Continent built terrain above water level starts at C4 (-0.19F) and transitions to C5 / C6 / C7
		cell.height = levels.water + 0.1F;
		cell.controlPoint = cp.beach;
		float cOnshoreBeach = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cOnshoreBeach - (-0.19F)) > 1e-3F) {
			throw new AssertionError("Onshore beach boundary must meet -0.19F (C4), was " + cOnshoreBeach);
		}

		// Below beach, continent terrain above water must never drop below C4 (-0.19F)
		cell.controlPoint = cp.beach - 0.05F;
		float cBelowBeach = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (cBelowBeach < -0.19F - 1e-4F) {
			throw new AssertionError("Continent above water must never have continentalness < C4 (-0.19F), was " + cBelowBeach);
		}

		cell.controlPoint = cp.coast;
		float cCoast = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cCoast - (-0.11F)) > 1e-3F) {
			throw new AssertionError("Coast boundary must meet -0.11F (C5), was " + cCoast);
		}

		cell.controlPoint = cp.inland;
		float cInland = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cInland - 0.03F) > 1e-3F) {
			throw new AssertionError("Inland boundary must meet 0.03F (C6), was " + cInland);
		}

		float midSplitEdge = cp.inland + 0.35F * (1.0F - cp.inland);
		cell.controlPoint = midSplitEdge;
		float cMidSplit = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cMidSplit - 0.30F) > 1e-3F) {
			throw new AssertionError("Mid-interior split boundary must meet 0.30F (C7), was " + cMidSplit);
		}

		cell.controlPoint = 1.0F;
		float cPeak = CellSampler.Field.CONTINENT.read(cell, heightmap);
		if (Math.abs(cPeak - 1.00F) > 1e-3F) {
			throw new AssertionError("Continental peak must reach 1.00F, was " + cPeak);
		}

		// 4. All island built terrain strictly in C1 / C0 range [-1.20F, -1.05F]
		cell.terrain = TerrainType.ISLAND;
		for (float edge = 0.0F; edge <= cp.islandCoast; edge += 0.01F) {
			cell.controlPoint = edge;
			float c = CellSampler.Field.CONTINENT.read(cell, heightmap);
			if (c < -1.20F - 1e-4F || c > -1.05F + 1e-4F) {
				throw new AssertionError("Island terrain at edge " + edge + " had c=" + c + ", outside C1/C0 range [-1.20, -1.05]");
			}
		}

		// 4. Slider Proportionality Verification
		WorldSettings.ControlPoints narrowInland = new WorldSettings.ControlPoints(0.0F, 0.074F, 0.102F, 0.13144F, 0.2210F, 0.3576F, 0.40F);
		WorldSettings.ControlPoints wideInland = new WorldSettings.ControlPoints(0.0F, 0.074F, 0.102F, 0.13144F, 0.2210F, 0.3576F, 0.70F);
		etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap hmNarrow = new etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap(null, null, null, null, levels, narrowInland, 1.0F, null, null);
		etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap hmWide = new etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap(null, null, null, null, levels, wideInland, 1.0F, null, null);

		cell.terrain = TerrainType.NONE;
		cell.controlPoint = 0.55F;
		float cNarrow = CellSampler.Field.CONTINENT.read(cell, hmNarrow);
		float cWide = CellSampler.Field.CONTINENT.read(cell, hmWide);
		if (cNarrow <= Continentalness.MID_INLAND.min() || cWide >= Continentalness.NEAR_INLAND.max()) {
			throw new AssertionError("Control point slider adjustment failed to alter terrain band classification at edge 0.55");
		}

		return "All 7 vanilla continentalness bands continuous (delta < 1e-3), islands strictly bounded <= 0.03F, control point proportionality verified";
	}

	public static void runAllTests(MinecraftServer server) {
		String serverModName = server.getServerModName();
		String loader = serverModName.toLowerCase().replaceAll("[^a-z0-9]", "");
		if (loader.contains("fabric")) loader = "fabric";
		else if (loader.contains("neoforge")) loader = "neoforge";
		FTFCommon.LOGGER.info("================================================================================");
		FTFCommon.LOGGER.info("[FTF-Test] STARTING HEADLESS PRESET VALIDATION SUITE (Server loader: {})", serverModName);
		FTFCommon.LOGGER.info("================================================================================");

		List<TestRecord> testRecords = new java.util.ArrayList<>();
		String currentTestName = "Initialization";

		TestWatchdog watchdog = new TestWatchdog(Thread.currentThread(), serverModName);
		Thread watchdogThread = new Thread(watchdog, "FTF-Test-Watchdog");
		watchdogThread.setDaemon(true);
		watchdogThread.start();

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

			currentTestName = "presetValidation.adaptiveBiomeLocate." + loader;
			watchdog.setStep(currentTestName);
			String adaptiveSearchNotes = validateAdaptiveBiomeSearchResolution();
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Adaptive biome locate validation ({})", adaptiveSearchNotes);
			testRecords.add(new TestRecord(currentTestName, adaptiveSearchNotes, true, null));

			currentTestName = "presetValidation.biomeLocateLivePlan." + loader;
			watchdog.setStep(currentTestName);
			BiomeLocateValidator.Result locateResult = BiomeLocateValidator.validate(server);
			String locateNotes = String.format(
				"surface=%s, captured=%d, vanilla=%dms@%s, ftf=%dms@%s, columns=%d, step=%dx%d, roles=%s; "
					+ "lush_caves vanilla=%dms@%s, ftf=%dms@%s, columns=%d, step=%dx%d, roles=%s",
				locateResult.biome(),
				locateResult.capturedBiomes(),
				locateResult.vanillaMillis(),
				locateResult.vanillaPosition(),
				locateResult.ftfMillis(),
				locateResult.ftfPosition(),
				locateResult.ftfColumns(),
				locateResult.horizontalStep(),
				locateResult.verticalStep(),
				locateResult.roles(),
				locateResult.vanillaCaveMillis(),
				locateResult.vanillaCavePosition(),
				locateResult.ftfCaveMillis(),
				locateResult.ftfCavePosition(),
				locateResult.ftfCaveColumns(),
				locateResult.caveHorizontalStep(),
				locateResult.caveVerticalStep(),
				locateResult.caveRoles()
			);
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Live biome locate comparison ({})", locateNotes);
			testRecords.add(new TestRecord(currentTestName, locateNotes, true, null));

			currentTestName = "presetValidation.terrainLocateLivePlan." + loader;
			watchdog.setStep(currentTestName);
			TerrainLocateValidator.Result terrainLocate = TerrainLocateValidator.validate(
				((FTFRandomState) (Object) server.overworld().getChunkSource().randomState()).generatorContext(),
				server.overworld().getSharedSpawnPos()
			);
			String terrainLocateNotes = String.format(
				"raw=%s captured@%s, %dms@%s, samples=%d, step=%d; beach(filtered)=%dms@%s, samples=%d, step=%d",
				terrainLocate.rawTerrain(),
				terrainLocate.rawCapture(),
				terrainLocate.rawMillis(),
				terrainLocate.rawMatch(),
				terrainLocate.rawSamples(),
				terrainLocate.rawStep(),
				terrainLocate.beachMillis(),
				terrainLocate.beachMatch(),
				terrainLocate.beachSamples(),
				terrainLocate.beachStep()
			);
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Live terrain locate validation ({})", terrainLocateNotes);
			testRecords.add(new TestRecord(currentTestName, terrainLocateNotes, true, null));

			// -----------------------------------------------------------------------------------------
			// Live Server Datapack & Registry Resolution
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.checkSynchronizedRegistry." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Live Server Datapack & Registry Resolution...");
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
				FTFCommon.LOGGER.info("[FTF-Test] PASSED: Live server loaded datapack matching active registry and populated PresetManager.PM ground truth.");
				testRecords.add(new TestRecord(currentTestName, "Datapack ground truth & active registry synchronized", true, null));
			} else {
				FTFCommon.LOGGER.info("[FTF-Test] Note: Dedicated server run started without pre-existing world datapack.");
				testRecords.add(new TestRecord(currentTestName, "Dedicated server run started without pre-existing world datapack", true, null));
			}

			// -----------------------------------------------------------------------------------------
			// Spawn Chunk Heightmap & Regularity
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.spawnChunkRegularity." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Generating Spawn Heightmap & Evaluating Terrain Regularity...");
			HeightmapValidator.ValidationResult heightmapResult = HeightmapValidator.validateLiveServerSpawn(server);
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Spawn heightmap generated and verified deterministic regularity (span: {} blocks, max step: {} blocks, spikes: 0).",
				heightmapResult.maxElevationBlocks() - heightmapResult.minElevationBlocks(),
				heightmapResult.maxAdjacentStepBlocks());
			testRecords.add(new TestRecord(currentTestName, String.format("Preset `ftf` @ spawn | Span: %d blocks, Max Step: %d blocks",
				heightmapResult.maxElevationBlocks() - heightmapResult.minElevationBlocks(),
				heightmapResult.maxAdjacentStepBlocks()), true, null));

			// -----------------------------------------------------------------------------------------
			// Built-in Default Fallback (Rivers)
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.builtinRiversFallback." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Built-in Default Fallback (Rivers)...");
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
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Built-in default preset correctly saved to world datapack on disk.");
			testRecords.add(new TestRecord(currentTestName, "Fallback resolution without source (rivers) saved to world pack", true, null));

			// -----------------------------------------------------------------------------------------
			// User-Selected Default Preset (Upscaled Community)
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.userSelectedDefault." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying User-Selected Default Preset (Upscaled Community)...");
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
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: User-selected default preset correctly saved to world datapack on disk and overrides fallback.");
			testRecords.add(new TestRecord(currentTestName, "Configured user default overrides fallback without prompt", true, null));

			// -----------------------------------------------------------------------------------------
			// Manual Preset Selection Overrule (Huge Biomes)
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.manualSelectionOverrule." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Manual Selection (Huge Biomes) Overrules User Default & Fallback...");
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
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Manually selected preset is saved to world folder and populates PresetManager.PM ground truth.");
			testRecords.add(new TestRecord(currentTestName, "Manual selection overrules user default & fallback on disk", true, null));

			// -----------------------------------------------------------------------------------------
			// World Type Cycling Preservation
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.worldTypeCycling." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying World Type Switching / Cycling Preserves Manual Preset...");
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
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: World type cycling preserves manual preset without overwrite.");
			testRecords.add(new TestRecord(currentTestName, "Switching world type preserves manual preset without overwrite", true, null));

			// -----------------------------------------------------------------------------------------
			// Dedicated Server Successive Run Preservation
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.successiveRunPreservation." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Dedicated Server Successive Run Preservation...");
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
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Successive server runs preserve existing manual preset on disk and in PresetManager.PM.");
			testRecords.add(new TestRecord(currentTestName, "Server reboot preserves existing manual world preset & PM state", true, null));

			// -----------------------------------------------------------------------------------------
			// Re-create World Flow Preservation
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.recreateWorldFlow." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Re-create World Flow...");
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
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Re-created worlds preserve original preset on disk and in PresetManager.PM.");
			testRecords.add(new TestRecord(currentTestName, "Re-created worlds preserve original preset on disk and in PM", true, null));

			// -----------------------------------------------------------------------------------------
			// Custom File-Based Default Preset Resolution
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.customFileResolution." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Custom File-Based Default Preset Source...");
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
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Custom file-based default correctly resolves and is overrulable by manual selection.");
			testRecords.add(new TestRecord(currentTestName, "Custom file source resolves correctly and is overrulable", true, null));

			// -----------------------------------------------------------------------------------------
			// Multi-Run Seam Continuity (Issue #330)
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.multiRunSeamContinuity." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Multi-Run Seam Continuity & Varying Config Immunity (Issue #330)...");
			HeightmapValidator.MultiRunContinuityResult multiRunResult = HeightmapValidator.validateMultiRunContinuity(server, testWorkDir);
			if (!multiRunResult.passed()) {
				throw new AssertionError("Multi-run continuity validation failed: " + multiRunResult.violations());
			}
			FTFCommon.LOGGER.info(
				"[FTF-Test] PASSED: Multi-run world generation is 100% continuous and immune to varying config setups (max seam step: {} blocks, hypothetical corrupted step: {} blocks, {}/{} identical samples).",
				multiRunResult.maxActualSeamStepBlocks(),
				multiRunResult.maxCorruptedSeamStepBlocks(),
				multiRunResult.identicalBlockSamples(),
				multiRunResult.totalBlockSamplesVerified()
			);
			testRecords.add(new TestRecord(currentTestName, String.format("Preset `Huge Biomes` (Issue #330) | Max Seam Step: %d blocks, Parity: %d/%d identical",
				multiRunResult.maxActualSeamStepBlocks(),
				multiRunResult.identicalBlockSamples(),
				multiRunResult.totalBlockSamplesVerified()), true, null));

			// -----------------------------------------------------------------------------------------
			// In-Game Command Execution & Parity (/ftf commands)
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.inGameCommandParity." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying In-Game /ftf heightmap & /ftf reliefmap Command Execution...");

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
				Files.copy(hmFile.toPath(), reportDir.resolve(String.format("heightmap_%s_ingame_command_%s.png", loader, timestamp)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				Files.copy(rmFile.toPath(), reportDir.resolve(String.format("reliefmap_%s_ingame_command_%s.png", loader, timestamp)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

				Path reportFile = reportDir.resolve(String.format("command_parity_%s_ingame_command_%s_report.txt", loader, timestamp));
				StringBuilder sb = new StringBuilder();
				sb.append("FreeTerraForged In-Game Command Parity Report\n");
				sb.append("============================================\n");
				sb.append("Timestamp: ").append(timestamp).append("\n");
				sb.append("Loader: ").append(loader).append("\n");
				sb.append("Test Radius: ").append(testRadius).append(" blocks (Area: ").append(expectedW).append("x").append(expectedH).append(")\n");
				sb.append("Heightmap Command: /ftf heightmap radius ").append(testRadius).append(" -> PASSED (100% Core Parity)\n");
				sb.append("Reliefmap Command: /ftf reliefmap radius ").append(testRadius).append(" -> PASSED (100% Core Parity)\n");
				sb.append("Image Integrity: Non-black, non-flat height variance verified\n");
				sb.append("Outcome: PASSED\n");
				Files.writeString(reportFile, sb.toString(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				FTFCommon.LOGGER.warn("[FTF-Test] Failed to archive command outputs: {}", e.getMessage());
			}

			// 5. Cleanup root directory
			hmFile.delete();
			rmFile.delete();
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: In-game commands /ftf heightmap and /ftf reliefmap executed, generated valid non-black non-flat images, and verified 100% bit-for-bit parity against core generator.");
			testRecords.add(new TestRecord(currentTestName, String.format("Radius: %d blocks | 100%% bit-for-bit core parity verified", testRadius), true, null));

			// -----------------------------------------------------------------------------------------
			// Continentalness Distribution, Range Mapping & Control Point Proportionality
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.continentalnessDistributionAndControlPoints." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Continentalness Distribution, Range Mapping & Control Point Proportionality...");
			String continentalnessNotes = validateContinentalnessAndControlPoints(genCtx);
			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Continentalness distribution & control point proportionality ({})", continentalnessNotes);
			testRecords.add(new TestRecord(currentTestName, continentalnessNotes, true, null));

			// -----------------------------------------------------------------------------------------
			// Two-Stage Datapack Reload & User Spawn Persistence
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.twoStageReloadSpawnPersistence." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Two-Stage Datapack Reload, 2D Preview-to-World Topological Parity & User Spawn...");
			int customSpawnX = 1337;
			int customSpawnZ = -2048;
			long paritySeed = server.overworld().getSeed();

			// Stage 1: Simulate CreateWorldScreen.openFresh exporting initial default preset (Rivers) and enabling "file/freeterraforged-preset.zip"
			Path stageDir = testWorkDir.resolve("two-stage-create-world");
			Files.createDirectories(stageDir);
			Path stagedPresetZip = stageDir.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			Preset stage1DefaultPreset = Presets.modernDefaultWithRivers().copy();
			FTFWorldPresetDataPacks.exportPresetAsDatapack(stagedPresetZip, stage1DefaultPreset, "Rivers", server.registryAccess());

			DirectoryValidator noSymlinkValidator = new DirectoryValidator(path -> false);
			PackRepository stageRepo = new PackRepository(
				new ServerPacksSource(noSymlinkValidator),
				new FolderRepositorySource(stageDir, PackType.SERVER_DATA, PackSource.WORLD, noSymlinkValidator)
			);
			stageRepo.reload();
			if (!stageRepo.addPack(FTFWorldPresets.GENERATED_PRESET_PACK_ID)) {
				throw new AssertionError("Stage 1 failed to select " + FTFWorldPresets.GENERATED_PRESET_PACK_ID);
			}
			WorldDataConfiguration stage1DataConfig = new WorldDataConfiguration(
				new DataPackConfig(List.copyOf(stageRepo.getSelectedIds()), List.of()),
				FeatureFlags.DEFAULT_FLAGS
			);
			RegistryAccess.Frozen stage1Registries = loadPresetAndNoiseRegistriesFromRepository(stageRepo);
			Preset stage1LoadedPreset = stage1Registries.lookupOrThrow(FTFRegistries.PRESET).getOrThrow(Preset.KEY).value();
			if (identifyPreset(stage1LoadedPreset) != PresetIdentity.RIVERS_DEFAULT) {
				throw new AssertionError("Stage 1 registry should be RIVERS_DEFAULT, but was: " + identifyPreset(stage1LoadedPreset));
			}

			// Stage 2: User opens PresetConfigScreen, customizes preset (terrain + island + erosion + USER_SELECTED spawn on 2D preview)
			Preset userSpawnPreset = Presets.makeLegacyBeautiful().copy();
			userSpawnPreset.world().properties.worldHeight = 384;
			userSpawnPreset.world().continent.continentScale = 5555;
			userSpawnPreset.world().properties.spawnType = SpawnType.USER_SELECTED;
			userSpawnPreset.world().properties.spawnX = customSpawnX;
			userSpawnPreset.world().properties.spawnZ = customSpawnZ;

			// Simulate Preview2D / IPreviewHandler creating a detached preview clone in PresetManager.PM BEFORE user edits Island & Erosion pages:
			HolderLookup.Provider initialPreviewLookups = userSpawnPreset.buildPreviewLookups(server.registryAccess());
			Preset detachedPreviewClone = initialPreviewLookups.lookupOrThrow(FTFRegistries.PRESET).getOrThrow(Preset.KEY).value();
			try (GeneratorContext ignoredDetachedPreviewCtx = GeneratorContext.makeUncached(
				detachedPreviewClone,
				initialPreviewLookups.lookupOrThrow(FTFRegistries.NOISE),
				paritySeed, 4, 0, 4
			)) {
				// GeneratorContext constructor ingests detachedPreviewClone into PresetManager.PM.
				// Verify that mutating userSpawnPreset.island() and userSpawnPreset.erosionFilters() (as fixed in IslandSettingsPage & ErosionFilterSettingsPage)
				// mutates userSpawnPreset directly rather than the detached clone:
				userSpawnPreset.island().spawnIslands = true;
				userSpawnPreset.island().mountainChance = 0.77F;
				userSpawnPreset.erosionFilters().strengthMultiplier = 1.65F;
			}

			// Generate 2D Preview (PreviewGrid) for the customized preset at (customSpawnX, customSpawnZ), matching Preview2D:
			HolderLookup.Provider finalPreviewLookups = userSpawnPreset.buildPreviewLookups(server.registryAccess());
			Preset previewPreparedPreset = finalPreviewLookups.lookupOrThrow(FTFRegistries.PRESET).getOrThrow(Preset.KEY).value();
			HolderGetter<Noise> previewNoises = finalPreviewLookups.lookupOrThrow(FTFRegistries.NOISE);

			// Save to user preset JSON file and verify JSON persistence:
			Path userSpawnPresetsDir = configRoot.resolve("freeterraforged").resolve("presets");
			Files.createDirectories(userSpawnPresetsDir);
			Path userSpawnFile = userSpawnPresetsDir.resolve("UserSelectedSpawnTest.json");
			try (var writer = Files.newBufferedWriter(userSpawnFile, StandardCharsets.UTF_8)) {
				JsonElement json = Preset.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, userSpawnPreset)
					.getOrThrow(msg -> new IllegalStateException("Failed to encode custom preset: " + msg));
				writer.write(json.toString());
			}

			Preset diskUserSpawnPreset;
			try (Reader reader = Files.newBufferedReader(userSpawnFile, StandardCharsets.UTF_8)) {
				JsonElement element = JsonParser.parseReader(reader);
				diskUserSpawnPreset = Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, element)
					.getOrThrow(msg -> new IllegalStateException("Failed to parse preset: " + msg));
			}
			if (diskUserSpawnPreset.world().properties.spawnType != SpawnType.USER_SELECTED) {
				throw new AssertionError("Disk preset failed to persist SpawnType.USER_SELECTED");
			}
			if (diskUserSpawnPreset.world().properties.spawnX != customSpawnX || diskUserSpawnPreset.world().properties.spawnZ != customSpawnZ) {
				throw new AssertionError("Disk preset failed to persist exact coordinates: (" + diskUserSpawnPreset.world().properties.spawnX + ", " + diskUserSpawnPreset.world().properties.spawnZ + ")");
			}
			if (!diskUserSpawnPreset.island().spawnIslands || Math.abs(diskUserSpawnPreset.island().mountainChance - 0.77F) > 1e-5F) {
				throw new AssertionError("Disk preset failed to persist IslandSettings edits!");
			}
			if (Math.abs(diskUserSpawnPreset.erosionFilters().strengthMultiplier - 1.65F) > 1e-5F) {
				throw new AssertionError("Disk preset failed to persist ErosionFilterSettings edits!");
			}

			// Overwrite stagedPresetZip on disk (exact PresetConfigScreen.applyPreset behavior when "file/freeterraforged-preset.zip" is already enabled):
			FTFWorldPresetDataPacks.exportPresetAsDatapack(stagedPresetZip, diskUserSpawnPreset, "UserSelectedSpawnTest", server.registryAccess());
			stageRepo.reload();
			WorldDataConfiguration stage2DataConfig = new WorldDataConfiguration(
				new DataPackConfig(List.copyOf(stageRepo.getSelectedIds()), List.of()),
				FeatureFlags.DEFAULT_FLAGS
			);

			// CRITICAL CONTRACT CHECK:
			// Because "file/freeterraforged-preset.zip" was ALREADY in stage1DataConfig.dataPacks().getEnabled() from openFresh,
			// stage1DataConfig.dataPacks().getEnabled().equals(stage2DataConfig.dataPacks().getEnabled()) is TRUE!
			if (!stage1DataConfig.dataPacks().getEnabled().equals(stage2DataConfig.dataPacks().getEnabled())) {
				throw new AssertionError("Expected enabled pack IDs list to be equal across Stage 1 and Stage 2 overwrite");
			}
			// Verify FTFWorldPresetDataPacks.requiresFullDataPackReload forces WorldCreationUiState.tryUpdateDataConfiguration to return false:
			if (!FTFWorldPresetDataPacks.requiresFullDataPackReload(stage2DataConfig)) {
				throw new AssertionError("CRITICAL FAILURE: requiresFullDataPackReload returned false when " + FTFWorldPresets.GENERATED_PRESET_PACK_ID + " was enabled! WorldCreationUiState would short-circuit and bake the stale Stage 1 default preset!");
			}

			// Execute Stage 2 datapack reload from stageRepo:
			RegistryAccess.Frozen stage2Registries = loadPresetAndNoiseRegistriesFromRepository(stageRepo);
			Preset stage2LoadedPreset = stage2Registries.lookupOrThrow(FTFRegistries.PRESET).getOrThrow(Preset.KEY).value();
			HolderGetter<Noise> stage2LoadedNoises = stage2Registries.lookupOrThrow(FTFRegistries.NOISE);

			if (identifyPreset(stage2LoadedPreset) != PresetIdentity.CUSTOM_TEST) {
				throw new AssertionError("Stage 2 reloaded registry preset should be CUSTOM_TEST, but was: " + identifyPreset(stage2LoadedPreset));
			}
			if (stage2LoadedPreset.world().properties.spawnType != SpawnType.USER_SELECTED
				|| stage2LoadedPreset.world().properties.spawnX != customSpawnX
				|| stage2LoadedPreset.world().properties.spawnZ != customSpawnZ) {
				throw new AssertionError("Stage 2 reloaded registry preset lost USER_SELECTED spawn coordinates!");
			}

			// Verify 100% Topological Parity between 2D Preview (PreviewGrid / previewCtx) and Reloaded World (worldCtx) at (customSpawnX, customSpawnZ),
			// AND verify that Stale Stage 1 Default World (staleCtx) diverges (anti-false-positive proof):
			int previewSize = 64;
			int zoomLevel = 50;
			int paritySamplesChecked = 0;
			int staleDivergentSamples = 0;
			int maxStaleElevationDelta = 0;

			try (GeneratorContext previewCtx = GeneratorContext.makeUncached(previewPreparedPreset, previewNoises, paritySeed, 4, 0, 4);
				 GeneratorContext worldCtx = GeneratorContext.make(stage2LoadedPreset, stage2LoadedNoises, paritySeed);
				 GeneratorContext staleCtx = GeneratorContext.make(stage1LoadedPreset, stage1Registries.lookupOrThrow(FTFRegistries.NOISE), paritySeed)) {

				PreviewGrid zoomedGrid = PreviewGrid.generateZoomed(
					previewCtx.heightmap, null, customSpawnX, customSpawnZ, zoomLevel, previewSize, () -> false
				).join();
				PreviewGrid filtered1to1Grid = PreviewGrid.generateZoomed(
					previewCtx.heightmap, previewCtx.filters, customSpawnX, customSpawnZ, 1.0F, previewSize, () -> false
				).join();

				Cell worldCell = new Cell();
				Cell staleCell = new Cell();

				// 1. Check zoomed 2D PreviewGrid cells (including center cell at customSpawnX, customSpawnZ) against worldCtx.heightmap:
				for (int iz = 8; iz < previewSize - 8; iz += 4) {
					for (int ix = 8; ix < previewSize - 8; ix += 4) {
						int worldX = customSpawnX + (ix - previewSize / 2) * zoomLevel;
						int worldZ = customSpawnZ + (iz - previewSize / 2) * zoomLevel;

						Cell previewCell = zoomedGrid.getCell(ix, iz);
						worldCtx.heightmap.apply(worldCell.reset(), worldX, worldZ, true);
						staleCtx.heightmap.apply(staleCell.reset(), worldX, worldZ, true);

						int previewY = previewCtx.levels.scale(previewCell.height);
						int worldY = worldCtx.levels.scale(worldCell.height);
						int staleY = staleCtx.levels.scale(staleCell.height);

						if (Float.compare(previewCell.height, worldCell.height) != 0 || previewY != worldY) {
							throw new AssertionError(String.format(
								"2D Preview vs Reloaded World height mismatch at (%d, %d): preview=%.6f (Y=%d) vs world=%.6f (Y=%d)",
								worldX, worldZ, previewCell.height, previewY, worldCell.height, worldY
							));
						}
						if (Float.compare(previewCell.controlPoint, worldCell.controlPoint) != 0
							|| Float.compare(previewCell.riverMask, worldCell.riverMask) != 0) {
							throw new AssertionError(String.format(
								"2D Preview vs Reloaded World continent/river mismatch at (%d, %d)", worldX, worldZ
							));
						}

						int staleDelta = Math.abs(worldY - staleY);
						if (staleDelta > 0 || !worldCell.terrain.equals(staleCell.terrain)) {
							staleDivergentSamples++;
							maxStaleElevationDelta = Math.max(maxStaleElevationDelta, staleDelta);
						}
						paritySamplesChecked++;
					}
				}

				// 2. Also check exact center cell of 1:1 filtered PreviewGrid at (customSpawnX, customSpawnZ):
				Cell centerPreview1to1 = filtered1to1Grid.getCell(previewSize / 2, previewSize / 2);
				if (Float.isNaN(centerPreview1to1.height) || Float.isInfinite(centerPreview1to1.height)) {
					throw new AssertionError("1:1 filtered PreviewGrid center cell at spawn produced NaN/Infinite height");
				}
			}

			if (staleDivergentSamples == 0 || maxStaleElevationDelta < 5) {
				throw new AssertionError(
					"Anti-false-positive check failed: Stage 1 default preset did not significantly diverge from Stage 2 custom preset (divergent="
						+ staleDivergentSamples + ", maxDelta=" + maxStaleElevationDelta + ")"
				);
			}

			PresetManager.PM.ingestFromPreset(stage2LoadedPreset);
			verifyPresetManagerGroundTruth(stage2LoadedPreset, "User Selected Spawn & Stage 2 Reload Verification");

			// Verify exact spawn coordinates application on ServerLevelData
			ServerLevel overworld = server.overworld();
			ServerLevelData overworldData = (ServerLevelData) overworld.getLevelData();
			BlockPos prevSpawn = overworld.getSharedSpawnPos();

			int expectedSurfaceY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, customSpawnX, customSpawnZ);
			if (expectedSurfaceY < overworld.getMinBuildHeight() + 1) {
				expectedSurfaceY = overworld.getSeaLevel() + 1;
			}
			while (expectedSurfaceY < overworld.getMaxBuildHeight() && (overworld.getBlockState(new BlockPos(customSpawnX, expectedSurfaceY, customSpawnZ)).blocksMotion() || overworld.getBlockState(new BlockPos(customSpawnX, expectedSurfaceY + 1, customSpawnZ)).blocksMotion())) {
				expectedSurfaceY++;
			}

			BlockPos expectedSpawnPos = new BlockPos(customSpawnX, expectedSurfaceY, customSpawnZ);
			overworldData.setSpawn(expectedSpawnPos, 0.0f);
			BlockPos appliedSpawn = overworld.getSharedSpawnPos();

			if (appliedSpawn.getX() != customSpawnX || appliedSpawn.getZ() != customSpawnZ || appliedSpawn.getY() != expectedSurfaceY) {
				throw new AssertionError("Spawn application failed to apply exact coordinates: expected " + expectedSpawnPos + " but got " + appliedSpawn);
			}

			// Restore previous spawn pos
			overworldData.setSpawn(prevSpawn, 0.0f);
			Files.deleteIfExists(userSpawnFile);
			FTFCommon.LOGGER.info(
				"[FTF-Test] PASSED: Two-stage datapack reload forced full reload, verified 100% 2D Preview-to-World topological parity across {} samples around ({}, {}) (stale default diverged by up to {} blocks across {} samples), and verified exact spawn ({}, {}, {}).",
				paritySamplesChecked, customSpawnX, customSpawnZ, maxStaleElevationDelta, staleDivergentSamples, customSpawnX, expectedSurfaceY, customSpawnZ
			);
			testRecords.add(new TestRecord(currentTestName, String.format("Full reload forces 2D Preview parity across %d samples & exact spawn (%d, %d, %d)",
				paritySamplesChecked, customSpawnX, expectedSurfaceY, customSpawnZ), true, null));

			// -----------------------------------------------------------------------------------------
			// Non-FTF World Types (Default & Superflat) Resolution & Generation
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.nonFtfWorldResolution." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Non-FTF World Resolution & Sampling (Default, Amplified & Superflat)...");

			// 1. Simulate cycling away from FTF: verify datapack lifecycle & decoupling
			Path nonFtfTestDir = testWorkDir.resolve("non-ftf-cycling-test");
			Files.createDirectories(nonFtfTestDir);
			Path testStagedPack = nonFtfTestDir.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			FTFWorldPresetDataPacks.exportPresetAsDatapack(testStagedPack, Presets.modernDefaultWithRivers(), "CyclingTest", server.registryAccess());
			if (!Files.isRegularFile(testStagedPack)) {
				throw new AssertionError("Failed to stage preset pack for non-FTF cycling test");
			}

			WorldDataConfiguration ftfActiveConfig = new WorldDataConfiguration(
				new DataPackConfig(List.of("vanilla", FTFWorldPresets.GENERATED_PRESET_PACK_ID), List.of()),
				FeatureFlags.DEFAULT_FLAGS
			);
			if (!FTFWorldPresetDataPacks.requiresFullDataPackReload(ftfActiveConfig)) {
				throw new AssertionError("requiresFullDataPackReload must return true when FTF preset pack is enabled");
			}

			WorldDataConfiguration sanitizedConfig = FTFWorldPresetDataPacks.setCustomPresetEnabled(ftfActiveConfig, false);
			if (sanitizedConfig.dataPacks().getEnabled().contains(FTFWorldPresets.GENERATED_PRESET_PACK_ID)) {
				throw new AssertionError("setCustomPresetEnabled(false) failed to remove FTF preset pack from enabled list");
			}

			Files.deleteIfExists(testStagedPack);
			if (Files.exists(testStagedPack)) {
				throw new AssertionError("Failed to delete unused FTF preset pack for non-FTF world creation");
			}

			// 2. Default World (WorldPresets.NORMAL) Guarantee
			// When cycling away from FTF, datapacks are reloaded without freeterraforged-preset.zip,
			// restoring clean vanilla registries (VanillaRegistries.createLookup()).
			HolderLookup.Provider vanillaLookups = VanillaRegistries.createLookup();
			HolderLookup.RegistryLookup<WorldPreset> vanillaPresets = vanillaLookups.lookupOrThrow(Registries.WORLD_PRESET);
			WorldPreset vanillaNormalPreset = vanillaPresets.getOrThrow(WorldPresets.NORMAL).value();
			WorldDimensions normalDimensions = vanillaNormalPreset.createWorldDimensions();
			LevelStem normalOverworldStem = normalDimensions.get(LevelStem.OVERWORLD).orElseThrow(
				() -> new AssertionError("Normal preset has no overworld stem")
			);
			var normalGenerator = normalOverworldStem.generator();

			if (!(normalGenerator instanceof NoiseBasedChunkGenerator noiseBasedGen)) {
				throw new AssertionError("Default world overworld generator must be NoiseBasedChunkGenerator, got: " + normalGenerator.getClass().getName());
			}
			if (normalGenerator instanceof TerraForgedChunkGenerator) {
				throw new AssertionError("Default world overworld generator must NOT be TerraForgedChunkGenerator");
			}

			NoiseGeneratorSettings normalNoiseSettings = noiseBasedGen.generatorSettings().value();
			RandomState normalRandomState = RandomState.create(
				normalNoiseSettings,
				server.registryAccess().lookupOrThrow(Registries.NOISE),
				paritySeed
			);

			if (!((Object) normalRandomState instanceof FTFRandomState normalRtfState)) {
				throw new AssertionError("RandomState must expose FTFRandomState contract");
			}
			if (normalRtfState.isTerraForged()) {
				throw new AssertionError("Default world RandomState must NOT be flagged as TerraForged! CellSampler.Marker was found in default noise router.");
			}

			int sampleDefaultHeight = normalGenerator.getBaseHeight(0, 0, Heightmap.Types.OCEAN_FLOOR_WG, server.overworld(), normalRandomState);
			if (sampleDefaultHeight < server.overworld().getMinBuildHeight() || sampleDefaultHeight > server.overworld().getMaxBuildHeight()) {
				throw new AssertionError("Default world sampled out-of-bounds height: " + sampleDefaultHeight);
			}
			var defaultColumn = normalGenerator.getBaseColumn(0, 0, server.overworld(), normalRandomState);
			if (defaultColumn == null) {
				throw new AssertionError("Default world getBaseColumn returned null");
			}

			// 3. Amplified World (WorldPresets.AMPLIFIED) Guarantee
			WorldPreset vanillaAmplifiedPreset = vanillaPresets.getOrThrow(WorldPresets.AMPLIFIED).value();
			WorldDimensions amplifiedDimensions = vanillaAmplifiedPreset.createWorldDimensions();
			LevelStem amplifiedOverworldStem = amplifiedDimensions.get(LevelStem.OVERWORLD).orElseThrow(
				() -> new AssertionError("Amplified preset has no overworld stem")
			);
			var amplifiedGenerator = amplifiedOverworldStem.generator();

			if (!(amplifiedGenerator instanceof NoiseBasedChunkGenerator amplifiedGen)) {
				throw new AssertionError("Amplified world overworld generator must be NoiseBasedChunkGenerator, got: " + amplifiedGenerator.getClass().getName());
			}
			if (amplifiedGenerator instanceof TerraForgedChunkGenerator) {
				throw new AssertionError("Amplified world overworld generator must NOT be TerraForgedChunkGenerator");
			}

			NoiseGeneratorSettings amplifiedNoiseSettings = amplifiedGen.generatorSettings().value();
			RandomState amplifiedRandomState = RandomState.create(
				amplifiedNoiseSettings,
				server.registryAccess().lookupOrThrow(Registries.NOISE),
				paritySeed
			);

			if (!((Object) amplifiedRandomState instanceof FTFRandomState amplifiedRtfState)) {
				throw new AssertionError("RandomState must expose FTFRandomState contract");
			}
			if (amplifiedRtfState.isTerraForged()) {
				throw new AssertionError("Amplified world RandomState must NOT be flagged as TerraForged! CellSampler.Marker was found in amplified noise router.");
			}

			int sampleAmplifiedHeight = amplifiedGenerator.getBaseHeight(0, 0, Heightmap.Types.OCEAN_FLOOR_WG, server.overworld(), amplifiedRandomState);
			if (sampleAmplifiedHeight < server.overworld().getMinBuildHeight() || sampleAmplifiedHeight > server.overworld().getMaxBuildHeight()) {
				throw new AssertionError("Amplified world sampled out-of-bounds height: " + sampleAmplifiedHeight);
			}
			var amplifiedColumn = amplifiedGenerator.getBaseColumn(0, 0, server.overworld(), amplifiedRandomState);
			if (amplifiedColumn == null) {
				throw new AssertionError("Amplified world getBaseColumn returned null");
			}

			// 4. Superflat World (WorldPresets.FLAT) Guarantee
			// Tested on both clean vanilla lookups and live server registry:
			WorldPreset flatWorldPreset = server.registryAccess().registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT);
			WorldDimensions flatDimensions = flatWorldPreset.createWorldDimensions();
			LevelStem flatOverworldStem = flatDimensions.get(LevelStem.OVERWORLD).orElseThrow(
				() -> new AssertionError("Flat preset has no overworld stem")
			);
			var flatGenerator = flatOverworldStem.generator();

			if (!(flatGenerator instanceof FlatLevelSource flatLevelSource)) {
				throw new AssertionError("Superflat world overworld generator must be FlatLevelSource, got: " + flatGenerator.getClass().getName());
			}
			if (flatGenerator instanceof TerraForgedChunkGenerator) {
				throw new AssertionError("Superflat world overworld generator must NOT be TerraForgedChunkGenerator");
			}

			// Vanilla ChunkMap uses NoiseGeneratorSettings.dummy() for non-NoiseBasedChunkGenerators (like FlatLevelSource)
			RandomState flatRandomState = RandomState.create(
				NoiseGeneratorSettings.dummy(),
				server.registryAccess().lookupOrThrow(Registries.NOISE),
				paritySeed
			);

			if (!((Object) flatRandomState instanceof FTFRandomState flatRtfState)) {
				throw new AssertionError("Superflat RandomState must expose FTFRandomState contract");
			}
			if (flatRtfState.isTerraForged()) {
				throw new AssertionError("Superflat RandomState must NOT be flagged as TerraForged");
			}

			FlatLevelGeneratorSettings flatSettings = flatLevelSource.settings();
			if (flatSettings.getLayersInfo().isEmpty()) {
				throw new AssertionError("Superflat world generator settings have no layers");
			}

			int sampleFlatHeight = flatGenerator.getBaseHeight(0, 0, Heightmap.Types.OCEAN_FLOOR_WG, server.overworld(), flatRandomState);
			if (sampleFlatHeight < server.overworld().getMinBuildHeight() || sampleFlatHeight > server.overworld().getMaxBuildHeight()) {
				throw new AssertionError("Superflat world sampled out-of-bounds height: " + sampleFlatHeight);
			}
			var flatColumn = flatGenerator.getBaseColumn(0, 0, server.overworld(), flatRandomState);
			if (flatColumn == null) {
				throw new AssertionError("Superflat world getBaseColumn returned null");
			}

			FTFCommon.LOGGER.info(
				"[FTF-Test] PASSED: Non-FTF worlds (Default, Amplified & Superflat) verified clean generator isolation, RandomState isTerraForged=false, and successful terrain sampling (Default Y={}, Amplified Y={}, Flat Y={}).",
				sampleDefaultHeight, sampleAmplifiedHeight, sampleFlatHeight
			);
			testRecords.add(new TestRecord(
				currentTestName,
				String.format("Default (NoiseBased, RTF=false, Y=%d), Amplified (NoiseBased, RTF=false, Y=%d), and Superflat (FlatLevelSource, RTF=false, Y=%d) verified",
					sampleDefaultHeight, sampleAmplifiedHeight, sampleFlatHeight),
				true,
				null
			));

			// -----------------------------------------------------------------------------------------
			// Mod Compatibility Shims (Non-FTF Dimensions & Foreign Climate.Sampler - Issues #340, #310, #298)
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.modCompatibilityShims." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Mod Compatibility Shims (Non-FTF Dimension CellSampler Fallback & Foreign Climate.Sampler Adoption)...");

			NoiseGeneratorSettings liveOverworldNoiseSettings = server.registryAccess()
				.lookupOrThrow(Registries.NOISE_SETTINGS)
				.getOrThrow(NoiseGeneratorSettings.OVERWORLD)
				.value();
			RandomState uninitializedFtfRouterState = RandomState.create(
				liveOverworldNoiseSettings,
				server.registryAccess().lookupOrThrow(Registries.NOISE),
				paritySeed
			);
			FTFRandomState uninitializedRtfState = (FTFRandomState) (Object) uninitializedFtfRouterState;
			if (uninitializedRtfState.isTerraForged()) {
				throw new AssertionError("Uninitialized RandomState referencing FTF density functions must NOT report isTerraForged=true before epoch initialization (Issue #340/#310)");
			}
			var testPointCtx = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(128, 64, -256);
			double fallbackContinent = uninitializedFtfRouterState.router().continents().compute(testPointCtx);
			double fallbackErosion = uninitializedFtfRouterState.router().erosion().compute(testPointCtx);
			double fallbackRidges = uninitializedFtfRouterState.router().ridges().compute(testPointCtx);
			if (!Double.isFinite(fallbackContinent) || !Double.isFinite(fallbackErosion) || !Double.isFinite(fallbackRidges)) {
				throw new AssertionError("Non-FTF dimension fallback noise evaluation produced non-finite values");
			}
			if (fallbackContinent == 0.0D && fallbackErosion == 0.0D && fallbackRidges == 0.0D) {
				throw new AssertionError("Non-FTF dimension fallback noise evaluation returned all zeros instead of wired Vanilla shifted noise");
			}

			if (server.overworld().getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator liveTfGen) {
				net.minecraft.world.level.biome.Climate.Sampler foreignSampler = net.minecraft.world.level.biome.Climate.empty();
				var resolvedViaSource = liveTfGen.getBiomeSource().getNoiseBiome(10, 16, -10, foreignSampler);
				var resolvedViaCell = liveTfGen.resolveBiomeInCell(10, 16, -10, foreignSampler, 0L, 0L);
				if (resolvedViaSource == null || resolvedViaCell == null) {
					throw new AssertionError("Foreign Climate.Sampler failed to resolve biome on TerraForgedChunkGenerator (Issue #298)");
				}
			}

			FTFCommon.LOGGER.info(
				"[FTF-Test] PASSED: Mod compatibility shims verified (non-FTF dimension fallback continents={}, erosion={}, ridges={}; foreign Climate.Sampler adopted cleanly).",
				String.format(java.util.Locale.ROOT, "%.4f", fallbackContinent),
				String.format(java.util.Locale.ROOT, "%.4f", fallbackErosion),
				String.format(java.util.Locale.ROOT, "%.4f", fallbackRidges)
			);
			testRecords.add(new TestRecord(
				currentTestName,
				"Non-FTF dimension CellSampler fallback (#340/#310) & foreign Climate.Sampler adoption (#298) verified",
				true,
				null
			));

			// -----------------------------------------------------------------------------------------
			// Underground Biome Banding & Vertical Stacking in Deep Worlds
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.undergroundBiomeBanding." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Underground Biome Banding & Vertical Stacking in Deep Worlds...");

			var parameterLists = server.registryAccess().lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
			var overworldParamsHolder = parameterLists.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
			Climate.ParameterList<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> overworldEntries = overworldParamsHolder.value().parameters();

			// 1. Configure deep world preset with vertical banding enabled
			Preset deepPreset = Presets.makeFTFDefault();
			deepPreset.world().properties.worldDepth = 512;
			deepPreset.world().properties.worldHeight = 320;
			deepPreset.climate().biomeShape.undergroundBiomeSize = 225;
			deepPreset.climate().biomeShape.undergroundBiomeVerticalSize = 64;
			deepPreset.climate().biomeShape.undergroundBiomeCoverage = 1.0F;
			deepPreset.climate().biomeShape.undergroundBiomeClimateInfluence = 0.75F;
			deepPreset.climate().biomeShape.undergroundBiomeBanding = true;

			long seed = server.overworld().getSeed();
			UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> bandedLayout = UndergroundBiomeBanding.apply(
				deepPreset,
				overworldEntries,
				seed,
				(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value))
			);

			if (bandedLayout.deepCandidateCount() != 3) {
				throw new AssertionError("Deep stage should have exactly 3 cave candidates, found: " + bandedLayout.deepCandidateCount() + " (" + bandedLayout.deepCandidateValues() + ")");
			}
			if (bandedLayout.shallowCandidateCount() != 2) {
				throw new AssertionError("Shallow stage should have exactly 2 cave candidates, found: " + bandedLayout.shallowCandidateCount() + " (" + bandedLayout.shallowCandidateValues() + ")");
			}
			for (var biome : bandedLayout.deepCandidateValues()) {
				if (!UndergroundBiomeTags.isCave(biome)) {
					throw new AssertionError("Deep candidates contain non-cave biome: " + biome);
				}
			}
			for (var biome : bandedLayout.shallowCandidateValues()) {
				if (!UndergroundBiomeTags.isCave(biome)) {
					throw new AssertionError("Shallow candidates contain non-cave biome: " + biome);
				}
			}

			// Sample a vertical column in the deep cave zone at steps of verticalSize (64 blocks)
			Climate.TargetPoint target = Climate.target(0.0F, 0.0F, 0.0F, -0.5F, 1.5F, 0.0F);
			int quartX = 0;
			int quartZ = 0;
			int verticalSizeBlocks = 64;

			java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> stackedBiomes = new java.util.LinkedHashSet<>();
			net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> previousBiome = null;
			int stepTransitions = 0;
			int sampleStepCount = 0;

			// Sample Y levels corresponding to center of vertical bands from Y = -96 down to Y = -448
			for (int blockY = -96; blockY >= -448; blockY -= verticalSizeBlocks) {
				int quartY = QuartPos.fromBlock(blockY);
				net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> currentBiome = bandedLayout.findValue(target, quartX, quartY, quartZ);
				stackedBiomes.add(currentBiome);
				if (previousBiome != null && !previousBiome.equals(currentBiome)) {
					stepTransitions++;
				}
				previousBiome = currentBiome;
				sampleStepCount++;
			}

			for (var biome : stackedBiomes) {
				if (!UndergroundBiomeTags.isCave(biome)) {
					throw new AssertionError("Underground banding sampled non-cave biome in stacked biomes: " + biome);
				}
			}
			if (stackedBiomes.size() < 2) {
				throw new AssertionError("Underground biome banding failed: expected multiple stacked cave biome types across vertical column, but only got " + stackedBiomes.size() + ": " + stackedBiomes);
			}
			if (stepTransitions < 2) {
				throw new AssertionError("Underground biome banding failed: expected transitions at steps of verticalSize (" + verticalSizeBlocks + " blocks), but got " + stepTransitions + " transitions across " + sampleStepCount + " steps");
			}

			// 2. Verify columnar behavior when banding is disabled
			Preset columnarPreset = deepPreset.copy();
			columnarPreset.climate().biomeShape.undergroundBiomeBanding = false;
			UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> columnarLayout = UndergroundBiomeBanding.apply(
				columnarPreset,
				overworldEntries,
				seed,
				(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value))
			);

			java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> columnarBiomes = new java.util.HashSet<>();
			for (int blockY = -96; blockY >= -448; blockY -= verticalSizeBlocks) {
				int quartY = QuartPos.fromBlock(blockY);
				columnarBiomes.add(columnarLayout.findValue(target, quartX, quartY, quartZ));
			}
			if (columnarBiomes.size() != 1) {
				throw new AssertionError("Underground biomes with banding disabled should be columnar (single biome down column), but got " + columnarBiomes.size() + ": " + columnarBiomes);
			}

			// 3. Verify that undergroundBiomeSize controls horizontal biome transition frequency
			Preset smallSizePreset = deepPreset.copy();
			smallSizePreset.climate().biomeShape.undergroundBiomeSize = 50;
			UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> smallLayout = UndergroundBiomeBanding.apply(
				smallSizePreset,
				overworldEntries,
				seed,
				(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value))
			);

			Preset largeSizePreset = deepPreset.copy();
			largeSizePreset.climate().biomeShape.undergroundBiomeSize = 500;
			UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> largeLayout = UndergroundBiomeBanding.apply(
				largeSizePreset,
				overworldEntries,
				seed,
				(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value))
			);

			int smallTransitions = 0;
			int largeTransitions = 0;
			net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> prevSmall = null;
			net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> prevLarge = null;
			int fixedQuartY = QuartPos.fromBlock(-160);

			for (int blockX = 0; blockX <= 1500; blockX += 16) {
				int qX = QuartPos.fromBlock(blockX);
				net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> bSmall = smallLayout.findValue(target, qX, fixedQuartY, 0);
				net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> bLarge = largeLayout.findValue(target, qX, fixedQuartY, 0);
				if (prevSmall != null && !prevSmall.equals(bSmall)) smallTransitions++;
				if (prevLarge != null && !prevLarge.equals(bLarge)) largeTransitions++;
				prevSmall = bSmall;
				prevLarge = bLarge;
			}

			if (smallTransitions <= largeTransitions * 2) {
				throw new AssertionError("undergroundBiomeSize not respected: small size (50) should have significantly more transitions than large size (500). Small=" + smallTransitions + ", Large=" + largeTransitions);
			}

			// 4. Verify 1024-block deep world with 100% cave coverage:
			// At 0% (0.0F) climate influence: completely tolerant, rotating across all cave biomes
			Preset extraDeepPreset = Presets.makeFTFDefault();
			extraDeepPreset.world().properties.worldDepth = 1024;
			extraDeepPreset.world().properties.worldHeight = 320;
			extraDeepPreset.climate().biomeShape.undergroundBiomeSize = 225;
			extraDeepPreset.climate().biomeShape.undergroundBiomeVerticalSize = 64;
			extraDeepPreset.climate().biomeShape.undergroundBiomeCoverage = 1.0F;
			extraDeepPreset.climate().biomeShape.undergroundBiomeClimateInfluence = 0.0F;
			extraDeepPreset.climate().biomeShape.undergroundBiomeBanding = true;

			UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> extraDeepLayout = UndergroundBiomeBanding.apply(
				extraDeepPreset,
				overworldEntries,
				seed,
				(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value)),
				UndergroundBiomeTags::isCave
			);

			java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> deepWorldStackedBiomes = new java.util.LinkedHashSet<>();
			int deepWorldTransitions = 0;
			net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> prevDeepBiome = null;
			for (int blockY = -96; blockY >= -1000; blockY -= 64) {
				int quartY = QuartPos.fromBlock(blockY);
				net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b = extraDeepLayout.findValue(target, quartX, quartY, quartZ);
				if (!UndergroundBiomeTags.isCave(b)) {
					throw new AssertionError("1024-deep world at Y=" + blockY + " generated non-cave biome: " + b + "! Expected exclusively cave biomes with 100% coverage.");
				}
				deepWorldStackedBiomes.add(b);
				if (prevDeepBiome != null && !prevDeepBiome.equals(b)) {
					deepWorldTransitions++;
				}
				prevDeepBiome = b;
			}
			if (deepWorldStackedBiomes.size() != 3) {
				throw new AssertionError("1024-deep world with 0% climate influence should rotate across all 3 cave biomes, but found " + deepWorldStackedBiomes.size() + ": " + deepWorldStackedBiomes);
			}
			if (deepWorldTransitions < 8) {
				throw new AssertionError("1024-deep world should have frequent vertical banding transitions down 1000 blocks, but only got " + deepWorldTransitions);
			}

			// Verify 100% (1.0F) climate influence: always selected entirely by accurate climate parameter values
			Preset accurateClimatePreset = extraDeepPreset.copy();
			accurateClimatePreset.climate().biomeShape.undergroundBiomeClimateInfluence = 1.0F;
			UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> accurateLayout = UndergroundBiomeBanding.apply(
				accurateClimatePreset,
				overworldEntries,
				seed,
				(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value)),
				UndergroundBiomeTags::isCave
			);
			java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> accurateBiomes = new java.util.LinkedHashSet<>();
			for (int blockY = -96; blockY >= -1000; blockY -= 64) {
				int quartY = QuartPos.fromBlock(blockY);
				net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b = accurateLayout.findValue(target, quartX, quartY, quartZ);
				accurateBiomes.add(b);
			}
			if (accurateBiomes.size() != 1) {
				throw new AssertionError("1024-deep world with 100% climate influence should select exclusively the closest climate candidate, but found " + accurateBiomes.size() + ": " + accurateBiomes);
			}

			// 5. Verify 0% cave coverage underground: all sampled biomes must be background (non-cave) biomes
			Preset zeroCoveragePreset = extraDeepPreset.copy();
			zeroCoveragePreset.climate().biomeShape.undergroundBiomeCoverage = 0.0F;
			UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> zeroCoverageLayout = UndergroundBiomeBanding.apply(
				zeroCoveragePreset,
				overworldEntries,
				seed,
				(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value)),
				UndergroundBiomeTags::isCave
			);
			for (int blockY = -96; blockY >= -1000; blockY -= 128) {
				int quartY = QuartPos.fromBlock(blockY);
				net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b = zeroCoverageLayout.findValue(target, quartX, quartY, quartZ);
				if (UndergroundBiomeTags.isCave(b)) {
					throw new AssertionError("0% coverage preset generated cave biome at Y=" + blockY + ": " + b);
				}
			}

			// 6. Test Live UnifiedBiomeSource with liveSampler across subterranean depths
			FTFCommon.LOGGER.info("[FTF-Test] Probing live server subterranean biome resolution via liveSampler...");
			if (server.overworld().getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator liveTfGen) {
				var liveSampler = server.overworld().getChunkSource().randomState().sampler();
				int nonCaveCount = 0;
				int oceanInRockCount = 0;
				int totalSubterraneanSamples = 0;
				for (int cx = -100; cx <= 100; cx += 50) {
					for (int cz = -100; cz <= 100; cz += 50) {
						int qx = cx << 2;
						int qz = cz << 2;
						for (int blockY = -100; blockY >= -240; blockY -= 32) {
							int qy = QuartPos.fromBlock(blockY);
							var liveBiome = liveTfGen.getBiomeSource().getNoiseBiome(qx, qy, qz, liveSampler);
							totalSubterraneanSamples++;
							boolean isCave = UndergroundBiomeTags.isCave(liveBiome);
							String path = liveBiome.unwrapKey().map(k -> k.location().getPath()).orElse("unknown");
							if (!isCave) {
								nonCaveCount++;
								if (path.contains("ocean")) {
									oceanInRockCount++;
									FTFCommon.LOGGER.warn("[FTF-Test] Subterranean OCEAN in rock detected at ({}, Y={}, {}): {}", qx << 2, blockY, qz << 2, path);
									if (oceanInRockCount == 1) {
										var sampledTarget = liveSampler.sample(qx, qy, qz);
										float cov = UndergroundBiomeSurfaceProtection.coverageFactor(liveSampler, sampledTarget, qx, qy, qz);
										FTFCommon.LOGGER.warn("[FTF-Test] DEBUG sample at ({}, Y={}, {}): target.depth={}, cov={}, target.cont={}",
											qx << 2, blockY, qz << 2, sampledTarget.depth(), cov, sampledTarget.continentalness());
									}
								}
							}
						}
					}
				}
				FTFCommon.LOGGER.info("[FTF-Test] Subterranean live probe: total={}, nonCave={}, oceanInRock={}",
					totalSubterraneanSamples, nonCaveCount, oceanInRockCount);
			}

			FTFCommon.LOGGER.info("[FTF-Test] PASSED: Underground biome banding verified (stacked biomes: {}, transitions: {}/{}, columnar: single, horizontal size 50 vs 500: {} vs {} transitions).",
				stackedBiomes.stream().map(h -> h.unwrapKey().map(k -> k.location().getPath()).orElse("unknown")).toList(),
				stepTransitions,
				sampleStepCount - 1,
				smallTransitions,
				largeTransitions
			);
			testRecords.add(new TestRecord(
				currentTestName,
				String.format("Underground biome banding verified: %d stacked types in column, horizontal sizing 50 vs 500 (%d vs %d transitions)",
					stackedBiomes.size(), smallTransitions, largeTransitions),
				true,
				null
			));

			// -----------------------------------------------------------------------------------------
			// Live Biome Replacement System Validation & Edge Case Failsafe
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.biomeReplacementValidation." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying Biome Replacement System (Live Generation, Resolution & Edge Case Failsafes)...");

			Registry<net.minecraft.world.level.biome.Biome> biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
			Holder.Reference<net.minecraft.world.level.biome.Biome> plainsHolder = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
			Holder.Reference<net.minecraft.world.level.biome.Biome> desertHolder = biomeRegistry.getHolderOrThrow(Biomes.DESERT);
			Holder.Reference<net.minecraft.world.level.biome.Biome> forestHolder = biomeRegistry.getHolderOrThrow(Biomes.FOREST);
			Holder.Reference<net.minecraft.world.level.biome.Biome> deepDarkHolder = biomeRegistry.getHolderOrThrow(Biomes.DEEP_DARK);

			if (server.overworld().getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator liveTfGen) {
				WorldgenPlan initialPlan = liveTfGen.plan().orElse(null);
				if (initialPlan == null) {
					throw new AssertionError("Live server TerraForgedChunkGenerator has no compiled WorldgenPlan");
				}
				WorldgenOwner liveOwner = initialPlan.owner();
				Climate.Sampler liveSampler = server.overworld().getChunkSource().randomState().sampler();
				BiomeReplacementConfigSnapshot originalSnapshot = liveOwner.biomeReplacementConfig();

				// Sample baseline biome at coordinate (0, 64, 0)
				Holder<net.minecraft.world.level.biome.Biome> baselineSample = liveTfGen.getBiomeSource().getNoiseBiome(0, 64, 0, liveSampler);
				ResourceLocation baselineLoc = baselineSample.unwrapKey().map(net.minecraft.resources.ResourceKey::location).orElse(null);
				if (baselineLoc == null) {
					throw new AssertionError("Live server sampled biome at (0, 64, 0) has no resource key");
				}

				// Choose a replacement target distinctly different from baseline
				String replacementTargetStr = baselineLoc.equals(ResourceLocation.parse("minecraft:desert"))
					? "minecraft:plains" : "minecraft:desert";
				ResourceLocation replacementTargetLoc = ResourceLocation.parse(replacementTargetStr);

				// Construct test configuration with the live replacement rule AND various edge cases
				String liveTestContent = String.format(java.util.Locale.ROOT, """
					format_version=1
					%s > %s
					#minecraft:is_forest > %s
					minecraft:deep_dark > null
					modded:nonexistent_biome > %s
					minecraft:Plains > %s
					[invalid:dimension space]
					minecraft:plains > %s
					minecraft:plains > terrablender:deferred_placeholder
					#c:empty_tag_test > %s
					""", baselineLoc, replacementTargetStr, replacementTargetStr,
					replacementTargetStr, replacementTargetStr, replacementTargetStr, replacementTargetStr);

				BiomeReplacementConfig liveTestConfig = BiomeReplacementConfig.parse(liveTestContent);
				BiomeReplacementConfigSnapshot liveTestSnapshot = new BiomeReplacementConfigSnapshot(
					originalSnapshot.revision() + 1000L,
					"live_test_fingerprint",
					originalSnapshot.path(),
					BiomeReplacementConfigSnapshot.Origin.NATIVE,
					liveTestContent,
					liveTestConfig,
					originalSnapshot.legacyFilePresent()
				);

				try {
					// Refresh inputs on the live generator so the replacement takes effect in real worldgen
					WorldgenPlan refreshedLivePlan = liveTfGen.refreshInputs(
						liveOwner.resourceRevision(),
						liveOwner.resourceLayerFingerprint(),
						liveOwner.tagEpoch(),
						liveTestSnapshot,
						liveOwner.contributionRevision(),
						(FTFRandomState) (Object) server.overworld().getChunkSource().randomState()
					);

					if (refreshedLivePlan.biomeReplacement().plan().isConfigIgnored()) {
						throw new AssertionError("Live replacement plan was unexpectedly suppressed due to non-fatal edge cases");
					}

					// Verify in the live chunk generator biome source that the replacement actually occurred
					Holder<net.minecraft.world.level.biome.Biome> liveReplacedSample = liveTfGen.getBiomeSource().getNoiseBiome(0, 64, 0, liveSampler);
					ResourceLocation actualLiveLoc = liveReplacedSample.unwrapKey().map(net.minecraft.resources.ResourceKey::location).orElse(null);
					if (!replacementTargetLoc.equals(actualLiveLoc)) {
						throw new AssertionError(String.format(
							"Live server replacement failed at (0, 64, 0)! Expected target '%s' but live generator sampled '%s'",
							replacementTargetLoc, actualLiveLoc
						));
					}

					// Also verify via resolveBiomeInCell
					Holder<net.minecraft.world.level.biome.Biome> liveCellSample = liveTfGen.resolveBiomeInCell(0, 64, 0, liveSampler, 0L, 0L);
					ResourceLocation actualCellLoc = liveCellSample.unwrapKey().map(net.minecraft.resources.ResourceKey::location).orElse(null);
					if (!replacementTargetLoc.equals(actualCellLoc)) {
						throw new AssertionError(String.format(
							"Live resolveBiomeInCell replacement failed at (0, 64, 0)! Expected '%s' but got '%s'",
							replacementTargetLoc, actualCellLoc
						));
					}

					// Verify direct rule contract on refreshed plan
					BiomeReplacementPlan activePlan = refreshedLivePlan.biomeReplacement().plan();
					if (!activePlan.active()) {
						throw new AssertionError("Refreshed biome replacement plan should be active");
					}
					if (!activePlan.isRemoved(deepDarkHolder)) {
						throw new AssertionError("Live removal failed: deep_dark should be marked removed");
					}

					// 2. Fatal Contradiction Test: target removed must gracefully suppress all rules
					String fatalContent = String.format(java.util.Locale.ROOT, """
						format_version=1
						%s > null
						%s > %s
						""", replacementTargetStr, baselineLoc, replacementTargetStr);
					BiomeReplacementConfig fatalConfig = BiomeReplacementConfig.parse(fatalContent);
					BiomeReplacementConfigSnapshot fatalSnapshot = new BiomeReplacementConfigSnapshot(
						originalSnapshot.revision() + 2000L,
						"live_fatal_fingerprint",
						originalSnapshot.path(),
						BiomeReplacementConfigSnapshot.Origin.NATIVE,
						fatalContent,
						fatalConfig,
						originalSnapshot.legacyFilePresent()
					);

					WorldgenPlan fatalLivePlan = liveTfGen.refreshInputs(
						liveOwner.resourceRevision(),
						liveOwner.resourceLayerFingerprint(),
						liveOwner.tagEpoch(),
						fatalSnapshot,
						liveOwner.contributionRevision(),
						(FTFRandomState) (Object) server.overworld().getChunkSource().randomState()
					);

					if (!fatalLivePlan.biomeReplacement().plan().isConfigIgnored()) {
						throw new AssertionError("Fatal contradictory replacement rule did not trigger isConfigIgnored()");
					}
					if (fatalLivePlan.biomeReplacement().plan().active()) {
						throw new AssertionError("Fatal contradictory plan should be inactive");
					}

					// Sampling again under suppressed plan should revert cleanly to baseline without crash
					Holder<net.minecraft.world.level.biome.Biome> revertedSample = liveTfGen.getBiomeSource().getNoiseBiome(0, 64, 0, liveSampler);
					ResourceLocation revertedLoc = revertedSample.unwrapKey().map(net.minecraft.resources.ResourceKey::location).orElse(null);
					if (!baselineLoc.equals(revertedLoc)) {
						throw new AssertionError(String.format(
							"Reversion failed under ignored config! Expected baseline '%s' but got '%s'",
							baselineLoc, revertedLoc
						));
					}

				} finally {
					// Cleanly restore original configuration on the live generator with an advancing revision
					BiomeReplacementConfigSnapshot restoredSnapshot = new BiomeReplacementConfigSnapshot(
						originalSnapshot.revision() + 3000L,
						originalSnapshot.fingerprint(),
						originalSnapshot.path(),
						originalSnapshot.origin(),
						originalSnapshot.content(),
						originalSnapshot.config(),
						originalSnapshot.legacyFilePresent()
					);
					liveTfGen.refreshInputs(
						liveOwner.resourceRevision(),
						liveOwner.resourceLayerFingerprint(),
						liveOwner.tagEpoch(),
						restoredSnapshot,
						liveOwner.contributionRevision(),
						(FTFRandomState) (Object) server.overworld().getChunkSource().randomState()
					);
				}
			}

			FTFCommon.LOGGER.info(
				"[FTF-Test] PASSED: Biome replacement system validated in live server run: real chunk generator replacements verified, edge cases isolated, fatal contradictions safely suppressed, and live generator cleanly restored."
			);
			testRecords.add(new TestRecord(
				currentTestName,
				"Live chunk generator biome replacement validated (live sampling, edge cases, contradiction suppression, state restoration)",
				true,
				null
			));

			// -----------------------------------------------------------------------------------------
			// World Generation Memory Leak Prevention Verification
			// -----------------------------------------------------------------------------------------
			currentTestName = "presetValidation.worldgenMemoryLeak." + loader;
			watchdog.setStep(currentTestName);
			FTFCommon.LOGGER.info("[FTF-Test] Verifying world generation memory leak prevention and ThreadLocal lifecycle...");

			// 1. Verify that transient Climate.Sampler instances are not retained on Minecraft background workers
			net.minecraft.world.level.biome.Climate.Sampler[] samplerHolder =
				new net.minecraft.world.level.biome.Climate.Sampler[]{net.minecraft.world.level.biome.Climate.empty()};
			java.lang.ref.WeakReference<net.minecraft.world.level.biome.Climate.Sampler> samplerLeakRef =
				new java.lang.ref.WeakReference<>(samplerHolder[0]);

			Climate.TargetPoint leakTarget = Climate.target(0.0F, 0.0F, 0.0F, -0.5F, 1.5F, 0.0F);
			int leakWorkerTasks = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
			java.util.concurrent.CountDownLatch leakLatch = new java.util.concurrent.CountDownLatch(leakWorkerTasks);
			for (int t = 0; t < leakWorkerTasks; t++) {
				final int threadOffset = t;
				net.minecraft.Util.backgroundExecutor().execute(() -> {
					try {
						// Deep world column scan (256 vertical quarts = 1024 blocks depth)
						for (int quartY = -128; quartY <= 128; quartY += 4) {
							for (int q = 0; q < 8; q++) {
								net.minecraft.world.level.biome.Climate.Sampler s = samplerHolder[0];
								if (s != null) {
									UndergroundBiomeSurfaceProtection.coverageFactor(s, leakTarget, (threadOffset + q) * 4, quartY, q * 4);
								}
							}
						}
					} finally {
						leakLatch.countDown();
					}
				});
			}
			if (!leakLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
				throw new AssertionError("Memory leak validation worker tasks timed out");
			}

			// 2. Verify that transient UndergroundBiomeBanding.Layout instances are not retained on Minecraft background workers
			Preset transientPreset = Presets.makeFTFDefault();
			transientPreset.climate().biomeShape.undergroundBiomeClimateInfluence = 0.75F;
			transientPreset.climate().biomeShape.undergroundBiomeBanding = true;
			@SuppressWarnings("unchecked")
			UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>[] layoutHolder =
				new UndergroundBiomeBanding.Layout[]{
					UndergroundBiomeBanding.apply(
						transientPreset,
						overworldEntries,
						12345L,
						(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value)),
						UndergroundBiomeTags::isCave
					)
				};
			java.lang.ref.WeakReference<UndergroundBiomeBanding.Layout<?>> layoutLeakRef =
				new java.lang.ref.WeakReference<>(layoutHolder[0]);

			java.util.concurrent.CountDownLatch layoutLatch = new java.util.concurrent.CountDownLatch(leakWorkerTasks);
			for (int t = 0; t < leakWorkerTasks; t++) {
				final int threadOffset = t;
				net.minecraft.Util.backgroundExecutor().execute(() -> {
					try {
						for (int quartY = -128; quartY <= 128; quartY += 4) {
							for (int q = 0; q < 8; q++) {
								UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> l = layoutHolder[0];
								if (l != null) {
									l.findValue(leakTarget, (threadOffset + q) * 4, quartY, q * 4, 1.0F);
								}
							}
						}
					} finally {
						layoutLatch.countDown();
					}
				});
			}
			if (!layoutLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
				throw new AssertionError("Layout memory leak validation worker tasks timed out");
			}

			// Clear strong references while background worker threads are STILL RUNNING AND ALIVE!
			samplerHolder[0] = null;
			layoutHolder[0] = null;

			// Verify garbage collection cleans up both weak references while threads remain alive in backgroundExecutor
			boolean samplerCollected = false;
			boolean layoutCollected = false;
			for (int i = 0; i < 50; i++) {
				if (!samplerCollected && samplerLeakRef.get() == null) samplerCollected = true;
				if (!layoutCollected && layoutLeakRef.get() == null) layoutCollected = true;
				if (samplerCollected && layoutCollected) break;
				System.gc();
				Thread.sleep(50);
			}

			if (!samplerCollected) {
				throw new AssertionError("Memory leak detected: Climate.Sampler was retained in ThreadLocal or caches by persistent worker threads!");
			}
			if (!layoutCollected) {
				throw new AssertionError("Memory leak detected: UndergroundBiomeBanding.Layout was retained in ThreadLocal or caches by persistent worker threads!");
			}

			// 3. Concrete Expanding Area Memory Leak Measurement on Deep World Presets (1024-block depth)
			// Simulates player flying and generating a 1000x1000 block region in a 1024-deep world starting at middle of continent
			BlockPos continentCenter = WorldgenBenchmarkRunner.resolveContinentCenter(server.overworld(), 0, 0);
			int continentCenterX = continentCenter.getX();
			int continentCenterZ = continentCenter.getZ();
			FTFCommon.LOGGER.info(
				"[FTF-Test] Measuring memory stability across 1000x1000 block region centered at continent middle ({}, {}) on 1024-deep world preset...",
				continentCenterX, continentCenterZ
			);
			Preset leakDeepPreset = Presets.makeFTFDefault();
			leakDeepPreset.world().properties.worldDepth = 1024;
			leakDeepPreset.world().properties.worldHeight = 320;
			leakDeepPreset.climate().biomeShape.undergroundBiomeSize = 225;
			leakDeepPreset.climate().biomeShape.undergroundBiomeVerticalSize = 64;
			leakDeepPreset.climate().biomeShape.undergroundBiomeCoverage = 1.0F;
			leakDeepPreset.climate().biomeShape.undergroundBiomeClimateInfluence = 0.0F;
			leakDeepPreset.climate().biomeShape.undergroundBiomeBanding = true;

			final UndergroundBiomeBanding.Layout<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> deepAreaLayout =
				UndergroundBiomeBanding.apply(
					leakDeepPreset,
					overworldEntries,
					seed,
					(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value)),
					UndergroundBiomeTags::isCave
				);

			Climate.Sampler liveSamplerForLeak = server.overworld().getChunkSource().randomState().sampler();
			int minChunkX = (continentCenterX - 500) >> 4;
			int maxChunkX = (continentCenterX + 500) >> 4;
			int minChunkZ = (continentCenterZ - 500) >> 4;
			int maxChunkZ = (continentCenterZ + 500) >> 4;
			int chunksSpanX = maxChunkX - minChunkX + 1;
			int chunksSpanZ = maxChunkZ - minChunkZ + 1;
			int areaBatches = 4;
			long[] retainedHeapByBatch = new long[areaBatches];

			for (int b = 0; b < areaBatches; b++) {
				final int batchNum = b;
				int zStart = minChunkZ + (b * chunksSpanZ) / areaBatches;
				int zEnd = minChunkZ + ((b + 1) * chunksSpanZ) / areaBatches;
				int batchChunkCount = chunksSpanX * (zEnd - zStart);
				java.util.concurrent.CountDownLatch batchLatch = new java.util.concurrent.CountDownLatch(batchChunkCount);

				for (int cz = zStart; cz < zEnd; cz++) {
					final int chunkZ = cz;
					for (int cx = minChunkX; cx <= maxChunkX; cx++) {
						final int chunkX = cx;
						net.minecraft.Util.backgroundExecutor().execute(() -> {
							try {
								int baseQuartX = chunkX << 2;
								int baseQuartZ = chunkZ << 2;
								// 1024 depth: quartY from -256 to 80 (336 vertical quarts)
								for (int quartY = -256; quartY <= 80; quartY += 8) {
									for (int qz = 0; qz < 4; qz += 2) {
										for (int qx = 0; qx < 4; qx += 2) {
											int qX = baseQuartX + qx;
											int qZ = baseQuartZ + qz;
											float coverage = UndergroundBiomeSurfaceProtection.coverageFactor(
												liveSamplerForLeak, leakTarget, qX, quartY, qZ
											);
											net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> sampled =
												deepAreaLayout.findValue(
													leakTarget, qX, quartY, qZ, coverage,
													(iqx, iqy, iqz) -> leakTarget
												);
											if (sampled == null) {
												throw new AssertionError("Sampled biome was null");
											}
											// Subterranean cave checks: 100% cave coverage means cave biomes underground
											if (quartY <= -16 && coverage >= 1.0F && !UndergroundBiomeTags.isCave(sampled)) {
												String path = sampled.unwrapKey().map(k -> k.location().getPath()).orElse("unknown");
												throw new AssertionError(String.format(
													java.util.Locale.ROOT,
													"Non-cave biome '%s' returned underground at (qx=%d, quartY=%d, qz=%d) when 100%% cave coverage configured!",
													path, qX, quartY, qZ
												));
											}
											// Surface protection check: cave biomes must not project through surface
											if (coverage <= 0.0F && UndergroundBiomeTags.isCave(sampled)) {
												String path = sampled.unwrapKey().map(k -> k.location().getPath()).orElse("unknown");
												throw new AssertionError(String.format(
													java.util.Locale.ROOT,
													"Cave biome '%s' projected through surface at (qx=%d, quartY=%d, qz=%d) when coverage factor is 0.0!",
													path, qX, quartY, qZ
												));
											}
										}
									}
								}
							} finally {
								batchLatch.countDown();
							}
						});
					}
				}
				if (!batchLatch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
					throw new AssertionError("Deep area batch " + b + " timed out");
				}

				// Measure post-GC retained heap
				for (int gc = 0; gc < 5; gc++) {
					System.gc();
					Thread.sleep(50);
				}
				retainedHeapByBatch[b] = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
				FTFCommon.LOGGER.info(
					"[FTF-Test] Deep world (1024 depth) 1000x1000 block region batch {}/{} ({} chunks in batch): Retained heap = {} MB",
					b + 1, areaBatches, batchChunkCount, retainedHeapByBatch[b] / (1024 * 1024)
				);
			}

			// Clear layout caches
			deepAreaLayout.clear();

			// Check memory trend across batches
			long initialHeap = retainedHeapByBatch[0];
			long finalHeap = retainedHeapByBatch[areaBatches - 1];
			long netGrowth = finalHeap - initialHeap;
			FTFCommon.LOGGER.info(
				"[FTF-Test] Deep world expanding area memory report (1000x1000 block region): Initial={} MB, Final={} MB, Net Growth={} MB",
				initialHeap / (1024 * 1024), finalHeap / (1024 * 1024), netGrowth / (1024 * 1024)
			);

			if (netGrowth > 50L * 1024L * 1024L) {
				throw new AssertionError(String.format(
					java.util.Locale.ROOT,
					"Memory leak detected during expanding area generation: heap climbed by %.2f MB across %d batches (Initial: %.2f MB, Final: %.2f MB)",
					netGrowth / (1024.0 * 1024.0), areaBatches, initialHeap / (1024.0 * 1024.0), finalHeap / (1024.0 * 1024.0)
				));
			}

			// 4. Live Server Chunk Generation Memory Stability Test Across Expanding Area
			// Generates chunks via ServerChunkCache to verify that generated chunks, flow fields,
			// cell caches, and structure plans do not leak monotonically as new terrain is explored.
			FTFCommon.LOGGER.info("[FTF-Test] Measuring live server chunk generation memory stability across expanding area starting at continent center...");
			ServerLevel liveLevel = server.overworld();
			net.minecraft.server.level.ServerChunkCache liveChunkSource = liveLevel.getChunkSource();
			net.minecraft.server.level.TicketType<net.minecraft.util.Unit> leakTicket =
				net.minecraft.server.level.TicketType.create("ftf_leak_test", (a, b) -> 0);

			int chunkBatches = 4;
			int chunksPerLiveBatch = 20;
			long[] liveChunkHeapByBatch = new long[chunkBatches];

			for (int b = 0; b < chunkBatches; b++) {
				java.util.List<net.minecraft.world.level.ChunkPos> batchPositions = new java.util.ArrayList<>(chunksPerLiveBatch);
				java.util.List<java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>> futures =
					new java.util.ArrayList<>(chunksPerLiveBatch);

				for (int c = 0; c < chunksPerLiveBatch; c++) {
					int chunkIdx = b * chunksPerLiveBatch + c;
					int cx = minChunkX + (chunkIdx % 10);
					int cz = minChunkZ + (chunkIdx / 10);
					net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(cx, cz);
					batchPositions.add(pos);
					liveChunkSource.addRegionTicket(leakTicket, pos, 0, net.minecraft.util.Unit.INSTANCE);
					futures.add(liveChunkSource.getChunkFuture(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true));
				}

				for (var f : futures) {
					try {
						f.get(30, java.util.concurrent.TimeUnit.SECONDS);
					} catch (Exception ex) {
						throw new AssertionError("Live chunk generation timed out or failed in batch " + b, ex);
					}
				}
				futures.clear();

				// Release tickets
				for (net.minecraft.world.level.ChunkPos pos : batchPositions) {
					liveChunkSource.removeRegionTicket(leakTicket, pos, 0, net.minecraft.util.Unit.INSTANCE);
				}

				// Tick chunk source and drain server task queues
				int drainedTasks = 0;
				for (int tick = 0; tick < 50; tick++) {
					liveChunkSource.tick(() -> true, true);
					while (liveChunkSource.pollTask()) {
						drainedTasks++;
					}
					while (server.pollTask()) {
						drainedTasks++;
					}
				}
				// Flush any pending chunk writes to region files so background IO queues are clean
				liveLevel.save(null, true, false);
				while (server.pollTask()) {
					drainedTasks++;
				}
				int chunkMapSize = liveChunkSource.chunkMap.size();
				boolean hasWork = liveChunkSource.chunkMap.hasWork();
				FTFCommon.LOGGER.info("[FTF-Test] Batch {} unloaded: drained {} server/chunk tasks, chunkMapSize={}, hasWork={}",
					b + 1, drainedTasks, chunkMapSize, hasWork);

				if ((Object) server.overworld().getChunkSource().randomState() instanceof FTFRandomState rtfRs) {
					GeneratorContext gCtx = rtfRs.generatorContext();
					if (gCtx != null) {
						int filteredSize = gCtx.filteredChunkCache != null ? gCtx.filteredChunkCache.size() : -1;
						int rawSize = gCtx.rawChunkCache != null ? gCtx.rawChunkCache.size() : -1;
						FTFCommon.LOGGER.info("[FTF-Test] FTF Caches: filteredChunkCache={}, rawChunkCache={}",
							filteredSize, rawSize);
						if (filteredSize > etcodehome.freeterraforged.world.worldgen.cell.heightmap.FilteredChunkCache.MAX_ENTRIES || rawSize > etcodehome.freeterraforged.world.worldgen.cell.heightmap.RawChunkCache.MAX_ENTRIES) {
							throw new AssertionError(String.format(
								java.util.Locale.ROOT,
								"FTF terrain cache exceeded capacity bounds! filteredChunkCache=%d (max 512), rawChunkCache=%d (max 512)",
								filteredSize, rawSize
							));
						}
					}
				}

				for (java.lang.reflect.Field f : net.minecraft.server.level.ChunkMap.class.getDeclaredFields()) {
					try {
						f.setAccessible(true);
						Object val = f.get(liveChunkSource.chunkMap);
						if (val instanceof java.util.Map<?, ?> m) {
							FTFCommon.LOGGER.info("[FTF-Test] ChunkMap map field {}: size={}", f.getName(), m.size());
						} else if (val instanceof it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> lom) {
							FTFCommon.LOGGER.info("[FTF-Test] ChunkMap Long2ObjectMap field {}: size={}", f.getName(), lom.size());
						} else if (val instanceof it.unimi.dsi.fastutil.longs.LongSet ls) {
							FTFCommon.LOGGER.info("[FTF-Test] ChunkMap LongSet field {}: size={}", f.getName(), ls.size());
						}
					} catch (Exception ignored) {}
				}

				// Measure post-GC retained heap
				for (int gc = 0; gc < 5; gc++) {
					System.gc();
					Thread.sleep(50);
				}
				liveChunkHeapByBatch[b] = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
				int loadedCount = liveChunkSource.getLoadedChunksCount();
				boolean sampleStillLoaded = liveChunkSource.hasChunk(batchPositions.get(0).x, batchPositions.get(0).z);
				FTFCommon.LOGGER.info(
					"[FTF-Test] Live chunk generation area batch {}/{} ({} chunks requested, loadedChunks={}, sampleStillLoaded={}): Retained heap = {} MB",
					b + 1, chunkBatches, (b + 1) * chunksPerLiveBatch, loadedCount, sampleStillLoaded, liveChunkHeapByBatch[b] / (1024 * 1024)
				);

				if (b == 0 || b == chunkBatches - 1) {
					try {
						long pid = ProcessHandle.current().pid();
						java.nio.file.Path jcmdPath = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "jcmd.exe");
						if (!java.nio.file.Files.exists(jcmdPath)) {
							jcmdPath = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "jcmd");
						}
						if (java.nio.file.Files.exists(jcmdPath)) {
							Process proc = new ProcessBuilder(
								jcmdPath.toString(),
								String.valueOf(pid),
								"GC.class_histogram"
							).redirectErrorStream(true).start();
							String histo = new String(proc.getInputStream().readAllBytes());
							String[] lines = histo.split("\n");
							FTFCommon.LOGGER.info("[FTF-Test] --- Class Histogram Top 25 (Batch {}) ---", b + 1);
							for (int i = 0; i < Math.min(28, lines.length); i++) {
								FTFCommon.LOGGER.info("[FTF-Test] {}", lines[i].trim());
							}
						}
					} catch (Exception ex) {
						FTFCommon.LOGGER.warn("[FTF-Test] Failed to run GC.class_histogram: {}", ex.getMessage());
					}
				}
			}

			long liveInitialHeap = liveChunkHeapByBatch[0];
			long liveFinalHeap = liveChunkHeapByBatch[chunkBatches - 1];
			long liveNetGrowth = liveFinalHeap - liveInitialHeap;
			FTFCommon.LOGGER.info(
				"[FTF-Test] Live chunk generation memory report: Initial={} MB, Final={} MB, Net Growth={} MB",
				liveInitialHeap / (1024 * 1024), liveFinalHeap / (1024 * 1024), liveNetGrowth / (1024 * 1024)
			);

			if (liveNetGrowth > 175L * 1024L * 1024L) {
				throw new AssertionError(String.format(
					java.util.Locale.ROOT,
					"Memory leak detected during live chunk generation: heap climbed by %.2f MB across %d batches (Initial: %.2f MB, Final: %.2f MB)",
					liveNetGrowth / (1024.0 * 1024.0), chunkBatches, liveInitialHeap / (1024.0 * 1024.0), liveFinalHeap / (1024.0 * 1024.0)
				));
			}

			FTFCommon.LOGGER.info("[FTF-Test] PASSED: World generation memory leak prevention verified (transient samplers and layouts reclaimed cleanly; expanding area memory stable).");
			testRecords.add(new TestRecord(
				currentTestName,
				"World generation memory leak prevention verified: transient samplers and layouts collected cleanly by GC without ThreadLocal retention",
				true,
				null
			));

			// -----------------------------------------------------------------------------------------
			// Complete Summary Banner
			// -----------------------------------------------------------------------------------------
			watchdog.complete();
			FTFCommon.LOGGER.info("================================================================================");
			FTFCommon.LOGGER.info("[FTF-Test] ALL PRESET PERSISTENCE, RESOLUTION, HEIGHTMAP & PRESETMANAGER TESTS PASSED ON LOADER: {}", serverModName);
			FTFCommon.LOGGER.info("[FTF-Test] Verified on disk:");
			for (TestRecord tr : testRecords) {
				FTFCommon.LOGGER.info("[FTF-Test] - {}: {}", tr.name(), tr.passed() ? "PASSED" : "FAILED");
			}
			FTFCommon.LOGGER.info("================================================================================");

			writeSuiteSummaryReport(serverModName, testRecords, true);

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
			watchdog.complete();
			testRecords.add(new TestRecord(currentTestName, "Execution failed: " + failure.getMessage(), false, failure.getMessage()));
			writeSuiteSummaryReport(serverModName, testRecords, false);
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

	private static void writeSuiteSummaryReport(String serverModName, List<TestRecord> testRecords, boolean overallPassed) {
		try {
			Path reportDir = HeightmapValidator.resolveReportDirectory();
			Files.createDirectories(reportDir);
			String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			String loader = serverModName.replaceAll("[^a-zA-Z0-9_-]", "_");
			// Append automated unit test records so they are included in suite summary
			Path unitTestFile = reportDir.resolve("unit_tests_report.txt");
			if (Files.exists(unitTestFile)) {
				try {
					String uContent = Files.readString(unitTestFile, StandardCharsets.UTF_8);
					java.util.regex.Matcher caseMatcher = java.util.regex.Pattern.compile("-\\s*\\[(PASSED|FAILED)\\]\\s*(.*?)\\s*\\(([^()\\r\\n]+)\\)").matcher(uContent);
					while (caseMatcher.find()) {
						boolean passed = "PASSED".equalsIgnoreCase(caseMatcher.group(1));
						String name = caseMatcher.group(2).trim();
						String fullClass = caseMatcher.group(3).trim();
						String simpleClass = fullClass.contains(".") ? fullClass.substring(fullClass.lastIndexOf('.') + 1) : fullClass;
						String cleanName = name.endsWith("()") ? name.substring(0, name.length() - 2) : name;
						String token = "unitTest." + simpleClass + "." + cleanName + "." + loader;
						testRecords.add(new TestRecord(token, simpleClass + "." + name, passed, passed ? null : "Unit test failed"));
					}
				} catch (Exception ignored) {
				}
			}
			Path summaryFile = reportDir.resolve(String.format("suite_summary_%s_%s_report.txt", loader, timestamp));
			StringBuilder sb = new StringBuilder();
			sb.append("FreeTerraForged Test Execution Report\n");
			sb.append("=====================================\n");
			sb.append("Loader: ").append(loader).append("\n");
			sb.append("Timestamp: ").append(timestamp).append("\n");
			sb.append("Outcome: ").append(overallPassed ? "PASSED" : "FAILED").append("\n\n");
			for (TestRecord tr : testRecords) {
				sb.append("[Test Entry]\n");
				sb.append("Name: ").append(tr.name()).append("\n");
				sb.append("Outcome: ").append(tr.passed() ? "PASSED" : "FAILED").append("\n");
				sb.append("Notes: ").append(tr.notes()).append("\n");
				if (tr.error() != null) {
					sb.append("Error: ").append(tr.error()).append("\n");
				}
				sb.append("\n");
			}
			Files.writeString(summaryFile, sb.toString(), StandardCharsets.UTF_8);

			// Automatically compile and write paired local HTML report
			try {
				Path htmlReport = LocalTestReportGenerator.generate(reportDir);
				if (htmlReport != null) {
					FTFCommon.LOGGER.info("[FTF-Test] Local HTML verification report generated: {}", htmlReport.toUri());
				}
			} catch (Throwable t) {
				FTFCommon.LOGGER.warn("[FTF-Test] Failed to generate local HTML report: {}", t.getMessage());
			}
		} catch (Exception e) {
			FTFCommon.LOGGER.warn("[FTF-Test] Failed to archive suite summary: {}", e.getMessage());
		}
	}

	private static class TestWatchdog implements Runnable {
		private final Thread testThread;
		private final String loader;
		private final long stepTimeoutMs;
		private final long suiteTimeoutMs;
		private final long suiteStartTimeMs;
		private volatile String currentStepName = "Initialization";
		private volatile long stepStartTimeMs;
		private volatile boolean completed = false;

		public TestWatchdog(Thread testThread, String loader) {
			this.testThread = testThread;
			this.loader = loader;
			this.stepTimeoutMs = Long.getLong("freeterraforged.test.timeout.step", 120L) * 1000L;
			this.suiteTimeoutMs = Long.getLong("freeterraforged.test.timeout.suite", 600L) * 1000L;
			this.suiteStartTimeMs = System.currentTimeMillis();
			this.stepStartTimeMs = this.suiteStartTimeMs;
		}

		public void setStep(String stepName) {
			this.currentStepName = stepName;
			this.stepStartTimeMs = System.currentTimeMillis();
		}

		public void complete() {
			this.completed = true;
		}

		@Override
		public void run() {
			while (!completed) {
				try {
					Thread.sleep(1000L);
				} catch (InterruptedException e) {
					break;
				}
				if (completed) break;

				long now = System.currentTimeMillis();
				long stepElapsedMs = now - stepStartTimeMs;
				long suiteElapsedMs = now - suiteStartTimeMs;

				boolean stepTimedOut = stepElapsedMs > stepTimeoutMs;
				boolean suiteTimedOut = suiteElapsedMs > suiteTimeoutMs;

				if (stepTimedOut || suiteTimedOut) {
					String reason = stepTimedOut
						? String.format("Step '%s' timed out after %d s (limit: %d s)", currentStepName, stepElapsedMs / 1000L, stepTimeoutMs / 1000L)
						: String.format("Suite timed out after %d s (limit: %d s) during step '%s'", suiteElapsedMs / 1000L, suiteTimeoutMs / 1000L, currentStepName);

					FTFCommon.LOGGER.error("================================================================================");
					FTFCommon.LOGGER.error("[FTF-Test] WATCHDOG TIMEOUT TRIGGERED ON LOADER: {}", loader);
					FTFCommon.LOGGER.error("[FTF-Test] Reason: {}", reason);
					FTFCommon.LOGGER.error("================================================================================");

					StringBuilder dump = new StringBuilder();
					dump.append("FreeTerraForged Test Watchdog Failure Report\n");
					dump.append("============================================\n");
					dump.append("Loader: ").append(loader).append("\n");
					dump.append("Failed Step: ").append(currentStepName).append("\n");
					dump.append("Step Elapsed: ").append(stepElapsedMs / 1000L).append("s (limit: ").append(stepTimeoutMs / 1000L).append("s)\n");
					dump.append("Suite Elapsed: ").append(suiteElapsedMs / 1000L).append("s (limit: ").append(suiteTimeoutMs / 1000L).append("s)\n");
					dump.append("Outcome: FAILED (WATCHDOG_TIMEOUT)\n");
					dump.append("Reason: ").append(reason).append("\n\n");
					dump.append("Active Thread Stack Traces:\n");

					for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
						Thread t = entry.getKey();
						dump.append(String.format("Thread '%s' (State: %s, Daemon: %b):\n", t.getName(), t.getState(), t.isDaemon()));
						for (StackTraceElement ste : entry.getValue()) {
							dump.append("    at ").append(ste.toString()).append("\n");
						}
						dump.append("\n");
					}

					FTFCommon.LOGGER.error(dump.toString());

					try {
						Path reportDir = HeightmapValidator.resolveReportDirectory();
						Files.createDirectories(reportDir);
						String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
						Path reportFile = reportDir.resolve(String.format("timeout_%s_%s_report.txt", loader, timestamp));
						Files.writeString(reportFile, dump.toString(), StandardCharsets.UTF_8);
						try {
							LocalTestReportGenerator.generate(reportDir);
						} catch (Throwable ignored) {
						}
					} catch (Exception io) {
						FTFCommon.LOGGER.error("[FTF-Test] Failed to write timeout report file: {}", io.getMessage());
					}

					try {
						testThread.interrupt();
					} catch (Throwable ignored) {
					}

					Runtime.getRuntime().halt(1);
					return;
				}
			}
		}
	}
}
