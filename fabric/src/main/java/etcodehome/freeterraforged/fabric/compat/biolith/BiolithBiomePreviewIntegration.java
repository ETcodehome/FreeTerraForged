package etcodehome.freeterraforged.fabric.compat.biolith;

import etcodehome.freeterraforged.compat.biolith.BiolithPreviewContext;
import etcodehome.freeterraforged.world.worldgen.biome.BiomePreviewIntegration;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/** Finalizes a seed-scoped copy of Biolith's pending requests for the current preview worker. */
public final class BiolithBiomePreviewIntegration implements BiomePreviewIntegration {
	@Override
	public String id() {
		return "reterraforged:biolith";
	}

	@Override
	public boolean supports(Context context) {
		return context.biomeSource() instanceof MultiNoiseBiomeSource;
	}

	@Override
	public Session open(Context context) {
		return BiolithPreviewContext.open(context.seed(), context.registries(), context.provider());
	}
}
