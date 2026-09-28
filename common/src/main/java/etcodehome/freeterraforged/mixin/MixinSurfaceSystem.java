package etcodehome.freeterraforged.mixin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.core.Direction;
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
import net.minecraft.world.level.ChunkPos;
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
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.NoiseChunkTileOwner;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.TileCache;
import etcodehome.freeterraforged.world.worldgen.surface.FTFSurfaceSystem;
import etcodehome.freeterraforged.world.worldgen.surface.rule.StrataRule;

@Mixin(SurfaceSystem.class)
@Implements(@Interface(iface = FTFSurfaceSystem.class, prefix = FTFCommon.MOD_ID + "$FTFSurfaceSystem$"))
class MixinSurfaceSystem {
	private static final ResourceLocation GEOLOGY_RANDOM = FTFCommon.location("geology");
	private static final int[] NEIGHBOR_X = {0, 0, 1, -1};
	private static final int[] NEIGHBOR_Z = {-1, 1, 0, 0};

	private static final int VOLCANO_PAD = 16;
	private static final int VOLCANO_GRID = 16 + VOLCANO_PAD * 2;

	private RandomState randomState;
	private volatile Map<ResourceLocation, List<List<StrataRule.Layer>>> strata;

	@Inject(
			at = @At("TAIL"),
			method = "<init>"
	)
	public void SurfaceSystem(RandomState randomState, BlockState blockState, int i, PositionalRandomFactory positionalRandomFactory, CallbackInfo callback) {
		this.randomState = randomState;
	}

	// INJECT AT HEAD to carve out rivers, lakes and volcano conduits before surface rules run
	@Inject(
			method = "buildSurface",
			at = @At("HEAD")
	)
	private void onBuildSurface(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes, boolean useLegacyRandom, WorldGenerationContext context, final ChunkAccess chunk, NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource, CallbackInfo ci) {
		if ((Object) randomState instanceof FTFRandomState ftfRandomState) {
			GeneratorContext genCtx = ftfRandomState.generatorContext();
			if (genCtx != null) {
				ChunkPos chunkPos = chunk.getPos();
				TileCache cache = Objects.requireNonNull(genCtx.cache, "FTF surface tile cache");
				Tile.Chunk ownedChunk = noiseChunk instanceof NoiseChunkTileOwner owner
						? owner.freeterraforged$currentTileChunk()
						: null;
				// One set of tile leases is shared by every pass so each tile is acquired at most once.
				try (SurfaceTiles tiles = new SurfaceTiles(cache, chunkPos.x, chunkPos.z, ownedChunk)) {
					this.freeterraforged$placeRiverWater(chunk, biomeManager, genCtx, tiles);
					this.freeterraforged$placeVolcanoLava(chunk, genCtx, tiles);
				}
			}
		}
	}

	public List<List<StrataRule.Layer>> freeterraforged$FTFSurfaceSystem$getOrCreateStrata(ResourceLocation name, Function<RandomSource, List<List<StrataRule.Layer>>> strata) {
		return this.freeterraforged$strata().computeIfAbsent(name, (k) -> {
			PositionalRandomFactory factory = this.randomState.getOrCreateRandomFactory(GEOLOGY_RANDOM);
			return strata.apply(factory.fromHashOf(k));
		});
	}

	@Unique
	private Map<ResourceLocation, List<List<StrataRule.Layer>>> freeterraforged$strata() {
		Map<ResourceLocation, List<List<StrataRule.Layer>>> current = this.strata;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			current = this.strata;
			if (current == null) {
				current = new ConcurrentHashMap<>();
				this.strata = current;
			}
		}
		return current;
	}

	@Unique
	private void freeterraforged$placeVolcanoLava(ChunkAccess chunk, GeneratorContext genCtx, SurfaceTiles tiles) {
		ChunkPos chunkPos = chunk.getPos();

		final int pad = VOLCANO_PAD;
		final int gridSize = VOLCANO_GRID; // 48x48 grid

		boolean[] pipe = new boolean[gridSize * gridSize];
		int[] pipeX = new int[gridSize * gridSize];
		int[] pipeZ = new int[gridSize * gridSize];
		int pipeCount = 0;

		// 1. Snapshot surrounding 3x3 chunks strictly for VOLCANO_PIPE conduit locations
		for (int cx = -1; cx <= 1; cx++) {
			for (int cz = -1; cz <= 1; cz++) {
				var neighborReader = tiles.reader(chunkPos.x + cx, chunkPos.z + cz);
				int baseGx = (cx + 1) * 16;
				int baseGz = (cz + 1) * 16;

				for (int i = 0; i < 16; i++) {
					int gx = baseGx + i;
					for (int j = 0; j < 16; j++) {
						int gz = baseGz + j;
						Terrain terrain = neighborReader.getCell(i, j).terrain;
						if (terrain == TerrainType.VOLCANO_PIPE) {
							pipe[gx * gridSize + gz] = true;
							pipeX[pipeCount] = gx;
							pipeZ[pipeCount] = gz;
							pipeCount++;
						}
					}
				}
			}
		}

		if (pipeCount == 0) {
			return;
		}

		var reader = tiles.reader(chunkPos.x, chunkPos.z);
		var levels = genCtx.generator.getHeightmap().levels();

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		BlockState lava = Blocks.LAVA.defaultBlockState();
		BlockState magma = Blocks.MAGMA_BLOCK.defaultBlockState();
		BlockState basalt = Blocks.BASALT.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
		BlockState smoothBasalt = Blocks.SMOOTH_BASALT.defaultBlockState();
		BlockState blackstone = Blocks.BLACKSTONE.defaultBlockState();
		BlockState tuff = Blocks.TUFF.defaultBlockState();
		BlockState gravel = Blocks.GRAVEL.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();

		int minY = chunk.getMinBuildHeight() + 2;
		int maxY = chunk.getMaxBuildHeight() - 1;

		// Conduit winding offsets calculated per depth level
		int maxDepth = Math.max(1, maxY - minY + 1);
		double[] windX = new double[maxDepth];
		double[] windZ = new double[maxDepth];
		for (int d = 0; d < maxDepth; d++) {
			windX[d] = Math.sin(d * 0.025) * 4.5 + Math.sin(d * 0.011) * 2.0;
			windZ[d] = Math.sin(d * 0.020) * 4.5 + Math.sin(d * 0.014) * 2.0;
		}

		// Tight 7x7 Gaussian kernel for underground conduit pipe shaft
		int kRadius = 3;
		int kDiameter = kRadius * 2 + 1;
		double[] kernel = new double[kDiameter * kDiameter];
		double kernelTotal = 0.0;
		int k = 0;
		for (int dx = -kRadius; dx <= kRadius; dx++) {
			for (int dz = -kRadius; dz <= kRadius; dz++) {
				double distSq = dx * dx + dz * dz;
				if (distSq <= kRadius * kRadius + 0.25) {
					kernel[k] = Math.exp(-distSq / 3.5);
					kernelTotal += kernel[k];
				} else {
					kernel[k] = 0.0;
				}
				k++;
			}
		}

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int gx = localX + pad;
				int gz = localZ + pad;

				int globalX = chunkPos.getMinBlockX() + localX;
				int globalZ = chunkPos.getMinBlockZ() + localZ;

				// Compute exact distance to the central volcano pipe conduit
				double minDistSq = 1e9;
				for (int p = 0; p < pipeCount; p++) {
					double dx = gx - pipeX[p];
					double dz = gz - pipeZ[p];
					double dSq = dx * dx + dz * dz;
					if (dSq < minDistSq) {
						minDistSq = dSq;
					}
				}
				double distToPipe = Math.sqrt(minDistSq);

				// Warp distance field with 2D noise to break geometric circularity
				double warp = Math.sin(globalX * 0.08 + globalZ * 0.06) * 4.0
						+ Math.cos(globalX * 0.04 - globalZ * 0.09) * 3.0;
				double effectiveDist = Math.max(0.0, distToPipe + warp);

				// Continuous smoothstep falloff field (1.0 at rim -> 0.0 at 38 blocks out)
				double volcanoIntensity = 0.0;
				if (effectiveDist <= 6.0) {
					volcanoIntensity = 1.0;
				} else if (effectiveDist < 38.0) {
					double t = (effectiveDist - 6.0) / (38.0 - 6.0);
					volcanoIntensity = 1.0 - (t * t * (3.0 - 2.0 * t)); // Hermite smoothstep
				}

				Cell cell = reader.getCell(localX, localZ);
				int surfaceY = Math.min(levels.scale(cell.height), maxY);

				// Lava level set to exactly 10 blocks below mouth level
				int lavaY = Math.max(minY + 1, surfaceY - 10);
				double totalDepth = Math.max(1.0, surfaceY - minY);

				for (int y = surfaceY; y >= minY; y--) {
					int depth = surfaceY - y;
					double depthRatio = (double) depth / totalDepth; // 0.0 at surface -> 1.0 at bedrock

					// Smooth power-curve taper: 1.0 (full width at surface) down to 0.0 at bedrock
					double widthScale = Math.pow(1.0 - depthRatio, 0.85);

					int sampleX = (int) Math.round(gx - windX[depth]);
					int sampleZ = (int) Math.round(gz - windZ[depth]);

					double rawPipeFactor = freeterraforged$smoothPipeFactor(
							pipe, sampleX, sampleZ, kernel, kernelTotal, kRadius, gridSize
					);

					double pipeFactor = rawPipeFactor * widthScale;

					if (pipeFactor <= 0.03 && (volcanoIntensity <= 0.01 || depth > 3)) {
						continue;
					}

					// 3D noise scaled with width for wall roughness
					double wallNoise = (Math.sin(globalX * 0.12 + y * 0.08) * Math.cos(globalZ * 0.12 - y * 0.08) * 0.07
							+ Math.sin(globalX * 0.25 - y * 0.15 + globalZ * 0.25) * 0.03) * widthScale;
					double tubeValue = pipeFactor + wallNoise;

					double coreThreshold = 0.50;  // Open shaft / liquid core
					double innerThreshold = 0.30; // Hot inner wall lining
					double outerThreshold = 0.10; // Outer basalt crust

					pos.set(globalX, y, globalZ);

					if (tubeValue >= coreThreshold) {
						// Core shaft: Air pit for top 10 blocks, liquid lava pool below lavaY
						if (y <= lavaY) {
							chunk.setBlockState(pos, lava, false);
							if (y == lavaY) {
								chunk.markPosForPostprocessing(pos);
							}
						} else {
							chunk.setBlockState(pos, air, false);
						}
					} else if (tubeValue >= innerThreshold) {
						// Inner wall: Magma veins, blackstone strata, and vertical basalt columns
						double veinNoise = Math.sin(globalX * 0.18 + y * 0.14 + globalZ * 0.18)
								+ Math.cos(globalX * 0.09 - y * 0.22 + globalZ * 0.09);
						if (veinNoise > 0.4) {
							chunk.setBlockState(pos, magma, false);
						} else if (veinNoise < -0.3) {
							chunk.setBlockState(pos, blackstone, false);
						} else {
							chunk.setBlockState(pos, basalt, false);
						}
					} else if (tubeValue >= outerThreshold) {
						// Outer crust: Transitions into basalt and smooth basalt
						double crustNoise = Math.sin(globalX * 0.21 - y * 0.11 + globalZ * 0.19);
						chunk.setBlockState(pos, crustNoise > 0.1 ? basalt : smoothBasalt, false);
					} else if (depth <= 3 && volcanoIntensity > 0.01) {
						// BROAD SURFACE DITHERED FADE-IN (No Y-dependence, no grid-aligned stripes)
						double maskNoise = freeterraforged$rotatedNoise2D(globalX, globalZ, 0.11);

						// Stochastic noise dithering seamlessly blends ash into standard biome blocks
						if (maskNoise < volcanoIntensity) {
							double varNoise = freeterraforged$rotatedNoise2D(globalX + 500, globalZ - 300, 0.21);

							if (volcanoIntensity > 0.70) {
								// Crater rim & upper slopes: Magma, Blackstone, Basalt
								if (varNoise > 0.65) {
									chunk.setBlockState(pos, magma, false);
								} else if (varNoise > 0.30) {
									chunk.setBlockState(pos, blackstone, false);
								} else {
									chunk.setBlockState(pos, basalt, false);
								}
							} else if (volcanoIntensity > 0.35) {
								// Mid slopes: Blackstone, Tuff, Basalt
								if (varNoise > 0.60) {
									chunk.setBlockState(pos, blackstone, false);
								} else if (varNoise > 0.30) {
									chunk.setBlockState(pos, tuff, false);
								} else {
									chunk.setBlockState(pos, basalt, false);
								}
							} else {
								// Outer apron scatter: Tuff & Gravel ash patches
								if (varNoise > 0.50) {
									chunk.setBlockState(pos, tuff, false);
								} else {
									chunk.setBlockState(pos, gravel, false);
								}
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Rotated 2D noise generator normalized to [0.0, 1.0].
	 * Completely eliminates vertical Y-contour rings and X/Z grid-aligned stripe artifacts.
	 */
	@Unique
	private static double freeterraforged$rotatedNoise2D(int x, int z, double scale) {
		double nx = x * scale;
		double nz = z * scale;
		// 30-degree rotation matrix to break grid alignment
		double rx = nx * 0.866025 - nz * 0.5;
		double rz = nx * 0.5 + nz * 0.866025;

		double sin1 = Math.sin(rx);
		double cos1 = Math.cos(rz);
		double sin2 = Math.sin(rx * 0.6 + rz * 1.4);
		double cos2 = Math.cos(rx * 1.5 - rz * 0.7);

		double raw = (sin1 * cos1 + sin2 * 0.5 + cos2 * 0.25);
		return (raw + 1.75) / 3.5;
	}

	/**
	 * Safe 2D distance-weighted kernel average that prevents row-wrapping across chunk bounds.
	 */
	@Unique
	private static double freeterraforged$smoothPipeFactor(
			boolean[] pipe, int gridX, int gridZ, double[] kernel, double kernelTotal, int kRadius, int gridSize
	) {
		double pipeWeight = 0.0;
		int k = 0;
		for (int dx = -kRadius; dx <= kRadius; dx++) {
			int nx = gridX + dx;
			if (nx < 0 || nx >= gridSize) {
				k += (kRadius * 2 + 1);
				continue;
			}
			int row = nx * gridSize;
			for (int dz = -kRadius; dz <= kRadius; dz++) {
				int nz = gridZ + dz;
				if (nz >= 0 && nz < gridSize) {
					if (pipe[row + nz]) {
						pipeWeight += kernel[k];
					}
				}
				k++;
			}
		}
		return pipeWeight / kernelTotal;
	}

	@Unique
	private void freeterraforged$placeRiverWater(ChunkAccess chunk, BiomeManager biomeManager, GeneratorContext genCtx, SurfaceTiles tiles) {
		var chunkPos = chunk.getPos();
		var reader = tiles.reader(chunkPos.x, chunkPos.z);
		var levels = genCtx.generator.getHeightmap().levels();

		float oceanLevel = levels.water;
		int oceanY = levels.scale(oceanLevel);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockState flowingWater = water.setValue(BlockStateProperties.LEVEL, 1);
		BlockState stone = Blocks.STONE.defaultBlockState();

		// Iterate over the 16x16 chunk exactly once
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int globalX = chunkPos.getMinBlockX() + localX;
				int globalZ = chunkPos.getMinBlockZ() + localZ;

				Cell cell = reader.getCell(localX, localZ);
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

					if (waterY < oceanY) waterY = oceanY;

					if (waterY > scaledY) {
						boolean isTransitionColumn = false;
						boolean multiBlockDrop = false;
						int lowestNeighborWaterY = waterY;

						// Check neighbors to determine if we are an edge/waterfall
						for (int i = 0; i < 4; i++) {
							int nx = globalX + NEIGHBOR_X[i];
							int nz = globalZ + NEIGHBOR_Z[i];

							var neighborReader = tiles.reader(nx >> 4, nz >> 4);
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

								if (nWaterY < oceanY) nWaterY = oceanY;

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
					// Only run gasket logic if this land cell is above ocean level
					if (scaledY >= oceanY) {

						// GASKET LOGIC: Soft fade using riverMask
						int maxNeighborWaterY = scaledY;

						// Check neighbors for water and record the strongest mask influence
						for (int i = 0; i < 4; i++) {
							int nx = globalX + NEIGHBOR_X[i];
							int nz = globalZ + NEIGHBOR_Z[i];
							var neighborReader = tiles.reader(nx >> 4, nz >> 4);
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

	@Unique
	private static final class SurfaceTiles implements AutoCloseable {
		private final TileCache cache;
		private final int ownedChunkX;
		private final int ownedChunkZ;
		private final Tile.Chunk ownedChunk;
		private final Map<Long, TileCache.Lease> leases = new HashMap<>(4);

		private SurfaceTiles(TileCache cache, int ownedChunkX, int ownedChunkZ, Tile.Chunk ownedChunk) {
			this.cache = cache;
			this.ownedChunkX = ownedChunkX;
			this.ownedChunkZ = ownedChunkZ;
			this.ownedChunk = ownedChunk;
		}

		private Tile.Chunk reader(int chunkX, int chunkZ) {
			if (this.ownedChunk != null && chunkX == this.ownedChunkX && chunkZ == this.ownedChunkZ) {
				return this.ownedChunk;
			}
			int tileX = this.cache.chunkToTile(chunkX);
			int tileZ = this.cache.chunkToTile(chunkZ);
			long key = ChunkPos.asLong(tileX, tileZ);
			TileCache.Lease lease = this.leases.get(key);
			if (lease == null) {
				lease = this.cache.acquire(tileX, tileZ);
				this.leases.put(key, lease);
			}
			return lease.tile().getChunkReader(chunkX, chunkZ);
		}

		@Override
		public void close() {
			Throwable failure = null;
			for (TileCache.Lease lease : this.leases.values()) {
				try {
					lease.close();
				} catch (RuntimeException | Error cleanupFailure) {
					if (failure == null) {
						failure = cleanupFailure;
					} else {
						failure.addSuppressed(cleanupFailure);
					}
				}
			}
			this.leases.clear();
			if (failure instanceof RuntimeException runtimeFailure) {
				throw runtimeFailure;
			}
			if (failure instanceof Error error) {
				throw error;
			}
		}
	}
}