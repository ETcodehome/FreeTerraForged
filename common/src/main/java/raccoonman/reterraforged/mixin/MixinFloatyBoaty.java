package raccoonman.reterraforged.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Boat.class)
public abstract class MixinFloatyBoaty {
    @Shadow private Boat.Status status;

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/Boat;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
            )
    )
    private void applyRiverBuoyancy(CallbackInfo ci) {
        Boat boat = (Boat) (Object) this;
        Level level = boat.level();
        Holder<Biome> biomeHolder = level.getBiome(boat.blockPosition());
        if (biomeHolder.is(Biomes.RIVER)) {
            if (this.status == Boat.Status.UNDER_WATER || this.status == Boat.Status.UNDER_FLOWING_WATER) {
                Vec3 currentMotion = boat.getDeltaMovement();
                double liftVelocity = (boat.getControllingPassenger() != null) ? 0.3 : 0.2;
                boat.setDeltaMovement(currentMotion.x, liftVelocity, currentMotion.z);
            }
        }
    }
}
