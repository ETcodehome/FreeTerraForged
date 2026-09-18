package etcodehome.freeterraforged.mixin;

import java.util.stream.Stream;

import etcodehome.freeterraforged.world.worldgen.feature.placement.DynamicHeightRangePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import etcodehome.freeterraforged.world.worldgen.feature.ore.DynamicOrePlacement;

@Mixin(HeightRangePlacement.class)
class MixinHeightRangePlacement {

	@Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
	private void freeterraforged$expandCanonicalTerrainRange(
		PlacementContext context,
		RandomSource random,
		BlockPos origin,
		CallbackInfoReturnable<Stream<BlockPos>> callback
	) {
		var orePositions = DynamicOrePlacement.getHeightPositions(
			(HeightRangePlacement)(Object)this,
			context,
			random,
			origin
		);
		if (orePositions.isPresent()) {
			callback.setReturnValue(orePositions.orElseThrow());
			return;
		}
		if (DynamicOrePlacement.isStandardOrePlacement((HeightRangePlacement)(Object)this, context)) {
			return;
		}
		DynamicHeightRangePlacement.getPositions(
			(HeightRangePlacement)(Object)this,
			context,
			random,
			origin
		).ifPresent(callback::setReturnValue);
	}
}
