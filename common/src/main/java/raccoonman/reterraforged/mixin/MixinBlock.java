package raccoonman.reterraforged.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

@Mixin(Block.class)
public class MixinBlock {

    @Inject(method = "animateTick", at = @At("HEAD"))
    private void spawnRiverParticles(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        // Guardrail: Only run on the client side
        if (!level.isClientSide()) {
            return;
        }

        // 1. FAST PERFORMANCE CHECK: Is this block a LiquidBlock?
        // This ensures non-fluid blocks exit immediately with zero performance cost.
        if (state.getBlock() instanceof LiquidBlock) {

            // 2. Is this specific liquid water?
            if (state.getFluidState().is(FluidTags.WATER)) {

                // 3. Only spawn particles if there is air above the water surface
                if (level.getBlockState(pos.above()).isAir()) {

                    // 4. Fetch the client-side chunk instance
                    ChunkAccess chunk = level.getChunk(pos);
                    if (chunk instanceof IFlowFieldHolder holder) {
                        ChunkFlowField flowField = holder.reterraforged$getFlowField();

                        int localX = pos.getX() & 15;
                        int localZ = pos.getZ() & 15;
                        byte packedAngle = flowField.getAngle(localX, localZ);

                        // =================================================================
                        // TEMPORARY TESTING HACK
                        // Forces particles to render on ALL water blocks until the
                        // network packet logic is fully wired up.
                        if (packedAngle == 0) packedAngle = (byte) 32;
                        // =================================================================

                        if (packedAngle != 0) {
                            // 5. Convert the unsigned byte wrapper map back to Radians
                            double radians = (packedAngle & 0xFF) * (Math.PI / 128.0);

                            // 6. Calculate downstream physics vectors
                            double speed = 0.14;
                            double vx = Math.cos(radians) * speed;
                            double vz = Math.sin(radians) * speed;

                            // Introduce minor variance so foam lines look organic
                            vx += (random.nextDouble() - 0.5) * 0.02;
                            vz += (random.nextDouble() - 0.5) * 0.02;

                            // 7. Spawn bright green village sparkles moving downstream on the surface
                            level.addParticle(
                                    ParticleTypes.HAPPY_VILLAGER,
                                    pos.getX() + random.nextDouble(),
                                    pos.getY() + 0.95,
                                    pos.getZ() + random.nextDouble(),
                                    vx,
                                    0.01,
                                    vz
                            );
                        }
                    }
                }
            }
        }
    }
}