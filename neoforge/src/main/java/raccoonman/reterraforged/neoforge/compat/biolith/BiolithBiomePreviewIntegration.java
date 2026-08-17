package raccoonman.reterraforged.neoforge.compat.biolith;

import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import raccoonman.reterraforged.compat.biolith.BiolithPreviewContext;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewIntegration;

public final class BiolithBiomePreviewIntegration implements BiomePreviewIntegration {
	@Override
	public String id() {
		return "reterraforged:biolith-neoforge";
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
