package etcodehome.freeterraforged.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import etcodehome.freeterraforged.world.worldgen.feature.SnowVegetation;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SnowAndFreezeFeature.class)
class MixinSnowAndFreezeFeature {
	@WrapOperation(method = "place", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/world/level/biome/Biome;shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"),
		require = 1, allow = 1)
	private boolean freeterraforged$coverVegetation(
		Biome biome, LevelReader level, BlockPos pos, Operation<Boolean> original,
		@Local(argsOnly = true) FeaturePlaceContext<?> context
	) {
		// Leave successful vanilla/modded checks and the feature's actual block writes intact.
		return original.call(biome, level, pos)
			|| (context.chunkGenerator() instanceof TerraForgedChunkGenerator
				&& SnowVegetation.canCover(biome, level, pos));
	}
}
