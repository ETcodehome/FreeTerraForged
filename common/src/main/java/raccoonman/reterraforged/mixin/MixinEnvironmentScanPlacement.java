package raccoonman.reterraforged.mixin;

import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import raccoonman.reterraforged.world.worldgen.feature.placement.SurfaceFeatureRescue;

@Mixin(EnvironmentScanPlacement.class)
class MixinEnvironmentScanPlacement {

	@Inject(method = "getPositions", at = @At("RETURN"), cancellable = true)
	private void reterraforged$rescueFailedSurfaceScan(
		PlacementContext context,
		RandomSource random,
		BlockPos origin,
		CallbackInfoReturnable<Stream<BlockPos>> callback
	) {
		callback.setReturnValue(SurfaceFeatureRescue.rescue(
			(EnvironmentScanPlacement)(Object)this,
			origin,
			callback.getReturnValue()
		));
	}
}
