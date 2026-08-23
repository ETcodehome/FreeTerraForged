package raccoonman.reterraforged.mixin;

import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.FanoutStage;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlacement;

@Mixin(RepeatingPlacement.class)
class MixinRepeatingPlacement {

	@Inject(method = "getPositions", at = @At("RETURN"), cancellable = true)
	private void reterraforged$fanOutDynamicOreCount(
		PlacementContext context,
		RandomSource random,
		BlockPos origin,
		CallbackInfoReturnable<Stream<BlockPos>> callback
	) {
		if ((Object)this instanceof CountPlacement) {
			DynamicOrePlacement.getFanoutPositions(
				(PlacementModifier)(Object)this,
				context,
				random,
				callback.getReturnValue(),
				FanoutStage.COUNT
			).ifPresent(callback::setReturnValue);
		}
	}
}
