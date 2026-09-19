package etcodehome.freeterraforged.data.worldgen.tags;

import java.util.concurrent.CompletableFuture;

import etcodehome.freeterraforged.data.worldgen.compat.terrablender.TBNoiseRouterData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseRouterData;
import etcodehome.freeterraforged.tags.FTFDensityFunctionTags;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.world.level.levelgen.DensityFunction;

public class FTFDensityFunctionTagsProvider extends TagsProvider<DensityFunction> {

	public FTFDensityFunctionTagsProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> completableFuture) {
		super(packOutput, Registries.DENSITY_FUNCTION, completableFuture);
	}

	@Override
	protected void addTags(HolderLookup.Provider provider) {
		this.tag(FTFDensityFunctionTags.ADDITIONAL_NOISE_ROUTER_FUNCTIONS).add(PresetNoiseRouterData.GRADIENT, PresetNoiseRouterData.HEIGHT_EROSION, PresetNoiseRouterData.SEDIMENT, TBNoiseRouterData.UNIQUENESS);
	}
}
