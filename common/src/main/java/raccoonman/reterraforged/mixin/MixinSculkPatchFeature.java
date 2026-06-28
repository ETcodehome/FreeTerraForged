package raccoonman.reterraforged.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.SculkPatchFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.SculkPatchConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;
import raccoonman.reterraforged.world.worldgen.noise.module.Simplex;

@Mixin(SculkPatchFeature.class)
public class MixinSculkPatchFeature {

    @Unique
    private static final Simplex RTF_SCULK_NOISE = new Simplex(
            0.04F,                 // FIX: Lower frequency (0.08 -> 0.04) makes patches 2x larger
            3,
            0.5F,
            0.45F,
            Interpolation.CURVE3
    );

    @Unique private static final int CONFIG_RADIUS = 12;
    @Unique private static final float CONFIG_THRESHOLD = 0.42F;
    @Unique private static final float VEIN_WINDOW = 0.05F;

    // FIX: Set vertical coherence resolution. Blending every 6 blocks eliminates flat sheets.
    @Unique private static final int VERTICAL_SCALE_STEPS = 6;

    // FIX: Restrict face placement to 25% chance to reduce vein clusters by 4x
    @Unique private static final float VEIN_CHANCE_PER_FACE = 0.25F;

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void rtf$replaceSculkGeneration(FeaturePlaceContext<SculkPatchConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        RandomSource random = level.getRandom();

        long longSeed = level.getSeed();
        int baseSeed = (int) (longSeed ^ (longSeed >>> 32));

        float radiusSq = CONFIG_RADIUS * CONFIG_RADIUS;
        boolean placedAnything = false;

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = -CONFIG_RADIUS; x <= CONFIG_RADIUS; x++) {
            for (int y = -CONFIG_RADIUS; y <= CONFIG_RADIUS; y++) {
                for (int z = -CONFIG_RADIUS; z <= CONFIG_RADIUS; z++) {
                    float distSq = x * x + y * y + z * z;
                    if (distSq > radiusSq) continue;

                    int absoluteX = origin.getX() + x;
                    int absoluteY = origin.getY() + y;
                    int absoluteZ = origin.getZ() + z;
                    mutablePos.set(absoluteX, absoluteY, absoluteZ);

                    BlockState state = level.getBlockState(mutablePos);

                    if (state.is(BlockTags.SCULK_REPLACEABLE)) {
                        if (rtf$isAdjacentToAir(level, mutablePos)) {

                            // FIX: Pseudo-3D Noise Sampling via Vertical Layer Lerping
                            // Handles negative Y coordinates cleanly using floorDiv
                            int yFloor = Math.floorDiv(absoluteY, VERTICAL_SCALE_STEPS);
                            float yAlpha = (float) (absoluteY - (yFloor * VERTICAL_SCALE_STEPS)) / (float) VERTICAL_SCALE_STEPS;

                            // Smoothstep alpha to prevent angular transitions
                            float smoothedAlpha = yAlpha * yAlpha * (3.0F - 2.0F * yAlpha);

                            // Sample upper and lower 2D boundaries
                            float sampleLower = RTF_SCULK_NOISE.compute(absoluteX, absoluteZ, baseSeed + yFloor);
                            float sampleUpper = RTF_SCULK_NOISE.compute(absoluteX, absoluteZ, baseSeed + yFloor + 1);

                            // Blend them together linearly over 3D space
                            float noiseVal = Mth.lerp(smoothedAlpha, sampleLower, sampleUpper);

                            float falloff = 1.0F - (distSq / radiusSq);
                            float combinedScore = noiseVal - (1.0F - falloff) * 0.25F;

                            if (combinedScore > CONFIG_THRESHOLD) {
                                level.setBlock(mutablePos, Blocks.SCULK.defaultBlockState(), 2);
                                placedAnything = true;

                                if (combinedScore > (CONFIG_THRESHOLD + 0.15F) && random.nextFloat() < 0.08F) {
                                    rtf$placeDecorations(level, mutablePos, random);
                                }
                            } else if (combinedScore > (CONFIG_THRESHOLD - VEIN_WINDOW)) {
                                // Pass random down to execute the 4x reduction roll
                                rtf$tryPlaceVeinOnExposedFaces(level, mutablePos, random);
                                placedAnything = true;
                            }
                        }
                    }
                }
            }
        }

        cir.setReturnValue(placedAnything);
    }

    @Unique
    private static boolean rtf$isAdjacentToAir(WorldGenLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).isAir()) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static void rtf$tryPlaceVeinOnExposedFaces(WorldGenLevel level, BlockPos solidPos, RandomSource random) {
        for (Direction dir : Direction.values()) {
            // FIX: Probability filter to thin out vein clusters cleanly
            if (random.nextFloat() > VEIN_CHANCE_PER_FACE) continue;

            BlockPos airPos = solidPos.relative(dir);
            BlockState airState = level.getBlockState(airPos);

            boolean isAir = airState.isAir();
            boolean isExistingVein = airState.is(Blocks.SCULK_VEIN);

            if (isAir || isExistingVein) {
                Direction faceToSet = dir.getOpposite();

                BlockState veinState = isExistingVein ? airState : Blocks.SCULK_VEIN.defaultBlockState();
                veinState = veinState.setValue(MultifaceBlock.getFaceProperty(faceToSet), true);

                level.setBlock(airPos, veinState, 2);
            }
        }
    }

    @Unique
    private static void rtf$placeDecorations(WorldGenLevel level, BlockPos pos, RandomSource random) {
        BlockPos above = pos.above();
        if (level.getBlockState(above).isAir()) {
            float roll = random.nextFloat();
            BlockState decor;

            if (roll < 0.50F) {
                // 50% chance for a Sensor
                decor = Blocks.SCULK_SENSOR.defaultBlockState();
            } else if (roll < 0.88F) {
                // 38% chance for a Catalyst
                decor = Blocks.SCULK_CATALYST.defaultBlockState();
            } else {
                // 12% chance for an active Shrieker that can summon the Warden
                decor = Blocks.SCULK_SHRIEKER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.SculkShriekerBlock.CAN_SUMMON, true);
            }

            level.setBlock(above, decor, 2);
        }
    }
}