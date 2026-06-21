package raccoonman.reterraforged.world.worldgen.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings;

import java.util.function.Supplier;

public class RiverGasketFeature extends Feature<NoneFeatureConfiguration> {
    public static Supplier<WorldLookup> LOOKUP_PROVIDER = () -> null;

    public RiverGasketFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {

        // Get access to the relevant data
        net.minecraft.server.level.ServerLevel serverLevel = context.level().getLevel();
        net.minecraft.world.level.levelgen.RandomState randomState = serverLevel.getChunkSource().randomState();
        if (!((Object) randomState instanceof raccoonman.reterraforged.world.worldgen.RTFRandomState rtfRandomState)) {
            return false; // Safely skip if this isn't an RTF-managed world state
        }

        GeneratorContext generatorContext = rtfRandomState.generatorContext();
        if (generatorContext == null) {
            return false;
        }

        // Instantiated a localized WorldLookup using the context
        WorldLookup worldLookup = new WorldLookup(generatorContext);
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        Levels levels = worldLookup.getHeightmap().levels();

        int minBlockX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()));
        int minBlockZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()));

        Cell cell = new Cell();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        BlockState defaultStone = Blocks.STONE.defaultBlockState();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = minBlockX + x;
                int blockZ = minBlockZ + z;

                worldLookup.applyCell(cell.reset(), blockX, blockZ, false, false);

                boolean inFillRegion = cell.riverZone != RiverCarverSettings.RiverZone.None;

                if (inFillRegion) {
                    int targetWaterY = levels.scale(cell.riverWaterLevel);
                    int currentFloorHeight = levels.scale(cell.height);

                    // exit early if terrain is already one block above the water level
                    if (currentFloorHeight >= targetWaterY + 1){
                        break;
                    }

                    // Scan down from the maximum possible height to find the actual physical surface in the world right now
                    int editY = currentFloorHeight;
                    while (editY > level.getMinBuildHeight()) {
                        mutablePos.set(blockX, editY, blockZ);
                        BlockState state = level.getBlockState(mutablePos);

                        // Consider it the true surface when we hit something that isn't air, open cave spaces, or liquids
                        if (!state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.LAVA) && !state.is(Blocks.CAVE_AIR)) {
                            break;
                        }
                        editY--;
                    }

                    // EDGE SMOOTHING LOGIC: If we are in the outer valley floor, blend our target height smoothly down
                    // to the natural physical terrain surface based on how close the riverMask is to 1.0.
                    int targetGasketHeight = currentFloorHeight;
                    if (cell.riverZone == RiverCarverSettings.RiverZone.ValleyFloor) {
                        // The valley floor typically occupies the upper boundary of the mask (e.g., 0.55 to 1.0).
                        // We slide our alpha gradient cleanly across this window.
                        float startFadeMask = 0.55f;
                        if (cell.riverMask > startFadeMask) {
                            float alpha = (cell.riverMask - startFadeMask) / (1.0f - startFadeMask);
                            alpha = Math.max(0.0f, Math.min(1.0f, alpha)); // Strict clamp to [0.0, 1.0]

                            // Linearly interpolate between the full design height and the native surface height
                            targetGasketHeight = Math.round(currentFloorHeight + (editY - currentFloorHeight) * alpha);
                        }
                    }

                    // DYNAMIC FILLER SELECTION: Sample from the bottom block of the original 4-block crust stack
                    int fillerY = Math.max(level.getMinBuildHeight(), editY - 3);
                    BlockState columnFiller = level.getBlockState(mutablePos.set(blockX, fillerY, blockZ));

                    if (columnFiller.isAir() || columnFiller.is(Blocks.CAVE_AIR) || columnFiller.is(Blocks.LAVA) || columnFiller.is(Blocks.WATER)) {
                        columnFiller = defaultStone;
                    }

                    // If our sloped target height is still higher than the ground, perform a smooth upshift
                    if (targetGasketHeight > editY && editY > level.getMinBuildHeight()) {
                        int crustDepth = 4;
                        BlockState[] crustSnapshot = new BlockState[crustDepth];

                        for (int i = 0; i < crustDepth; i++) {
                            int yCheck = editY - i;
                            if (yCheck >= level.getMinBuildHeight()) {
                                crustSnapshot[i] = level.getBlockState(mutablePos.set(blockX, yCheck, blockZ));
                            } else {
                                crustSnapshot[i] = columnFiller;
                            }
                        }

                        // Place the shifted topsoil blocks at our newly sloped target surface position
                        for (int i = 0; i < crustDepth; i++) {
                            level.setBlock(mutablePos.set(blockX, targetGasketHeight - i, blockZ), crustSnapshot[i], 2);
                        }

                        // Fill the gap created under the sloped surface
                        for (int y = targetGasketHeight - crustDepth; y > editY - crustDepth; y--) {
                            if (y >= level.getMinBuildHeight()) {
                                level.setBlock(mutablePos.set(blockX, y, blockZ), columnFiller, 2);
                            }
                        }
                    }

                    // Calculate our noise-based cavity thickness relative to our new sloped surface
                    double noiseVal = (Math.sin(blockX * 0.12) * Math.cos(blockZ * 0.12)
                            + Math.sin(blockX * 0.04)
                            + Math.cos(blockZ * 0.04)) / 2.0;

                    float normalizedNoise = (float) ((noiseVal + 1.0) / 2.0);
                    int dynamicThickness = 3 + Math.round(normalizedNoise * 4);

                    // Constrain the bottom scanning limit to our new sloped baseline
                    int scanBottomY = targetGasketHeight - dynamicThickness;

                    // The cavity scan top must also respect the sloped height to prevent filling air pockets
                    // that are now supposed to be open sky above the sloped terrain.
                    int gasketScanTopY = Math.max(targetWaterY, targetGasketHeight);

                    // Cavity fill open air gaps using the sloped bounds
                    for (int y = gasketScanTopY; y >= scanBottomY; y--) {
                        mutablePos.set(blockX, y, blockZ);
                        BlockState currentState = level.getBlockState(mutablePos);

                        if (currentState.isAir() || currentState.is(Blocks.LAVA) || currentState.is(Blocks.CAVE_AIR)) {
                            level.setBlock(mutablePos, columnFiller, 2);
                        }
                    }
                }
            }
        }
        return true;
    }
}