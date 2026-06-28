package raccoonman.reterraforged.world.worldgen.feature;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;

import java.util.Arrays;

public class RiverGasketFeature extends Feature<NoneFeatureConfiguration> {

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public RiverGasketFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();

        if ( !((Object) level.getLevel().getChunkSource().randomState() instanceof raccoonman.reterraforged.world.worldgen.RTFRandomState rtfRandomState)) {
            return false;
        }

        GeneratorContext generatorContext = rtfRandomState.generatorContext();
        if (generatorContext == null) {
            return false;
        }

        WorldLookup worldLookup = new WorldLookup(generatorContext);
        Levels levels = worldLookup.getHeightmap().levels();
        BlockPos origin = context.origin();

        int minBlockX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()));
        int minBlockZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()));

        Cell cell = new Cell();
        Cell neighborCell = new Cell();

        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowNeighborPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos testAbovePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos testSidePos = new BlockPos.MutableBlockPos();

        BlockState fallbackState = Blocks.STONE.defaultBlockState();
        float oceanHeightOffset = levels.water;

        // CACHE 1: 2D Cache for water cell evaluations
        int[] neighborWaterYCache = new int[40 * 40];

        for (int dz = -12; dz < 28; dz++) {
            for (int dx = -12; dx < 28; dx++) {

                int worldX = minBlockX + dx;
                int worldZ = minBlockZ + dz;

                worldLookup.applyCell(
                        neighborCell.reset(),
                        worldX,
                        worldZ,
                        false,
                        false
                );

                float water =
                        ContinentalHydrology.getWeightedWaterHeight(neighborCell.waterTable)
                                + oceanHeightOffset;

                int waterY = levels.scale(water);

                int cacheIndex = (dx + 12) + ((dz + 12) * 40);
                neighborWaterYCache[cacheIndex] = waterY;
            }
        }

        // CACHE 2: Fast 3D Cache for the horizontal terrain paint lookups
        Long2ObjectOpenHashMap<BlockState> paintCache = new Long2ObjectOpenHashMap<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = minBlockX + x;
                int blockZ = minBlockZ + z;

                worldLookup.applyCell(cell.reset(), blockX, blockZ, false, false);

                float targetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanHeightOffset;
                int localWaterY = levels.scale(targetWaterLevel);
                int currentFloorHeight = levels.scale(cell.height);

                int scanTopY = Math.max(localWaterY, currentFloorHeight);
                int scanBottomY = Math.min(localWaterY, currentFloorHeight) - 8;
                scanBottomY = Math.max(scanBottomY, level.getMinBuildHeight() + 16);

                // CACHE 3: structural state once per X/Z column using heightmap
                BlockState structuralState = null;
                int columnTopY = level.getChunk(origin).getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                        blockX, blockZ
                );
                if (columnTopY >= level.getMinBuildHeight()) {
                    samplePos.set(blockX, columnTopY, blockZ);
                    BlockState topState = level.getBlockState(samplePos);
                    if (!topState.isAir() && !topState.is(Blocks.CAVE_AIR) && !topState.is(Blocks.WATER)) {
                        structuralState = topState;
                    }
                }
                if (structuralState == null) {
                    structuralState = fallbackState;
                }

                for (int y = scanTopY; y >= scanBottomY; y--) {
                    currentPos.set(blockX, y, blockZ);
                    BlockState currentState = level.getBlockState(currentPos);

                    if (currentState.is(Blocks.WATER) && currentState.getFluidState().isSource()) {

                        // Ensure air directly below the current water block gets gasketed
                        belowNeighborPos.set(blockX, y - 1, blockZ);
                        BlockState belowState = level.getBlockState(belowNeighborPos);
                        if (belowState.isAir() || belowState.is(Blocks.CAVE_AIR)) {
                            level.setBlock(belowNeighborPos, structuralState, 2);
                        }

                        int radius = context.random().nextInt(5) + 3;
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

                                    neighborPos.set(targetX, y, targetZ);
                                    BlockState neighborState = level.getBlockState(neighborPos);

                                    if (neighborState.isAir() || neighborState.is(Blocks.CAVE_AIR)) {
                                        belowNeighborPos.set(targetX, y - 1, targetZ);
                                        BlockState belowNeighborState = level.getBlockState(belowNeighborPos);

                                        if (belowNeighborState.is(Blocks.WATER)) {
                                            continue;
                                        }

                                        BlockState finalPlacementState = structuralState;
                                        testAbovePos.set(targetX, y + 1, targetZ);
                                        BlockState stateAbove = level.getBlockState(testAbovePos);

                                        if (stateAbove.isAir() || stateAbove.is(Blocks.CAVE_AIR) || stateAbove.is(Blocks.WATER)) {
                                            long posHash = BlockPos.asLong(targetX, y, targetZ);

                                            if (paintCache.containsKey(posHash)) {
                                                finalPlacementState = paintCache.get(posHash);
                                            } else {
                                                BlockState foundPaint = structuralState;

                                                searchLoop:
                                                for (int dist = 1; dist <= 4; dist++) {
                                                    for (Direction dir : HORIZONTAL_DIRECTIONS) {
                                                        testSidePos.set(
                                                                targetX + (dir.getStepX() * dist),
                                                                y,
                                                                targetZ + (dir.getStepZ() * dist)
                                                        );
                                                        BlockState nearbyState = level.getBlockState(testSidePos);

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

                                        level.setBlock(neighborPos, finalPlacementState, 2);
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
