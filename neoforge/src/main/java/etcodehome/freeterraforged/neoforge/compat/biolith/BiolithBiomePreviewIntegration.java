package etcodehome.freeterraforged.neoforge.compat.biolith;

import etcodehome.freeterraforged.compat.biolith.BiolithPreviewContext;
import etcodehome.freeterraforged.world.worldgen.biome.BiomePreviewIntegration;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

public final class BiolithBiomePreviewIntegration implements BiomePreviewIntegration {
	@Override
	public String id() {
		return "freeterraforged:biolith-neoforge";
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
