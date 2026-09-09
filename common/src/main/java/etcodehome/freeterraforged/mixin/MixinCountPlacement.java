package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.world.worldgen.feature.placement.SurfaceFeatureRescue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.CountPlacement;

@Mixin(CountPlacement.class)
class MixinCountPlacement {

	@Inject(method = "count", at = @At("RETURN"))
	private void reterraforged$recordSurfaceFeatureCount(
		RandomSource random,
		BlockPos origin,
		CallbackInfoReturnable<Integer> callback
	) {
		SurfaceFeatureRescue.recordCount(
			(CountPlacement)(Object)this,
			callback.getReturnValue()
		);
	}
}
