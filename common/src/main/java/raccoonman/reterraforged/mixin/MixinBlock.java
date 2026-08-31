package raccoonman.reterraforged.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
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

        // Guard Clauses & Throttling
        if (!level.isClientSide()) return;
        if (random.nextFloat() > 0.30f) return; // abandon 70%
        if (!(state.getBlock() instanceof LiquidBlock)) return;
        if (!state.getFluidState().is(FluidTags.WATER)) return;
        if (!level.getBlockState(pos.above()).isAir()) return;
        ChunkAccess chunk = level.getChunk(pos);
        if (!(chunk instanceof IFlowFieldHolder holder)) return;

        ChunkFlowField flowField = holder.reterraforged$getFlowField();
        if (!flowField.enableFlowParticles()) return;
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;

        // Check for active flow (magnitude > 0)
        if (flowField.hasFlow(localX, localZ)) {
            // Query direction and magnitude directly via helper getters
            double radians = flowField.getAngleRadians(localX, localZ);
            float flowStrength = flowField.getNormalizedMagnitude(localX, localZ);

            // Dynamic Sine-Wave Weaving
            // Blending world space and client game time creates an organic, moving current filament
            long gameTime = level.getGameTime();
            double wavePhase = (pos.getX() * 0.4 + pos.getZ() * 0.4) + (gameTime * 0.15);
            double waveDisplacement = Math.sin(wavePhase) * 0.03;

            // Base Vectors (Forward Flow scaled by river magnitude, and Perpendicular Drift)
            double speed = (0.025 + (random.nextDouble() * 0.02)) * flowStrength;
            double forwardVx = Math.cos(radians) * speed;
            double forwardVz = Math.sin(radians) * speed;

            // Lateral drift combined with our sine-wave wiggle
            double lateralDrift = ((random.nextDouble() - 0.5) * 0.02) + waveDisplacement;
            double driftVx = -Math.sin(radians) * lateralDrift;
            double driftVz = Math.cos(radians) * lateralDrift;

            double vx = forwardVx + driftVx;
            double vz = forwardVz + driftVz;

            // Particle Type Aesthetic Variety Pool
            ParticleOptions chosenParticle;
            float roll = random.nextFloat();

            if (roll < 0.45f) {
                chosenParticle = ParticleTypes.FISHING;
            } else if (roll < 0.75f) {
                chosenParticle = ParticleTypes.DOLPHIN;
            } else {
                chosenParticle = ParticleTypes.SPLASH;
            }

            // Dynamic Surface Snapping
            float fluidHeight = state.getFluidState().getHeight(level, pos);
            double particleY = pos.getY() + fluidHeight + 0.15;

            // Spawn the finalized dynamic particle
            level.addParticle(
                    chosenParticle,
                    pos.getX() + random.nextDouble(),
                    particleY,
                    pos.getZ() + random.nextDouble(),
                    vx,
                    0.0,
                    vz
            );
        }
    }
}
