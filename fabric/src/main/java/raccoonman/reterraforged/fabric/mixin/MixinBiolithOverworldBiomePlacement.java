package raccoonman.reterraforged.fabric.mixin;

import com.terraformersmc.biolith.impl.biome.OverworldBiomePlacement;
import com.terraformersmc.biolith.impl.noise.OpenSimplexNoise2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import raccoonman.reterraforged.fabric.compat.biolith.BiolithPreviewContext;

/** Redirects Biolith's seed state to a thread-local source only while a preview integration is open. */
@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.OverworldBiomePlacement", remap = false)
public abstract class MixinBiolithOverworldBiomePlacement {
	@Redirect(
		method = "getLocalNoise",
		at = @At(
			value = "FIELD",
			target = "Lcom/terraformersmc/biolith/impl/biome/OverworldBiomePlacement;replacementNoise:Lcom/terraformersmc/biolith/impl/noise/OpenSimplexNoise2;"
		),
		remap = false
	)
	private OpenSimplexNoise2 reterraforged$previewReplacementNoise(OverworldBiomePlacement placement) {
		OpenSimplexNoise2 original = ((BiolithDimensionBiomePlacementAccessor) placement)
			.reterraforged$getReplacementNoise();
		return BiolithPreviewContext.replacementNoise(original);
	}

	@Redirect(
		method = "getLocalNoise",
		at = @At(
			value = "FIELD",
			target = "Lcom/terraformersmc/biolith/impl/biome/OverworldBiomePlacement;seedlets:[I"
		),
		remap = false
	)
	private int[] reterraforged$previewSeedlets(OverworldBiomePlacement placement) {
		int[] original = ((BiolithDimensionBiomePlacementAccessor) placement).reterraforged$getSeedlets();
		return BiolithPreviewContext.seedlets(original);
	}
}
