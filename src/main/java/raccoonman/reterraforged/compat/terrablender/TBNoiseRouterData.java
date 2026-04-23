package raccoonman.reterraforged.compat.terrablender;

import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import raccoonman.reterraforged.world.worldgen.cell.CellField;
import raccoonman.reterraforged.world.worldgen.densityfunction.RTFDensityFunctions;
import terrablender.core.TerraBlender;

public class TBNoiseRouterData {
	public static final ResourceKey<DensityFunction> UNIQUENESS = ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.fromNamespaceAndPath(TerraBlender.MOD_ID, "uniqueness"));
	
	public static void bootstrap(BootstrapContext<DensityFunction> ctx) {
		ctx.register(UNIQUENESS, RTFDensityFunctions.cell(CellField.BIOME_REGION));
	}
}
