package raccoonman.reterraforged.fabric.mixin;

import com.mojang.datafixers.util.Pair;
import com.terraformersmc.biolith.api.biome.BiolithFittestNodes;
import com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raccoonman.reterraforged.fabric.compat.biolith.BiolithPreviewContext;

/** Routes Biolith selection through an isolated, finalized request snapshot during previews. */
@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement", remap = false)
public abstract class MixinBiolithDimensionBiomePlacement {
	@Shadow(remap = false)
	@Final
	protected static ThreadLocal<Vec3i> EVALUATING_BIOME_POS;

	@Inject(method = "getReplacement", at = @At("HEAD"), cancellable = true, remap = false)
	private void reterraforged$getPreviewReplacement(
		int x,
		int y,
		int z,
		Climate.TargetPoint target,
		BiolithFittestNodes<Holder<Biome>> nodes,
		CallbackInfoReturnable<Holder<Biome>> callback
	) {
		if (BiolithPreviewContext.isActive()) {
			EVALUATING_BIOME_POS.set(new BlockPos(x, y, z));
			callback.setReturnValue(BiolithPreviewContext.getReplacement(
				(DimensionBiomePlacement) (Object) this,
				x,
				y,
				z,
				target,
				nodes
			));
		}
	}

	@Inject(method = "getReplacementEntry", at = @At("HEAD"), cancellable = true, remap = false)
	private void reterraforged$getPreviewReplacementEntry(
		int x,
		int y,
		int z,
		Holder<Biome> biome,
		CallbackInfoReturnable<Holder<Biome>> callback
	) {
		if (BiolithPreviewContext.isActive()) {
			callback.setReturnValue(BiolithPreviewContext.getReplacementEntry(
				(DimensionBiomePlacement) (Object) this,
				x,
				y,
				z,
				biome
			));
		}
	}

	@Inject(method = "getReplacementPair", at = @At("HEAD"), cancellable = true, remap = false)
	private void reterraforged$getPreviewReplacementPair(
		ResourceKey<Biome> biome,
		float noise,
		CallbackInfoReturnable<Pair<ResourceKey<Biome>, Holder<Biome>>> callback
	) {
		if (BiolithPreviewContext.isActive()) {
			callback.setReturnValue(BiolithPreviewContext.getReplacementPair(
				(DimensionBiomePlacement) (Object) this,
				biome,
				noise
			));
		}
	}
}
