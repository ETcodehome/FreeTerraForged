package etcodehome.freeterraforged.world.worldgen.search;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.server.command.LocateBiomeCommand;
import etcodehome.freeterraforged.server.command.LocateCommandOptions;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.ReferenceWeirdness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.RegionFilterGrid;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomePlacementPlan;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeLocateAreaTest {

	@BeforeAll
	static void setUp() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@DisplayName("Verify minimum biome area constant and dithering rejection in synthetic query")
	void testMinimumBiomeAreaRejectsSingleQuartDithering() throws Exception {
		int expectedBlocks = LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS;
		int expectedQuarts = expectedBlocks / QuartPos.SIZE;
		assertEquals(expectedBlocks, FTFBiomeLocateQuery.MIN_BIOME_AREA_BLOCKS);
		assertEquals(expectedBlocks, LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS);

		Holder<Biome> targetBiome = Holder.Reference.createStandAlone(null, Biomes.PLAINS);
		Holder<Biome> otherBiome = Holder.Reference.createStandAlone(null, Biomes.FOREST);

		BiomeLocateSearch.SearchPlan plan = new BiomeLocateSearch.SearchPlan(
			new BiomePlacementPlan.TargetRoles(
				true,
				Set.of(BiomePlacementPlan.Role.SURFACE),
				true,
				false
			),
			new BiomeSearchResolution(4, 4)
		);

		// 1. Single 4x4 quart dither pixel at (16, 16) surrounded by otherBiome -> must NOT match minimum area
		BiomeLocateSearch.Query ditheredQuery = createSyntheticGridQuery(
			plan,
			(qx, qz) -> (qx == 4 && qz == 4) ? targetBiome : otherBiome
		);
		BiomeLocateSearch.Result ditherResult = BiomeLocateSearch.findFirst(
			ditheredQuery,
			targetBiome::equals,
			new BlockPos(0, 64, 0),
			new LocateSearchSettings(1, 1)
		).join();
		assertNull(ditherResult.match(), "Isolated 4x4 quart dithering pixel must be rejected by area check");

		// 2. Contiguous patch of required size at quarts (4..4+k-1, 4..4+k-1) -> MUST match
		BiomeLocateSearch.Query patchQuery = createSyntheticGridQuery(
			plan,
			(qx, qz) -> (qx >= 4 && qx < 4 + expectedQuarts && qz >= 4 && qz < 4 + expectedQuarts) ? targetBiome : otherBiome
		);
		BiomeLocateSearch.Result patchResult = BiomeLocateSearch.findFirst(
			patchQuery,
			targetBiome::equals,
			new BlockPos(16, 64, 16),
			new LocateSearchSettings(1, 5)
		).join();
		assertNotNull(patchResult.match(), "Contiguous minimum biome patch must be matched");
		assertEquals(new BlockPos(16, 64, 16), patchResult.match().position());
		assertEquals(targetBiome, patchResult.match().biome());
	}

	@Test
	@DisplayName("Verify LocateCommandOptions parses --min-size and --size with boundary and fallback rules")
	void testLocateCommandOptionsMinSizeParsing() throws Exception {
		// Valid custom sizes
		LocateCommandOptions opt16 = LocateCommandOptions.parse("--min-size 16");
		assertEquals(16, opt16.minSize());

		LocateCommandOptions opt20 = LocateCommandOptions.parse("--size=20");
		assertEquals(20, opt20.minSize());

		// 0 and negative fall back to default (12)
		LocateCommandOptions optZero = LocateCommandOptions.parse("--min-size 0");
		assertEquals(FTFBiomeLocateQuery.DEFAULT_MIN_BIOME_AREA_BLOCKS, optZero.minSize());

		LocateCommandOptions optNeg = LocateCommandOptions.parse("--min-size -5");
		assertEquals(FTFBiomeLocateQuery.DEFAULT_MIN_BIOME_AREA_BLOCKS, optNeg.minSize());

		// Extreme values (> 64) fail outright
		assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () ->
			LocateCommandOptions.parse("--min-size 65")
		);
		assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () ->
			LocateCommandOptions.parse("--size 1000")
		);
	}

	@Test
	@DisplayName("Verify custom minimum size plan dynamically adjusts area requirement")
	void testCustomMinimumSizeRejectionAndAcceptance() {
		Holder<Biome> targetBiome = Holder.Reference.createStandAlone(null, Biomes.PLAINS);
		Holder<Biome> otherBiome = Holder.Reference.createStandAlone(null, Biomes.FOREST);

		// 16-block requirement (k = 4 quarts)
		BiomeLocateSearch.SearchPlan plan16 = new BiomeLocateSearch.SearchPlan(
			new BiomePlacementPlan.TargetRoles(true, Set.of(BiomePlacementPlan.Role.SURFACE), true, false),
			new BiomeSearchResolution(4, 4),
			16
		);
		assertEquals(4, plan16.minBiomeAreaQuarts());

		// 12x12 patch (3x3 quarts) at (4..6, 4..6) -> should FAIL plan16
		BiomeLocateSearch.Query patch12Query = createSyntheticGridQuery(
			plan16,
			(qx, qz) -> (qx >= 4 && qx < 4 + 3 && qz >= 4 && qz < 4 + 3) ? targetBiome : otherBiome
		);
		BiomeLocateSearch.Result result12 = BiomeLocateSearch.findFirst(
			patch12Query,
			targetBiome::equals,
			new BlockPos(16, 64, 16),
			new LocateSearchSettings(1, 5)
		).join();
		assertNull(result12.match(), "12x12 patch must fail when min size is set to 16 blocks");

		// 16x16 patch (4x4 quarts) at (4..7, 4..7) -> should PASS plan16
		BiomeLocateSearch.Query patch16Query = createSyntheticGridQuery(
			plan16,
			(qx, qz) -> (qx >= 4 && qx < 4 + 4 && qz >= 4 && qz < 4 + 4) ? targetBiome : otherBiome
		);
		BiomeLocateSearch.Result result16 = BiomeLocateSearch.findFirst(
			patch16Query,
			targetBiome::equals,
			new BlockPos(16, 64, 16),
			new LocateSearchSettings(1, 5)
		).join();
		assertNotNull(result16.match(), "16x16 patch must pass when min size is set to 16 blocks");
		assertEquals(new BlockPos(16, 64, 16), result16.match().position());
	}

	@Test
	void testRawCandidateBoundHasZeroFalseNegatives() throws Exception {
		OverworldBiomeBuilder builder = new OverworldBiomeBuilder();
		List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> entries = new ArrayList<>();
		Method addBiomes = OverworldBiomeBuilder.class.getDeclaredMethod("addBiomes", Consumer.class);
		addBiomes.setAccessible(true);
		addBiomes.invoke(builder, (Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>) entries::add);
		Climate.ParameterList<ResourceKey<Biome>> list = new Climate.ParameterList<>(entries);

		Set<ResourceKey<Biome>> allBiomes = new LinkedHashSet<>();
		entries.forEach(e -> allBiomes.add(e.getSecond()));

		RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
		Preset preset = Presets.makeFTFDefault();
		PresetManager.PM.ingestFromPreset(preset);
		HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);

		try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, 1337L)) {
			int[][] regions = {
				{ 1800, 2992 }, { 0, 0 }, { 2168, 2620 }, { -5404, 15012 },
				{ -5976, 14656 }, { -12456, 19768 }, { 11232, 4288 }, { -5408, 10272 },
				{ -8576, 12128 }, { 4028, 7888 }, { -7056, 17320 }, { 2684, 3924 }
			};

			Cell raw = new Cell();
			int falseNegatives = 0;
			int totalChecked = 0;
			int totalRejected = 0;

			for (ResourceKey<Biome> targetBiome : allBiomes) {
				if (targetBiome.equals(Biomes.RIVER) || targetBiome.equals(Biomes.FROZEN_RIVER)) {
					continue;
				}
				List<Climate.ParameterPoint> targetPoints = entries.stream()
					.filter(e -> e.getSecond().equals(targetBiome) && e.getFirst().depth().min() <= 0L)
					.map(Pair::getFirst)
					.toList();
				if (targetPoints.isEmpty()) {
					continue;
				}
				BiomeLocateCellOptimization.TargetFilter filter =
					BiomeLocateCellOptimization.TargetFilter.parameterBound(ctx, targetPoints);

				for (int[] reg : regions) {
					int rcx = (reg[0] >> 4) & ~3;
					int rcz = (reg[1] >> 4) & ~3;
					RegionFilterGrid grid = RegionFilterGrid.THREAD_LOCAL.get();
					grid.init(rcx, rcz, ctx);
					ctx.filters.apply(grid);
					int baseX = rcx << 4;
					int baseZ = rcz << 4;

					for (int dz = 0; dz < 64; dz += 16) {
						for (int dx = 0; dx < 64; dx += 16) {
							int bx = baseX + dx;
							int bz = baseZ + dz;
							Cell filtered = grid.getCellRaw(RegionFilterGrid.BORDER + dx, RegionFilterGrid.BORDER + dz);
							boolean isSubmerged = (filtered.terrain.isRiver() || filtered.terrain.isWetland() || filtered.terrain.isLake())
								&& filtered.height <= (filtered.riverWaterLevel > 0.0F ? filtered.riverWaterLevel : ctx.levels.water);
							if (isSubmerged) {
								continue;
							}

							float fc = CellSampler.Field.CONTINENT.read(filtered, ctx.heightmap);
							float fw = CellSampler.Field.WEIRDNESS.read(filtered, ctx.heightmap);
							Climate.TargetPoint ftp = Climate.target(filtered.temperature, filtered.moisture, fc, filtered.erosion, 0.0F, fw);
							ResourceKey<Biome> actualBiome = list.findValue(ftp);

							ctx.heightmap.apply(raw.reset(), bx, bz, true);
							boolean canMatch = filter.canMatchRaw(raw);
							totalChecked++;
							if (!canMatch) {
								totalRejected++;
								if (actualBiome.equals(targetBiome)) {
									falseNegatives++;
								}
							}
						}
					}
				}
			}
			assertEquals(0, falseNegatives, "Raw candidate bound must have zero false negatives");
			assertTrue(totalRejected > totalChecked * 0.90, "Raw candidate bound should reject >90% of non-matching cells");
		}
	}

	@Test
	@DisplayName("Verify river-only TargetFilter tile skip and raw hydrology precheck preserve all river tiles")
	void testRiverTargetFilterPreservesAllRiverTiles() {
		RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
		Preset preset = Presets.makeFTFDefault();
		PresetManager.PM.ingestFromPreset(preset);
		HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);

		try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, 1337L)) {
			BiomeLocateCellOptimization.TargetFilter riverFilter =
				BiomeLocateCellOptimization.TargetFilter.riverOnly(ctx);
			assertFalse(riverFilter.requiresClimateForRawPrecheck());

			Cell cell = new Cell();
			int missedTiles = 0;
			int riverTiles = 0;
			int totalTilesChecked = 0;
			int landTiles = 0;
			long center = ctx.heightmap.continent().getNearestCenter(-5000, 10000);
			int cx = PosUtil.unpackLeft(center);
			int cz = PosUtil.unpackRight(center);
			int centerRcx = cx >> 4;
			int centerRcz = cz >> 4;

			for (int rcz = centerRcz - 60; rcz <= centerRcz + 60; rcz += 4) {
				for (int rcx = centerRcx - 60; rcx <= centerRcx + 60; rcx += 4) {
					ParallelFirstSearch.Tile tile = new ParallelFirstSearch.Tile(rcx, rcz);
					boolean skipped = riverFilter.canSkipTile(tile);
					boolean hasHydrology = false;
					int minX = tile.minBlockX();
					int minZ = tile.minBlockZ();
					for (int dz = 0; dz < 64 && !hasHydrology; dz += 16) {
						for (int dx = 0; dx < 64; dx += 16) {
							ctx.heightmap.apply(cell.reset(), minX + dx, minZ + dz, false);
							if (cell.terrain.isRiver() || cell.terrain.isWetland() || cell.terrain.isLake()) {
								hasHydrology = true;
							}
						}
					}
					if (hasHydrology) {
						riverTiles++;
						if (skipped) {
							missedTiles++;
						}
					}
				}
			}
			assertTrue(riverTiles > 0, "Expected river tiles in test region");
			assertEquals(0, missedTiles, "River tile filter must never skip a tile containing hydrology");
		}
	}

	@FunctionalInterface
	private interface QuartBiomeGrid {
		Holder<Biome> biomeAt(int quartX, int quartZ);
	}

	private static BiomeLocateSearch.Query createSyntheticGridQuery(
		BiomeLocateSearch.SearchPlan plan,
		QuartBiomeGrid grid
	) {
		return new BiomeLocateSearch.Query() {
			@Override
			public BiomeLocateSearch.SearchPlan plan( java.util.function.Predicate<Holder<Biome>> target) {
				return plan;
			}

			@Override
			public boolean supportsParallelQueries() {
				return false;
			}

			@Override
			public ParallelFirstSearch.TileEvaluator<BiomeLocateSearch.Match> createTileSampler(
				java.util.function.Predicate<Holder<Biome>> target,
				BiomeLocateSearch.SearchPlan searchPlan,
				BlockPos origin
			) {
				return (tile, control) -> tile.findFirst(
					origin.getX(),
					origin.getZ(),
					searchPlan.resolution().horizontalStep(),
					control,
					(bx, bz) -> {
						if (bx != 16 || bz != 16) {
							return null;
						}
						int qx = QuartPos.fromBlock(bx);
						int qz = QuartPos.fromBlock(bz);
						Holder<Biome> center = grid.biomeAt(qx, qz);
						if (!target.test(center)) {
							return null;
						}
						int k = searchPlan.minBiomeAreaQuarts();
						for (int oz = 0; oz < k; oz++) {
							for (int ox = 0; ox < k; ox++) {
								if (!center.equals(grid.biomeAt(qx + ox, qz + oz))) {
									return null;
								}
							}
						}
						long dx = (long) bx - origin.getX();
						long dz = (long) bz - origin.getZ();
						return new BiomeLocateSearch.Match(new BlockPos(bx, origin.getY(), bz), center, dx * dx + dz * dz);
					}
				);
			}
		};
	}
}
