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
        WorldGenLevel level = context.level();

        // 1. Extract the ReTerraForged generator context from the server level state
        net.minecraft.server.level.ServerLevel serverLevel = level.getLevel();
        net.minecraft.world.level.levelgen.RandomState randomState = serverLevel.getChunkSource().randomState();
        if (!((Object) randomState instanceof raccoonman.reterraforged.world.worldgen.RTFRandomState rtfRandomState)) {
            return false; // Skip if this chunk isn't being driven by RTF
        }

        GeneratorContext generatorContext = rtfRandomState.generatorContext();
        if (generatorContext == null) {
            return false;
        }

        // 2. Initialize our localized lookup utilities
        WorldLookup worldLookup = new WorldLookup(generatorContext);
        Levels levels = worldLookup.getHeightmap().levels();
        BlockPos origin = context.origin();

        int minBlockX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()));
        int minBlockZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()));

        Cell cell = new Cell();
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowNeighborPos = new BlockPos.MutableBlockPos();

        BlockState stoneState = Blocks.REDSTONE_BLOCK.defaultBlockState();

        // 3. Process the 16x16 chunk grid
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = minBlockX + x;
                int blockZ = minBlockZ + z;

                // Query the hydrology/terrain profile for this exact column
                worldLookup.applyCell(cell.reset(), blockX, blockZ, false, false);

                // DYNAMIC BOUNDS CALCULATION
                // Convert the cell's continuous noise values back into concrete world Y integers
                float oceanHeightOffset = levels.water;
                float targetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanHeightOffset;
                int localWaterY = levels.scale(targetWaterLevel);
                int currentFloorHeight = levels.scale(cell.height);

                // scanTopY tracks the absolute ceiling of where water or surface assets interact
                int scanTopY = Math.max(localWaterY, currentFloorHeight);

                // Scan down just slightly past the valley floor/riverbed to catch
                // immediate subterranean cave punctures tearing open the bottom of the pool
                int scanBottomY = Math.min(localWaterY, currentFloorHeight) - 8;
                scanBottomY = Math.max(scanBottomY, level.getMinBuildHeight() + 16); // Absolute safety floor

                // 4. Vertical scan loop within the column's precise hydrology envelope
                for (int y = scanTopY; y >= scanBottomY; y--) {
                    currentPos.set(blockX, y, blockZ);
                    BlockState currentState = level.getBlockState(currentPos);

                    if (currentState.is(Blocks.WATER)) {

                        // ensure air below gets gasketed
                        belowNeighborPos.set(blockX, y - 1, blockZ);
                        BlockState belowState = level.getBlockState(belowNeighborPos);
                        if (belowState.isAir() || belowState.is(Blocks.CAVE_AIR)) {
                            level.setBlock(belowNeighborPos, stoneState, 2);
                        }

                        // Inspect horizontal neighbors for air exposure leaks
                        for (Direction dir : HORIZONTAL_DIRECTIONS) {
                            neighborPos.set(
                                    blockX + dir.getStepX(),
                                    y,
                                    blockZ + dir.getStepZ()
                            );

                            BlockState neighborState = level.getBlockState(neighborPos);

                            if (neighborState.isAir() || neighborState.is(Blocks.CAVE_AIR)) {
                                belowNeighborPos.set(neighborPos.getX(), neighborPos.getY() - 1, neighborPos.getZ());
                                BlockState belowNeighborState = level.getBlockState(belowNeighborPos);

                                // If the air gap isn't a natural waterfall plunging into a lower body of water, plug it
                                if (!belowNeighborState.is(Blocks.WATER)) {
                                    level.setBlock(neighborPos, stoneState, 2);
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