package raccoonman.reterraforged.world.worldgen.feature;

import com.mojang.serialization.Codec;
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

public class RiverGasketFeature extends Feature<NoneFeatureConfiguration> {

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public RiverGasketFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {

        // Extract the ReTerraForged generator context from the server level state
        WorldGenLevel level = context.level();
        net.minecraft.server.level.ServerLevel serverLevel = level.getLevel();
        net.minecraft.world.level.levelgen.RandomState randomState = serverLevel.getChunkSource().randomState();
        if (!((Object) randomState instanceof raccoonman.reterraforged.world.worldgen.RTFRandomState rtfRandomState)) {
            return false;
        }

        GeneratorContext generatorContext = rtfRandomState.generatorContext();
        if (generatorContext == null) {
            return false;
        }

        // Initialize localized lookup utilities
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

        // Extra mutables allocated for the surface decoration scanner to keep allocations at zero
        BlockPos.MutableBlockPos testAbovePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos testSidePos = new BlockPos.MutableBlockPos();


        BlockState fallbackState = Blocks.STONE.defaultBlockState();

        // We process the entire 16x16 chunk grid at once
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = minBlockX + x;
                int blockZ = minBlockZ + z;

                worldLookup.applyCell(cell.reset(), blockX, blockZ, false, false);

                // Calculate the region around water sources like rivers lakes and wetlands
                float oceanHeightOffset = levels.water;
                float targetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanHeightOffset;
                int localWaterY = levels.scale(targetWaterLevel);
                int currentFloorHeight = levels.scale(cell.height);

                int scanTopY = Math.max(localWaterY, currentFloorHeight);
                int scanBottomY = Math.min(localWaterY, currentFloorHeight) - 8;
                scanBottomY = Math.max(scanBottomY, level.getMinBuildHeight() + 16);

                // Vertical scan loop within the column's precise hydrology envelope
                for (int y = scanTopY; y >= scanBottomY; y--) {
                    currentPos.set(blockX, y, blockZ);
                    BlockState currentState = level.getBlockState(currentPos);

                    if (currentState.is(Blocks.WATER)) {

                        if (!currentState.getFluidState().isSource()) {
                            continue;
                        }

                        // Dynamic replacement block sampling (Default structural block)
                        BlockState structuralState = fallbackState;
                        for (int sampleY = y - 1; sampleY >= level.getMinBuildHeight(); sampleY--) {
                            samplePos.set(blockX, sampleY, blockZ);
                            BlockState sampleState = level.getBlockState(samplePos);

                            if (!sampleState.isAir() && !sampleState.is(Blocks.CAVE_AIR) && !sampleState.is(Blocks.WATER)) {
                                structuralState = sampleState;
                                break;
                            }
                        }

                        // Ensure air directly below the current water block gets gasketed
                        belowNeighborPos.set(blockX, y - 1, blockZ);
                        BlockState belowState = level.getBlockState(belowNeighborPos);
                        if (belowState.isAir() || belowState.is(Blocks.CAVE_AIR)) {
                            level.setBlock(belowNeighborPos, structuralState, 2);
                        }

                        // Circular brush setup
                        int radius = context.random().nextInt(5) + 3; // Radius 3 to 7
                        int radiusSq = radius * radius;

                        // Scan the 2D bounding box of the circle
                        for (int dx = -radius; dx <= radius; dx++) {
                            for (int dz = -radius; dz <= radius; dz++) {

                                if (dx * dx + dz * dz <= radiusSq) {

                                    int targetX = blockX + dx;
                                    int targetZ = blockZ + dz;

                                    worldLookup.applyCell(neighborCell.reset(), targetX, targetZ, false, false);

                                    float neighbourTargetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(neighborCell.waterTable) + oceanHeightOffset;
                                    int neighbourWaterY = levels.scale(neighbourTargetWaterLevel);

                                    if (y > neighbourWaterY) {
                                        continue;
                                    }

                                    neighborPos.set(targetX, y, targetZ);
                                    BlockState neighborState = level.getBlockState(neighborPos);

                                    if (neighborState.isAir() || neighborState.is(Blocks.CAVE_AIR)) {
                                        belowNeighborPos.set(neighborPos.getX(), neighborPos.getY() - 1, neighborPos.getZ());
                                        BlockState belowNeighborState = level.getBlockState(belowNeighborPos);

                                        if (belowNeighborState.is(Blocks.WATER)) {
                                            continue;
                                        }

                                        // Perform contextual surface matching
                                        // features are late in the minecraft world creation process.
                                        // Surface decorators have run and density functions have carved caves
                                        // if we leave it as abre stone here it stays as stone, so instead we try and draw on the surrounding terrain
                                        // copying a nearby block finish to pretty up this column.
                                        BlockState finalPlacementState = structuralState;

                                        testAbovePos.set(targetX, y + 1, targetZ);
                                        BlockState stateAbove = level.getBlockState(testAbovePos);

                                        if (stateAbove.isAir() || stateAbove.is(Blocks.CAVE_AIR) || stateAbove.is(Blocks.WATER)) {

                                            // Step outward progressively up to 4 blocks away to find valid terrain paint
                                            searchLoop:
                                            for (int dist = 1; dist <= 4; dist++) {
                                                for (Direction dir : HORIZONTAL_DIRECTIONS) {
                                                    testSidePos.set(
                                                            targetX + (dir.getStepX() * dist),
                                                            y,
                                                            targetZ + (dir.getStepZ() * dist)
                                                    );
                                                    BlockState nearbyState = level.getBlockState(testSidePos);

                                                    if (nearbyState.is(Blocks.GRASS_BLOCK)
                                                            || nearbyState.is(Blocks.SAND)
                                                            || nearbyState.is(Blocks.GRAVEL)
                                                            || nearbyState.is(Blocks.MUD)
                                                            || nearbyState.is(Blocks.PODZOL)
                                                            || nearbyState.is(Blocks.MYCELIUM)) {

                                                        finalPlacementState = nearbyState;
                                                        break searchLoop; // Escapes both loops immediately upon match
                                                    }
                                                }
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
}