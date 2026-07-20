package raccoonman.reterraforged.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.surface.RTFSurfaceSystem;
import raccoonman.reterraforged.world.worldgen.surface.rule.StrataRule;

@Mixin(SurfaceSystem.class)
@Implements(@Interface(iface = RTFSurfaceSystem.class, prefix = RTFCommon.MOD_ID + "$RTFSurfaceSystem$"))
class MixinSurfaceSystem {
	private static final ResourceLocation GEOLOGY_RANDOM = RTFCommon.location("geology");
	private RandomState randomState;
	private Map<ResourceLocation, List<List<StrataRule.Layer>>> strata;

	@Inject(
			at = @At("TAIL"),
			method = "<init>"
	)
	public void SurfaceSystem(RandomState randomState, BlockState blockState, int i, PositionalRandomFactory positionalRandomFactory, CallbackInfo callback) {
		this.randomState = randomState;
		this.strata = new ConcurrentHashMap<>();
	}

	// INJECT AT HEAD to carve out rivers and lakes before surface rules run
	@Inject(
			method = "buildSurface",
			at = @At("HEAD")
	)
	private void onBuildSurface(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes, boolean useLegacyRandom, WorldGenerationContext context, final ChunkAccess chunk, NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource, CallbackInfo ci) {
		if ((Object) randomState instanceof RTFRandomState rtfRandomState) {
			GeneratorContext genCtx = rtfRandomState.generatorContext();
			if (genCtx != null) {
				this.reterraforged$placeRiverWater(chunk, biomeManager, genCtx);
				this.reterraforged$placeVolcanoLava(chunk, genCtx);
			}
		}
	}

	public List<List<StrataRule.Layer>> reterraforged$RTFSurfaceSystem$getOrCreateStrata(ResourceLocation name, Function<RandomSource, List<List<StrataRule.Layer>>> strata) {
		return this.strata.computeIfAbsent(name, (k) -> {
			PositionalRandomFactory factory = this.randomState.getOrCreateRandomFactory(GEOLOGY_RANDOM);
			return strata.apply(factory.fromHashOf(k));
		});
	}

	@Unique
	private void reterraforged$placeVolcanoLava(ChunkAccess chunk, GeneratorContext genCtx) {
		var chunkPos = chunk.getPos();
		var tile = genCtx.cache.provideAtChunk(chunkPos.x, chunkPos.z);
		var reader = tile.getChunkReader(chunkPos.x, chunkPos.z);
		var levels = genCtx.generator.getHeightmap().levels();
		float oceanLevel = levels.water;

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		BlockState lava = Blocks.LAVA.defaultBlockState();
		BlockState magma = Blocks.MAGMA_BLOCK.defaultBlockState();
		BlockState basalt = Blocks.BASALT.defaultBlockState();
		BlockState smoothBasalt = Blocks.SMOOTH_BASALT.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();

		int minY = chunk.getMinBuildHeight() + 2; // Bedrock level (~ -62 in 1.18+)

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int globalX = chunkPos.getMinBlockX() + localX;
				int globalZ = chunkPos.getMinBlockZ() + localZ;

				// Check proximity so twisted South/East walls aren't truncated by terrain bounds
				if (reterraforged$isNearVolcano(genCtx, globalX, globalZ)) {
					Cell cell = reader.getCell(localX, localZ);
					int surfaceY = levels.scale(cell.height);

					// Maximum height for liquid lava inside the conduit
					int lavaY = levels.scale(
							(ContinentalHydrology.getComplexWaterHeight(
									cell.waterTable,
									cell.globalContinentScale,
									cell.continentSizeModifier)
							) + oceanLevel
					);

					double totalDepth = Math.max(1.0, surfaceY - minY);

					for (int y = surfaceY; y >= minY; y--) {
						double dy = surfaceY - y;
						double depthRatio = dy / totalDepth; // 0.0 at surface -> 1.0 at bedrock

						// Zero-centered symmetric winding (0.0 offset at surface dy = 0)
						double offsetX = Math.sin(dy * 0.025) * 4.5 + Math.sin(dy * 0.011) * 2.0;
						double offsetZ = Math.sin(dy * 0.020) * 4.5 + Math.sin(dy * 0.014) * 2.0;

						int sampleX = (int) Math.round(globalX - offsetX);
						int sampleZ = (int) Math.round(globalZ - offsetZ);

						// Continuous distance-weighted factor from 0.0 (outside) to 1.0 (center)
						double pipeFactor = reterraforged$getSmoothPipeFactor(genCtx, sampleX, sampleZ);

						if (pipeFactor > 0.05) {
							// Low-amplitude 3D noise for natural wall texture
							double noise3d = Math.sin(globalX * 0.12 + y * 0.08) * Math.cos(globalZ * 0.12 - y * 0.08) * 0.08;
							double tubeValue = pipeFactor + noise3d;

							// Tapering thresholds for smooth narrowing toward bedrock
							double coreThreshold  = 0.65 + (depthRatio * 0.25); // 0.65 (surface) -> 0.90 (bedrock)
							double innerThreshold = 0.40 + (depthRatio * 0.10); // 0.40 (surface) -> 0.50 (bedrock)
							double outerThreshold = 0.15;                       // Outer basalt boundary

							pos.set(globalX, y, globalZ);

							// 1. Core Conduit (Liquid Lava below lavaY, Air above lavaY)
							if (tubeValue >= coreThreshold) {
								if (y <= lavaY) {
									chunk.setBlockState(pos, lava, false);
									if (y == lavaY) {
										chunk.markPosForPostprocessing(pos);
									}
								} else {
									chunk.setBlockState(pos, air, false);
								}
							}
							// 2. Inner Wall: Magma & Basalt (Runs all the way to surfaceY)
							else if (tubeValue >= innerThreshold) {
								int hash = (globalX * 3122011) ^ (y * 11687) ^ (globalZ * 9399223);
								BlockState state = ((hash & 3) == 0) ? basalt : magma;
								chunk.setBlockState(pos, state, false);
							}
							// 3. Outer Crust: Basalt & Smooth Basalt (Runs all the way to surfaceY)
							else if (tubeValue >= outerThreshold) {
								int hash = (globalX * 3122011) ^ (y * 11687) ^ (globalZ * 9399223);
								BlockState state = ((hash & 1) == 0) ? basalt : smoothBasalt;
								chunk.setBlockState(pos, state, false);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Checks if a block column is within twist/shell distance of a volcano.
	 */
	@Unique
	private boolean reterraforged$isNearVolcano(GeneratorContext genCtx, int globalX, int globalZ) {
		int[] dx = {0, 0, 0, 12, -12};
		int[] dz = {0, 12, -12, 0, 0};

		for (int i = 0; i < 5; i++) {
			int sx = globalX + dx[i];
			int sz = globalZ + dz[i];
			var tile = genCtx.cache.provideAtChunk(sx >> 4, sz >> 4);
			var reader = tile.getChunkReader(sx >> 4, sz >> 4);
			Terrain terrain = reader.getCell(sx & 0xF, sz & 0xF).terrain;
			if (terrain == TerrainType.VOLCANO_PIPE || terrain == TerrainType.VOLCANO) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Samples a 5x5 distance-weighted region to produce a smooth scalar value (0.0 to 1.0).
	 */
	@Unique
	private double reterraforged$getSmoothPipeFactor(GeneratorContext genCtx, int centerX, int centerZ) {
		double totalWeight = 0.0;
		double pipeWeight = 0.0;

		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int sx = centerX + dx;
				int sz = centerZ + dz;

				double weight = 1.0 / (1.0 + (dx * dx + dz * dz));
				totalWeight += weight;

				var tile = genCtx.cache.provideAtChunk(sx >> 4, sz >> 4);
				var reader = tile.getChunkReader(sx >> 4, sz >> 4);
				if (reader.getCell(sx & 0xF, sz & 0xF).terrain == TerrainType.VOLCANO_PIPE) {
					pipeWeight += weight;
				}
			}
		}

		return pipeWeight / totalWeight;
	}

	@Unique
	private void reterraforged$placeRiverWater(ChunkAccess chunk, BiomeManager biomeManager, GeneratorContext genCtx) {
		var chunkPos = chunk.getPos();
		var tile = genCtx.cache.provideAtChunk(chunkPos.x, chunkPos.z);
		var reader = tile.getChunkReader(chunkPos.x, chunkPos.z);
		var levels = genCtx.generator.getHeightmap().levels();

		float oceanLevel = levels.water;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockState flowingWater = water.setValue(BlockStateProperties.LEVEL, 1);
		BlockState stone = Blocks.STONE.defaultBlockState();

		// Iterate over the 16x16 chunk exactly once
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int globalX = chunkPos.getMinBlockX() + localX;
				int globalZ = chunkPos.getMinBlockZ() + localZ;

				raccoonman.reterraforged.world.worldgen.cell.Cell cell = reader.getCell(localX, localZ);
				boolean isWaterCell = (cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland()) && cell.riverWaterLevel > 0;
				int scaledY = levels.scale(cell.height);

				if (isWaterCell) {
					int waterY = levels.scale(
							(ContinentalHydrology.getComplexWaterHeight(
									cell.waterTable,
									cell.globalContinentScale,
									cell.continentSizeModifier)
							) + oceanLevel
					);

					if (waterY < levels.scale(levels.water)) waterY = levels.scale(levels.water);

					if (waterY > scaledY) {
						boolean isTransitionColumn = false;
						boolean multiBlockDrop = false;
						int lowestNeighborWaterY = waterY;

						int[] dx = {0, 0, 1, -1};
						int[] dz = {-1, 1, 0, 0};

						// Check neighbors to determine if we are an edge/waterfall
						for (int i = 0; i < 4; i++) {
							int nx = globalX + dx[i];
							int nz = globalZ + dz[i];

							var neighborTile = genCtx.cache.provideAtChunk(nx >> 4, nz >> 4);
							var neighborReader = neighborTile.getChunkReader(nx >> 4, nz >> 4);
							var neighborCell = neighborReader.getCell(nx & 0xF, nz & 0xF);

							// Only consider the neighbor's water height if it is actually a water cell
							boolean nIsWaterCell = (neighborCell.terrain.isRiver() || neighborCell.terrain.isLake() || neighborCell.terrain.isWetland()) && neighborCell.riverWaterLevel > 0;
							if (nIsWaterCell) {
								int nWaterY = levels.scale(
										(ContinentalHydrology.getComplexWaterHeight(
												neighborCell.waterTable,
												neighborCell.globalContinentScale,
												neighborCell.continentSizeModifier)
										) + levels.water
								);

								if (nWaterY < levels.scale(levels.water)) nWaterY = levels.scale(levels.water);

								if (nWaterY < waterY) {
									isTransitionColumn = true;
									lowestNeighborWaterY = Math.min(lowestNeighborWaterY, nWaterY);
									if (waterY - nWaterY > 1) {
										multiBlockDrop = true;
									}
								}
							}
						}

						// adjust this column to be averaged so it makes the waterfalls more natural like.
						if (isTransitionColumn && multiBlockDrop) {
							scaledY = (scaledY + lowestNeighborWaterY) / 2;
						}

						// Build the water column in our OWN chunk down to the needed depth
						for (int wy = scaledY + 1; wy <= waterY; wy++) {
							boolean isTopBlock = wy == waterY;
							pos.set(globalX, wy, globalZ);

							if (isTopBlock && isTransitionColumn && multiBlockDrop) {
								chunk.setBlockState(pos, water, false);
								chunk.markPosForPostprocessing(pos);
							}
							else if (isTopBlock && isTransitionColumn && !multiBlockDrop) {
								if (biomeManager.getBiome(pos).value().coldEnoughToSnow(pos)) {
									// Place Ice instead of liquid water to keep frozen rivers continuous
									chunk.setBlockState(pos, Blocks.ICE.defaultBlockState(), false);
								} else {
									chunk.setBlockState(pos, flowingWater, false);
								}
							}
							else if (!isTopBlock && multiBlockDrop) {
								// If we are dropping multiple blocks, fill the gap down to the lowest neighbor with water instead of stone
								if (wy > lowestNeighborWaterY) {
									chunk.setBlockState(pos, flowingWater, false);
									chunk.markPosForPostprocessing(pos);
								} else {
									chunk.setBlockState(pos, stone, false);
								}
							}
							else {
								chunk.setBlockState(pos, water, false);
								if (isTopBlock) {
									chunk.markPosForPostprocessing(pos);
								}
							}
						}
					}
				} else {

					// GASKET LOGIC
					int oceanY = levels.scale(levels.water);

					// Only run gasket logic if this land cell is above ocean level
					if (scaledY >= oceanY) {

						// GASKET LOGIC: Soft fade using riverMask
						int maxNeighborWaterY = scaledY;

						// Check neighbors for water and record the strongest mask influence
						int[] dx = {0, 0, 1, -1};
						int[] dz = {-1, 1, 0, 0};
						for (int i = 0; i < 4; i++) {
							int nx = globalX + dx[i];
							int nz = globalZ + dz[i];
							var neighborTile = genCtx.cache.provideAtChunk(nx >> 4, nz >> 4);
							var neighborReader = neighborTile.getChunkReader(nx >> 4, nz >> 4);
							var neighborCell = neighborReader.getCell(nx & 0xF, nz & 0xF);

							if ((neighborCell.terrain.isRiver() || neighborCell.terrain.isLake())) {
								int nWaterY = levels.scale(
									(ContinentalHydrology.getComplexWaterHeight(
											neighborCell.waterTable,
											neighborCell.globalContinentScale,
											neighborCell.continentSizeModifier)
									) + oceanLevel
								);

								if (nWaterY > maxNeighborWaterY) {
									maxNeighborWaterY = nWaterY;
								}
							}
						}

						// If in river zone and lower than water table, we shouldn't be.
						int waterTableCeil = levels.scale(
							(ContinentalHydrology.getComplexWaterHeight(
									cell.waterTable,
									cell.globalContinentScale,
									cell.continentSizeModifier)
								) + oceanLevel
							);

						boolean isInRiverZone = cell.riverZone == RiverCarverSettings.RiverZone.Banks
								|| cell.riverZone == RiverCarverSettings.RiverZone.ValleyFloor
								|| cell.riverZone == RiverCarverSettings.RiverZone.ValleyFadeout;
						boolean isLowerThanWaterTable = scaledY < waterTableCeil;
						boolean isAboveOcean = scaledY > oceanY;

						if (isInRiverZone && isLowerThanWaterTable && isAboveOcean)  {
							for (int wy = scaledY + 1; wy <= waterTableCeil; wy++) {
								pos.set(globalX, wy, globalZ);
								chunk.setBlockState(pos, stone, false);
							}
						}

						// If a neighboring water block is higher than our terrain, build a stone pillar up to match it.
						if (maxNeighborWaterY > scaledY) {
							for (int wy = scaledY + 1; wy <= maxNeighborWaterY; wy++) {
								pos.set(globalX, wy, globalZ);
								chunk.setBlockState(pos, stone, false);
							}
						}
					}
				}
			}
		}
	}
}