package raccoonman.reterraforged.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;
import raccoonman.reterraforged.world.worldgen.FlowSettingsSnapshot;
import raccoonman.reterraforged.world.worldgen.IFlowSettingsHolder;

@Mixin(Boat.class)
public abstract class MixinFloatyBoaty {
    @Shadow private Boat.Status status;
    @Shadow private boolean inputUp;
    @Shadow private boolean inputDown;

    private final int tickLerper = 25;

    // 25 ticks = 1.25 seconds at 20tps. Initialized fully active by default.
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
		FlowSettingsSnapshot flowSettings = ((IFlowSettingsHolder) level).reterraforged$getFlowSettings();

        // Apply physics whenever in liquid, independent of vanilla river biome boundaries
        if (this.status != Boat.Status.IN_AIR && this.status != Boat.Status.ON_LAND) {
            BlockPos pos = boat.blockPosition();
            ChunkAccess chunk = level.getChunk(pos);

            if (chunk instanceof IFlowFieldHolder holder) {
                ChunkFlowField flowField = holder.reterraforged$getFlowField();
				if (!flowSettings.boatFlowDynamics()) {
                    return;
                }

                int localX = pos.getX() & 15;
                int localZ = pos.getZ() & 15;

                // Check for active flow (magnitude > 0)
                if (flowField.hasFlow(localX, localZ)) {
                    Vec3 currentMotion = boat.getDeltaMovement();
                    double motionX = currentMotion.x;
                    double motionY = currentMotion.y;
                    double motionZ = currentMotion.z;

                    // 1. Buoyancy / Waterfall Logic
					boolean allowGoingUpWaterfalls = flowSettings.navigableWaterfalls();
                    if (allowGoingUpWaterfalls && (this.status == Boat.Status.UNDER_WATER || this.status == Boat.Status.UNDER_FLOWING_WATER)) {
                        motionY = (boat.getControllingPassenger() != null) ? 0.3 : 0.2;
                    }

                    // 2. Dynamic River Current
                    // Query direction and magnitude directly via helper getters
                    double radians = flowField.getAngleRadians(localX, localZ);
                    float flowStrength = flowField.getNormalizedMagnitude(localX, localZ); // 0.14..1.0 range

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

                    // Apply incremental push force scaled by river magnitude
                    double currentForce = 0.006 * flowStrength;
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
                        // Reset the timer completely while actively fighting the current
                        this.reterraforged$upstreamReleaseTicks = 0;
                    } else if (this.reterraforged$upstreamReleaseTicks < this.tickLerper) {
                        // Increment timer back up toward max
                        this.reterraforged$upstreamReleaseTicks++;
                    }

                    // Lerp factor calculation
                    double alpha = (double) this.reterraforged$upstreamReleaseTicks / (double) this.tickLerper;

                    // Apply Velocity Floor scaled by river magnitude and lerp alpha
                    if (alpha > 0.0) {
                        double currentSpeedAlongTarget = (motionX * targetX) + (motionZ * targetZ);
                        double velocityFloor = 0.04 * flowStrength;

                        if (currentSpeedAlongTarget < velocityFloor) {
                            double missingSpeed = (velocityFloor - currentSpeedAlongTarget) * alpha;
                            motionX += targetX * missingSpeed;
                            motionZ += targetZ * missingSpeed;
                        }
                    }

                    boat.setDeltaMovement(motionX, motionY, motionZ);
                }
            }
        }
    }
}
