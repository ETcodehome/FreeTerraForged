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

                            // WATER_WAKE particles perform best at roughly 0.05 - 0.12 speed
                            double speed = 0.20;
                            double vx = Math.cos(radians) * speed;
                            double vz = Math.sin(radians) * speed;

                            // Introduce minor variance so foam lines look organic
                            vx += (random.nextDouble() - 0.5) * 0.01;
                            vz += (random.nextDouble() - 0.5) * 0.01;

                            // Spawn the directional wake particles flat on the water surface
                            level.addParticle(
                                    ParticleTypes.FLAME,
                                    pos.getX() + random.nextDouble(),
                                    pos.getY() + 0.88,
                                    pos.getZ() + random.nextDouble(),
                                    vx,
                                    0.0, // Keep vertical velocity flat so it stays on the surface
                                    vz
                            );
                        }
                    }
                }
            }
        }
    }
}