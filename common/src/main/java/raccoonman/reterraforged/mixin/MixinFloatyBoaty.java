package raccoonman.reterraforged.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.data.worldgen.preset.settings.FlowSettings;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;

@Mixin(Boat.class)
public abstract class MixinFloatyBoaty {
    @Shadow private Boat.Status status;
    @Shadow private boolean inputUp;
    @Shadow private boolean inputDown;

    final int tickLerper = 25;

    // 10 ticks = 0.5 seconds at Minecraft's 20tps. Initialize at 10 so it's fully active by default.
    private int reterraforged$upstreamReleaseTicks = tickLerper;

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/Boat;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
            )
    )
    private void applyRiverPhysics(CallbackInfo ci) {

        Boat boat = (Boat) (Object) this;
        Level level = boat.level();

        boolean allowFlowDynamics = FlowSettings.CurrentPresetState.get().enableBoatFlowDynamics();
        if (!allowFlowDynamics){
            return;
        }

        BlockPos pos = boat.blockPosition().below();
        Holder<Biome> biomeHolder = level.getBiome(pos);

        if (biomeHolder.is(Biomes.RIVER)) {
            Vec3 currentMotion = boat.getDeltaMovement();
            double motionX = currentMotion.x;
            double motionY = currentMotion.y;
            double motionZ = currentMotion.z;

            // 1. Buoyancy
            boolean allowGoingUpWaterfalls = FlowSettings.CurrentPresetState.get().enableNavigableWaterfalls();
            if (allowGoingUpWaterfalls && (this.status == Boat.Status.UNDER_WATER || this.status == Boat.Status.UNDER_FLOWING_WATER)) {
                motionY = (boat.getControllingPassenger() != null) ? 0.3 : 0.2;
            }

            // 2. Dynamic River Current
            if (this.status != Boat.Status.IN_AIR && this.status != Boat.Status.ON_LAND) {
                ChunkAccess chunk = level.getChunk(pos);
                if (chunk instanceof IFlowFieldHolder holder) {
                    ChunkFlowField flowField = holder.reterraforged$getFlowField();

                    int localX = pos.getX() & 15;
                    int localZ = pos.getZ() & 15;
                    byte packedAngle = flowField.getAngle(localX, localZ);

                    if (packedAngle != 0) {
                        double radians = (packedAngle & 0xFF) * (Math.PI / 128.0);

                        double targetX = Math.cos(radians);
                        double targetZ = Math.sin(radians);

                        Vec3 lookAngle = boat.getLookAngle();
                        double boatX = lookAngle.x;
                        double boatZ = lookAngle.z;
                        double boatLen = Math.hypot(boatX, boatZ);

                        if (boatLen > 1.0E-5) {
                            boatX /= boatLen;
                            boatZ /= boatLen;

                            double dotProduct = (targetX * boatX) + (targetZ * boatZ);

                            if (dotProduct >= 0.0) {
                                double avgX = targetX + boatX;
                                double avgZ = targetZ + boatZ;
                                double avgLen = Math.hypot(avgX, avgZ);

                                if (avgLen > 1.0E-5) {
                                    targetX = avgX / avgLen;
                                    targetZ = avgZ / avgLen;
                                }
                            }
                        }

                        // Apply the regular incremental push force
                        double currentForce = 0.006;
                        motionX += targetX * currentForce;
                        motionZ += targetZ * currentForce;

                        // 3. Upstream Input Check
                        double paddleX = 0;
                        double paddleZ = 0;
                        if (this.inputUp) {
                            paddleX += lookAngle.x;
                            paddleZ += lookAngle.z;
                        }
                        if (this.inputDown) {
                            paddleX -= lookAngle.x;
                            paddleZ -= lookAngle.z;
                        }

                        double paddleDot = (paddleX * targetX) + (paddleZ * targetZ);
                        boolean paddlingAgainstCurrent = paddleDot < -1.0E-5;

                        // 4. Smooth Lerp Timer Logic
                        if (paddlingAgainstCurrent) {
                            // Reset the timer completely while they are actively fighting the current
                            this.reterraforged$upstreamReleaseTicks = 0;
                        } else if (this.reterraforged$upstreamReleaseTicks < tickLerper) {
                            // Increment the timer up to our 10-tick max (0.5 seconds)
                            this.reterraforged$upstreamReleaseTicks++;
                        }

                        // Calculate our LERP interpolation factor (alpha) from 0.0 to 1.0
                        double alpha = this.reterraforged$upstreamReleaseTicks / tickLerper;

                        // Apply the Velocity Floor modified by our interpolation factor
                        if (alpha > 0.0) {
                            double currentSpeedAlongTarget = (motionX * targetX) + (motionZ * targetZ);
                            double velocityFloor = 0.04;

                            if (currentSpeedAlongTarget < velocityFloor) {
                                // Scale the corrective velocity by alpha to ease the boat back into the current
                                double missingSpeed = (velocityFloor - currentSpeedAlongTarget) * alpha;
                                motionX += targetX * missingSpeed;
                                motionZ += targetZ * missingSpeed;
                            }
                        }
                    }
                }
            }

            boat.setDeltaMovement(motionX, motionY, motionZ);
        }
    }
}