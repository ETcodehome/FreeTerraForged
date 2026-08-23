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

public class RiverGasketFeature extends Feature<NoneFeatureConfiguration> {

    // One cell allocated PER THREAD, reused indefinitely across feature calls
    private static final ThreadLocal<Cell> LOCAL_CELL = ThreadLocal.withInitial(Cell::new);
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

        // guard against interfering with non rtf content
        if (!((Object) randomState instanceof RTFRandomState rtfRandomState)) {
            return false;
        }

        // guard against no rtf generator context
        GeneratorContext generatorContext = rtfRandomState.generatorContext();
        if (generatorContext == null) {
            return false;
        }

        // get the cell we are checking efficiently
        Cell cell = LOCAL_CELL.get();
        generatorContext.lookup.applyCell(cell, placeContext.origin().getX(), placeContext.origin().getZ(), false, false);

        // Performance guard - prevent checking cells that are not near rivers
        if (cell.riverZone == RiverCarverSettings.RiverZone.None || cell.riverZone == RiverCarverSettings.RiverZone.ValleyFadeout) {
            return false;
        }

        Levels levels = generatorContext.lookup.getHeightmap().levels();
        BlockPos origin = placeContext.origin();

        int minBlockX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()));
        int minBlockZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()));

        Cell neighborCell = NEIGHBOUR_CELL.get();
        PosHolder pos = POS_HOLDER.get();

        BlockState fallbackState = Blocks.STONE.defaultBlockState();
        float oceanHeightOffset = levels.water;

        // CACHE 1: 2D Cache for water cell evaluations
        int[] neighborWaterYCache = WATER_Y_CACHE.get();

        for (int dz = -12; dz < 28; dz++) {
            for (int dx = -12; dx < 28; dx++) {

                int worldX = minBlockX + dx;
                int worldZ = minBlockZ + dz;

                generatorContext.lookup.applyCell(
                        neighborCell.reset(),
                        worldX,
                        worldZ,
                        false,
                        false
                );

                float water =
                        (ContinentalHydrology.getComplexWaterHeight(
                                neighborCell.waterTable,
                                neighborCell.globalContinentScale,
                                neighborCell.continentSizeModifier)
                        ) + oceanHeightOffset;

                int waterY = levels.scale(water);

                int cacheIndex = (dx + 12) + ((dz + 12) * 40);
                neighborWaterYCache[cacheIndex] = waterY;
            }
        }

        // CACHE 2: Fast 3D Cache for the horizontal terrain paint lookups
        Long2ObjectOpenHashMap<BlockState> paintCache = PAINT_CACHE.get();
        paintCache.clear();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = minBlockX + x;
                int blockZ = minBlockZ + z;

                generatorContext.lookup.applyCell(cell.reset(), blockX, blockZ, false, false);

                float targetWaterLevel =
                        (ContinentalHydrology.getComplexWaterHeight(
                                cell.waterTable,
                                cell.globalContinentScale,
                                cell.continentSizeModifier)
                        ) + oceanHeightOffset;
                int localWaterY = levels.scale(targetWaterLevel);
                int currentFloorHeight = levels.scale(cell.height);

                int scanTopY = Math.max(localWaterY, currentFloorHeight);
                int scanBottomY = Math.min(localWaterY, currentFloorHeight) - 8;
                scanBottomY = Math.max(scanBottomY, levels.scale(levels.water));

                // CACHE 3: structural state once per X/Z column using heightmap
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

                    if (currentState.is(Blocks.WATER) && currentState.getFluidState().isSource()) {

                        // Ensure air directly below the current water block gets gasketed
                        pos.belowNeighbor.set(blockX, y - 1, blockZ);
                        BlockState belowState = level.getBlockState(pos.belowNeighbor);
                        if (belowState.isAir() || belowState.is(Blocks.CAVE_AIR)) {
                            level.setBlock(pos.belowNeighbor, structuralState, 2);
                        }

                        int radius = placeContext.random().nextInt(5) + 3;
                        int radiusSq = radius * radius;

                        for (int dx = -radius; dx <= radius; dx++) {
                            for (int dz = -radius; dz <= radius; dz++) {
                                if (dx * dx + dz * dz <= radiusSq) {

                                    int targetX = blockX + dx;
                                    int targetZ = blockZ + dz;

                                    int cacheX = (targetX - minBlockX) + 12;
                                    int cacheZ = (targetZ - minBlockZ) + 12;
                                    int cacheIndex = cacheX + (cacheZ * 40);

                                    int neighbourWaterY = neighborWaterYCache[cacheIndex];

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
        }
        return true;
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