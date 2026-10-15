package etcodehome.freeterraforged.tags;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import etcodehome.freeterraforged.FTFCommon;

public final class FTFDensityFunctionTags {
	// This tag marks registered density functions that the runtime must include outside the NoiseRouter.
	public static final TagKey<DensityFunction> ADDITIONAL_NOISE_ROUTER_FUNCTIONS = resolve("additional_noise_router_functions");

    private static TagKey<DensityFunction> resolve(String path) {
	return TagKey.create(Registries.DENSITY_FUNCTION, FTFCommon.location(path));
    }
}
