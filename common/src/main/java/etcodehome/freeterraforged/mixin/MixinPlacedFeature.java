package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.world.worldgen.feature.placement.SurfaceFeatureRescue;
import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import etcodehome.freeterraforged.world.worldgen.feature.placement.ChunkLocalFeaturePlacement;
import etcodehome.freeterraforged.world.worldgen.feature.placement.SurfaceFeatureRescue;

@Mixin(PlacedFeature.class)
class MixinPlacedFeature {

	@WrapMethod(method = "placeWithContext")
	private boolean freeterraforged$manageSurfaceFeature(
		PlacementContext context,
		RandomSource random,
		BlockPos origin,
		Operation<Boolean> original
	) {
		PlacedFeature feature = (PlacedFeature)(Object)this;
		boolean chunkLocalScope = ChunkLocalFeaturePlacement.begin(feature, context, origin);
		try {
			boolean rescueScope = SurfaceFeatureRescue.begin(feature, context);
			try {
				return original.call(context, random, origin);
			} finally {
				SurfaceFeatureRescue.finish(rescueScope);
			}
		} finally {
			ChunkLocalFeaturePlacement.finish(chunkLocalScope);
		}
	}
}
