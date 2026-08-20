package raccoonman.reterraforged.mixin;

import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlacement;

@Mixin(InSquarePlacement.class)
class MixinInSquarePlacement {

	@Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
	private void reterraforged$fanOutDynamicOreHorizontalSamples(
		PlacementContext context,
		RandomSource random,
		BlockPos origin,
		CallbackInfoReturnable<Stream<BlockPos>> callback
	) {
		DynamicOrePlacement.getInSquarePositions(
			(PlacementModifier)(Object)this,
			context,
			random,
			origin
		).ifPresent(callback::setReturnValue);
	}
}
