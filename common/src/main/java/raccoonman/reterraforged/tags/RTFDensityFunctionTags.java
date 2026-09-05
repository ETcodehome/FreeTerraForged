package raccoonman.reterraforged.tags;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import raccoonman.reterraforged.RTFCommon;

public final class RTFDensityFunctionTags {

	public static final TagKey<DensityFunction> ADDITIONAL_NOISE_ROUTER_FUNCTIONS = resolve("additional_noise_router_functions");
	
    private static TagKey<DensityFunction> resolve(String path) {
    	return TagKey.create(Registries.DENSITY_FUNCTION, RTFCommon.location(path));
    }
}
