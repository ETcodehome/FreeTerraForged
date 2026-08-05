package raccoonman.reterraforged.neoforge.mixin;

import java.util.Objects;

import com.terraformersmc.biolith.api.biome.BiolithFittestNodes;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raccoonman.reterraforged.world.worldgen.terrablender.TerraBlenderParameterList;

/** Makes RTF banding the base candidate that Biolith replacements and sub-biomes compose over. */
@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.compat.TerraBlenderCompatNeoForge", remap = false)
public abstract class MixinBiolithTerraBlenderCompat {
	@Inject(method = "getBiome", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private void reterraforged$composeBandingBeforeBiolith(
		int x,
		int y,
		int z,
		Climate.TargetPoint target,
		Climate.ParameterList<Holder<Biome>> parameters,
		CallbackInfoReturnable<BiolithFittestNodes<Holder<Biome>>> callback
	) {
		BiolithFittestNodes<Holder<Biome>> result = callback.getReturnValue();
		if (result == null || !((Object) parameters instanceof TerraBlenderParameterList<?> terraBlenderParameters)) {
			return;
		}

		@SuppressWarnings("unchecked")
		Holder<Biome> banded = ((TerraBlenderParameterList<Holder<Biome>>) terraBlenderParameters)
			.reterraforged$applyUndergroundBanding(target, x, y, z, result.ultimate().value);
		if (Objects.equals(banded, result.ultimate().value)) {
			return;
		}

		Climate.Parameter[] parametersSpace = result.ultimate().parameterSpace;
		if (parametersSpace.length != 7) {
			return;
		}
		Climate.ParameterPoint point = new Climate.ParameterPoint(
			parametersSpace[0], parametersSpace[1], parametersSpace[2], parametersSpace[3],
			parametersSpace[4], parametersSpace[5], parametersSpace[6].min()
		);
		Climate.RTree.Leaf<Holder<Biome>> ultimate = new Climate.RTree.Leaf<>(point, banded);
		callback.setReturnValue(new BiolithFittestNodes<>(
			ultimate,
			result.ultimateDistance(),
			result.penultimate(),
			result.penultimateDistance()
		));
	}
}
