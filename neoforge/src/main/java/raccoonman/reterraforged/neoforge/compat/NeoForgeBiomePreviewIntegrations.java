package raccoonman.reterraforged.neoforge.compat;

import net.neoforged.fml.ModList;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.compat.biolith.BiolithPreviewCapabilities;
import raccoonman.reterraforged.neoforge.compat.biolith.BiolithBiomePreviewIntegration;
import raccoonman.reterraforged.neoforge.compat.lithostitched.LithostitchedBiomePreviewIntegration;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewIntegrations;

public final class NeoForgeBiomePreviewIntegrations {
	private static final String BIOLITH = "biolith";
	private static final String LITHOSTITCHED = "lithostitched";
	private static boolean bootstrapped;

	private NeoForgeBiomePreviewIntegrations() {
	}

	public static synchronized void bootstrap() {
		if (bootstrapped) {
			return;
		}
		bootstrapped = true;
		if (isBiolithLoaded()) {
			if (BiolithPreviewCapabilities.isAvailable()) {
				BiomePreviewIntegrations.register(new BiolithBiomePreviewIntegration());
			} else {
				RTFCommon.LOGGER.warn(
					"NeoForge Biolith preview integration is unavailable: the installed Biolith does not expose the required placement state."
				);
			}
		}
		if (isLithostitchedLoaded()) {
			BiomePreviewIntegrations.register(new LithostitchedBiomePreviewIntegration());
		}
	}

	public static boolean isBiolithLoaded() {
		return ModList.get().isLoaded(BIOLITH);
	}

	public static boolean isLithostitchedLoaded() {
		return ModList.get().isLoaded(LITHOSTITCHED);
	}
}
