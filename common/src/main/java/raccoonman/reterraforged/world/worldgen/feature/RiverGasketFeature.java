package raccoonman.reterraforged.world.worldgen.feature;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings;

import java.util.Arrays;

public class RiverGasketFeature extends Feature<NoneFeatureConfiguration> {

    // Pre-allocated array of 256 Cells per thread (zero allocation during worldgen)
    private static final ThreadLocal<Cell[]> CHUNK_CELLS = ThreadLocal.withInitial(() -> {
        Cell[] cells = new Cell[256];
        for (int i = 0; i < 256; i++) {
            cells[i] = new Cell();
        }
        return cells;
    });

    // Holds the indices (0-255) of columns that actually contain rivers
    private static final ThreadLocal<int[]> RIVER_INDICES = ThreadLocal.withInitial(() -> new int[256]);

    private static final ThreadLocal<Cell> NEIGHBOUR_CELL = ThreadLocal.withInitial(Cell::new);
    private static final ThreadLocal<int[]> WATER_Y_CACHE = ThreadLocal.withInitial(() -> new int[40 * 40]);
    private static final ThreadLocal<Long2ObjectOpenHashMap<BlockState>> PAINT_CACHE =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);
    private static final ThreadLocal<PosHolder> POS_HOLDER = ThreadLocal.withInitial(PosHolder::new);

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static class PosHolder {
        final BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos belowNeighbor = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos testAbove = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos testSide = new BlockPos.MutableBlockPos();
    }

    public RiverGasketFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> placeContext) {
        WorldGenLevel level = placeContext.level();
        RandomState randomState = level.getLevel().getChunkSource().randomState();

        if (!((Object) randomState instanceof RTFRandomState rtfRandomState)) {
            return false;
        }

        GeneratorContext generatorContext = rtfRandomState.generatorContext();
        if (generatorContext == null) {
            return false;
        }

        BlockPos origin = placeContext.origin();
        int minBlockX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()));
        int minBlockZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()));

        Cell[] chunkCells = CHUNK_CELLS.get();
        int[] riverIndices = RIVER_INDICES.get();
        int riverCount = 0;

        // --- SINGLE-PASS CELL EVALUATION & INDEX FILTER ---
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = x + (z * 16);
                int worldX = minBlockX + x;
                int worldZ = minBlockZ + z;

                Cell cell = chunkCells[index].reset();
                generatorContext.lookup.applyCell(cell, worldX, worldZ, false, false);

                if (cell.riverZone != RiverCarverSettings.RiverZone.None
                        && cell.riverZone != RiverCarverSettings.RiverZone.ValleyFadeout) {
                    riverIndices[riverCount++] = index;
                }
            }
        }

        // Fast exit for ~80%+ of non-river chunks before touching secondary caches or world blocks
        if (riverCount == 0) {
            return false;
        }

        Levels levels = generatorContext.lookup.getHeightmap().levels();
        Cell neighborCell = NEIGHBOUR_CELL.get();
        PosHolder pos = POS_HOLDER.get();

        BlockState fallbackState = Blocks.STONE.defaultBlockState();
        BlockState defaultWaterState = Blocks.WATER.defaultBlockState();
        float oceanHeightOffset = levels.water;

        // Reset the lazy water y cache
        // Fast primitive fill instead of a compulsory 1600 evaluation loop otherwise
        int[] neighborWaterYCache = WATER_Y_CACHE.get();
        Arrays.fill(neighborWaterYCache, Integer.MIN_VALUE);

        Long2ObjectOpenHashMap<BlockState> paintCache = PAINT_CACHE.get();
        paintCache.clear();

        // Process only river columns here
        for (int i = 0; i < riverCount; i++) {
            int index = riverIndices[i];
            int x = index & 15;
            int z = index >> 4;

            int blockX = minBlockX + x;
            int blockZ = minBlockZ + z;

            // Reuse any already evaluated cell directly from the cache
            Cell cell = chunkCells[index];

            float targetWaterLevel = (ContinentalHydrology.getComplexWaterHeight(
                    cell.waterTable,
                    cell.globalContinentScale,
                    cell.continentSizeModifier)
            ) + oceanHeightOffset;

            int localWaterY = levels.scale(targetWaterLevel);
            int currentFloorHeight = levels.scale(cell.height);

            int scanTopY = Math.max(localWaterY, currentFloorHeight);
            int scanBottomY = Math.min(localWaterY, currentFloorHeight) - 8;
            scanBottomY = Math.max(scanBottomY, levels.scale(levels.water));

            // Sample column structural block
            BlockState structuralState = null;
            int columnTopY = level.getChunk(origin).getHeight(
                    Heightmap.Types.OCEAN_FLOOR_WG,
                    blockX, blockZ
            );
            if (columnTopY >= level.getMinBuildHeight()) {
                pos.sample.set(blockX, columnTopY, blockZ);
                BlockState topState = level.getBlockState(pos.sample);
                if (!topState.isAir() && !topState.is(Blocks.CAVE_AIR) && !topState.is(Blocks.WATER)) {
                    structuralState = topState;
                }
            }
            if (structuralState == null) {
                structuralState = fallbackState;
            }

            for (int y = scanTopY; y >= scanBottomY; y--) {
                pos.current.set(blockX, y, blockZ);
                BlockState currentState = level.getBlockState(pos.current);

                boolean isWater = currentState.is(Blocks.WATER) && currentState.getFluidState().isSource();
                boolean isCarvedAir = currentState.isAir() || currentState.is(Blocks.CAVE_AIR);

                // Reconstruct carved water blocks in river channels
                if (isCarvedAir && y <= localWaterY && y >= currentFloorHeight) {
                    level.setBlock(pos.current, defaultWaterState, 2);
                    isWater = true;
                }

                if (isWater) {
                    // Gasket BELOW
                    pos.belowNeighbor.set(blockX, y - 1, blockZ);
                    BlockState belowState = level.getBlockState(pos.belowNeighbor);
                    if (belowState.isAir() || belowState.is(Blocks.CAVE_AIR)) {
                        level.setBlock(pos.belowNeighbor, structuralState, 2);
                    }

                    // Gasket HORIZONTAL
                    int radius = placeContext.random().nextInt(5) + 3;
                    int radiusSq = radius * radius;

                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (dx * dx + dz * dz <= radiusSq) {

                                int targetX = blockX + dx;
                                int targetZ = blockZ + dz;

                                int neighbourWaterY = getOrComputeWaterY(
                                        targetX, targetZ,
                                        minBlockX, minBlockZ,
                                        chunkCells,
                                        levels,
                                        oceanHeightOffset,
                                        generatorContext,
                                        neighborCell,
                                        neighborWaterYCache
                                );

                                if (y > neighbourWaterY) {
                                    continue;
                                }

                                pos.neighbor.set(targetX, y, targetZ);
                                BlockState neighborState = level.getBlockState(pos.neighbor);

                                if (neighborState.isAir() || neighborState.is(Blocks.CAVE_AIR)) {
                                    pos.belowNeighbor.set(targetX, y - 1, targetZ);
                                    BlockState belowNeighborState = level.getBlockState(pos.belowNeighbor);

                                    if (belowNeighborState.is(Blocks.WATER)) {
                                        continue;
                                    }

                                    BlockState finalPlacementState = structuralState;
                                    pos.testAbove.set(targetX, y + 1, targetZ);
                                    BlockState stateAbove = level.getBlockState(pos.testAbove);

                                    if (stateAbove.isAir() || stateAbove.is(Blocks.CAVE_AIR) || stateAbove.is(Blocks.WATER)) {
                                        long posHash = BlockPos.asLong(targetX, y, targetZ);

                                        if (paintCache.containsKey(posHash)) {
                                            finalPlacementState = paintCache.get(posHash);
                                        } else {
                                            BlockState foundPaint = structuralState;

                                            searchLoop:
                                            for (int dist = 1; dist <= 4; dist++) {
                                                for (Direction dir : HORIZONTAL_DIRECTIONS) {
                                                    pos.testSide.set(
                                                            targetX + (dir.getStepX() * dist),
                                                            y,
                                                            targetZ + (dir.getStepZ() * dist)
                                                    );
                                                    BlockState nearbyState = level.getBlockState(pos.testSide);

                                                    if (isTerrainPaint(nearbyState)) {
                                                        foundPaint = nearbyState;
                                                        break searchLoop;
                                                    }
                                                }
                                            }
                                            paintCache.put(posHash, foundPaint);
                                            finalPlacementState = foundPaint;
                                        }
                                    }

                                    level.setBlock(pos.neighbor, finalPlacementState, 2);
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Lazy lookup for neighbor water height.
     * Directly reuses CHUNK_CELLS for internal coordinates and caches external lookups on demand.
     */
    private static int getOrComputeWaterY(
            int targetX, int targetZ,
            int minBlockX, int minBlockZ,
            Cell[] chunkCells,
            Levels levels,
            float oceanHeightOffset,
            GeneratorContext generatorContext,
            Cell neighborCell,
            int[] cache
    ) {
        int relX = targetX - minBlockX;
        int relZ = targetZ - minBlockZ;

        // Reuse cached cell directly if the coordinate is within the current chunk bounds
        if (relX >= 0 && relX < 16 && relZ >= 0 && relZ < 16) {
            Cell cell = chunkCells[relX + (relZ * 16)];
            float water = ContinentalHydrology.getComplexWaterHeight(
                    cell.waterTable,
                    cell.globalContinentScale,
                    cell.continentSizeModifier
            ) + oceanHeightOffset;
            return levels.scale(water);
        }

        // Lazy computation & threadlocal caching for out of chunk coordinates
        int cacheX = relX + 12;
        int cacheZ = relZ + 12;
        int cacheIndex = cacheX + (cacheZ * 40);

        int cachedValue = cache[cacheIndex];
        if (cachedValue != Integer.MIN_VALUE) {
            return cachedValue;
        }

        generatorContext.lookup.applyCell(
                neighborCell.reset(),
                targetX,
                targetZ,
                false,
                false
        );

        float water = ContinentalHydrology.getComplexWaterHeight(
                neighborCell.waterTable,
                neighborCell.globalContinentScale,
                neighborCell.continentSizeModifier
        ) + oceanHeightOffset;

        int computedY = levels.scale(water);
        cache[cacheIndex] = computedY;
        return computedY;
    }

    private static boolean isTerrainPaint(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) ||
                state.is(Blocks.SAND) ||
                state.is(Blocks.GRAVEL) ||
                state.is(Blocks.MUD) ||
                state.is(Blocks.PODZOL) ||
                state.is(Blocks.MYCELIUM);
    }
}