package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import raccoonman.reterraforged.world.worldgen.feature.placement.SurfaceFeatureRescue;

@Mixin(PlacedFeature.class)
class MixinPlacedFeature {

	@WrapMethod(method = "placeWithContext")
	private boolean reterraforged$manageSurfaceFeature(
		PlacementContext context,
		RandomSource random,
		BlockPos origin,
		Operation<Boolean> original
	) {
		boolean rescueScope = SurfaceFeatureRescue.begin((PlacedFeature)(Object)this, context);
		try {
			return original.call(context, random, origin);
		} finally {
			SurfaceFeatureRescue.finish(rescueScope);
		}
	}
}
