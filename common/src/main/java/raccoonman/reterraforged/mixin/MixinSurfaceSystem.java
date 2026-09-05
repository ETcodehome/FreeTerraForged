package raccoonman.reterraforged.mixin;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache;
import raccoonman.reterraforged.world.worldgen.surface.RTFSurfaceSystem;
import raccoonman.reterraforged.world.worldgen.surface.rule.StrataRule;

@Mixin(SurfaceSystem.class)
@Implements(@Interface(iface = RTFSurfaceSystem.class, prefix = RTFCommon.MOD_ID + "$RTFSurfaceSystem$"))
class MixinSurfaceSystem {
	private static final ResourceLocation GEOLOGY_RANDOM = RTFCommon.location("geology");
	private static final int[] NEIGHBOR_X = {0, 0, 1, -1};
	private static final int[] NEIGHBOR_Z = {-1, 1, 0, 0};
	private RandomState randomState;
	private volatile Map<ResourceLocation, List<List<StrataRule.Layer>>> strata;

	@Inject(
			at = @At("TAIL"),
			method = "<init>"
	)
	public void SurfaceSystem(RandomState randomState, BlockState blockState, int i, PositionalRandomFactory positionalRandomFactory, CallbackInfo callback) {
		this.randomState = randomState;
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
				this.reterraforged$placeRiverWater(chunk, biomeManager, genCtx, noiseChunk);
			}
		}
	}

	public List<List<StrataRule.Layer>> reterraforged$RTFSurfaceSystem$getOrCreateStrata(ResourceLocation name, Function<RandomSource, List<List<StrataRule.Layer>>> strata) {
		return this.reterraforged$strata().computeIfAbsent(name, (k) -> {
			PositionalRandomFactory factory = this.randomState.getOrCreateRandomFactory(GEOLOGY_RANDOM);
			return strata.apply(factory.fromHashOf(k));
		});
	}

	@Unique
	private Map<ResourceLocation, List<List<StrataRule.Layer>>> reterraforged$strata() {
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
	private void reterraforged$placeRiverWater(ChunkAccess chunk, BiomeManager biomeManager, GeneratorContext genCtx, NoiseChunk noiseChunk) {
		var chunkPos = chunk.getPos();
		TileCache cache = java.util.Objects.requireNonNull(genCtx.cache, "FTF surface tile cache");
		Tile.Chunk ownedChunk = noiseChunk instanceof raccoonman.reterraforged.world.worldgen.densityfunction.tile.NoiseChunkTileOwner owner
			? owner.reterraforged$currentTileChunk()
			: null;
		try (SurfaceTiles tiles = new SurfaceTiles(cache, chunkPos.x, chunkPos.z, ownedChunk)) {
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
