package etcodehome.freeterraforged.data.worldgen.compat.terrablender;

import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.densityfunction.FTFDensityFunctions;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;

public class TBNoiseRouterData {
	public static final ResourceKey<DensityFunction> UNIQUENESS = ResourceKey.create(Registries.DENSITY_FUNCTION, new ResourceLocation("terrablender","uniqueness"));
	
	public static void bootstrap(BootstapContext<DensityFunction> ctx) {
		ctx.register(UNIQUENESS, FTFDensityFunctions.cell(CellSampler.Field.BIOME_REGION));
	}
}
