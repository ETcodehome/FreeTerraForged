package raccoonman.reterraforged.mixin;

import java.util.stream.Stream;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import raccoonman.reterraforged.world.worldgen.feature.placement.ChunkLocalFeaturePlacement;

@Mixin(RandomOffsetPlacement.class)
class MixinRandomOffsetPlacement {
	@ModifyReturnValue(method = "getPositions", at = @At("RETURN"))
	private Stream<BlockPos> reterraforged$constrainCompiledChunkLocalOffset(
		Stream<BlockPos> original,
		PlacementContext context,
		RandomSource random,
		BlockPos origin
	) {
		return ChunkLocalFeaturePlacement.constrain(
			(RandomOffsetPlacement)(Object)this,
			original
		);
	}
}
