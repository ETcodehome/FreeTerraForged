package etcodehome.freeterraforged.forge.compat;

import etcodehome.freeterraforged.compat.biolith.BiolithPreviewCapabilities;
import etcodehome.freeterraforged.forge.compat.biolith.BiolithBiomePreviewIntegration;
import etcodehome.freeterraforged.forge.compat.lithostitched.LithostitchedBiomePreviewIntegration;
import etcodehome.freeterraforged.world.worldgen.biome.BiomePreviewIntegrations;
import net.minecraftforge.fml.ModList;
import etcodehome.freeterraforged.FTFCommon;

public final class ForgeBiomePreviewIntegrations {
	private static final String BIOLITH = "biolith";
	private static final String LITHOSTITCHED = "lithostitched";
	private static boolean bootstrapped;

	private ForgeBiomePreviewIntegrations() {
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
				FTFCommon.LOGGER.warn(
					"Forge Biolith preview integration is unavailable: the installed Biolith does not expose the required placement state."
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
