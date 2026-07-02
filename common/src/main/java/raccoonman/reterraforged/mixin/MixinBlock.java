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
        if (!level.isClientSide()) {
            return;
        }

        if (state.getBlock() instanceof LiquidBlock) {
            if (state.getFluidState().is(FluidTags.WATER)) {
                if (level.getBlockState(pos.above()).isAir()) {

                    ChunkAccess chunk = level.getChunk(pos);
                    if (chunk instanceof IFlowFieldHolder holder) {
                        ChunkFlowField flowField = holder.reterraforged$getFlowField();

                        int localX = pos.getX() & 15;
                        int localZ = pos.getZ() & 15;
                        byte packedAngle = flowField.getAngle(localX, localZ);

                        if (packedAngle != 0) {
                            // Convert the unsigned byte wrapper map back to Radians
                            double radians = (packedAngle & 0xFF) * (Math.PI / 128.0);

                            // Dynamic Speed: Randomize slightly within the sweet spot
                            double speed = 0.03 + (random.nextDouble() * 0.02);

                            // Forward velocity vector based on flow direction
                            double forwardVx = Math.cos(radians) * speed;
                            double forwardVz = Math.sin(radians) * speed;

                            // 2. Perpendicular Drift: Adds lateral spread so foam isn't a perfect, rigid line
                            double driftSpeed = (random.nextDouble() - 0.5) * 0.04;
                            double driftVx = -Math.sin(radians) * driftSpeed;
                            double driftVz = Math.cos(radians) * driftSpeed;

                            // Combine vectors for final velocity
                            double vx = forwardVx + driftVx;
                            double vz = forwardVz + driftVz;

                            // 3. Dynamic Height: Calculate exact water height to prevent hovering/clipping
                            float fluidHeight = state.getFluidState().getHeight(level, pos);
                            double particleY = pos.getY() + fluidHeight + 0.02 + 0.25; // +0.02 to prevent Z-fighting on the surface

                            // Spawn the directional wake particles flat on the water surface
                            level.addParticle(
                                    ParticleTypes.FISHING,
                                    pos.getX() + random.nextDouble(),
                                    particleY,
                                    pos.getZ() + random.nextDouble(),
                                    vx,
                                    0.0, // Strict 0.0 ensures it glides on the X/Z plane
                                    vz
                            );
                        }
                    }
                }
            }
        }
    }
}