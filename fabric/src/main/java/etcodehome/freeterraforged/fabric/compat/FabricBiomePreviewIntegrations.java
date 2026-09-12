package etcodehome.freeterraforged.fabric.compat;

import etcodehome.freeterraforged.fabric.compat.biolith.BiolithBiomePreviewIntegration;
import etcodehome.freeterraforged.fabric.compat.lithostitched.LithostitchedBiomePreviewIntegration;
import etcodehome.freeterraforged.world.worldgen.biome.BiomePreviewIntegrations;
import net.fabricmc.loader.api.FabricLoader;

/** Registers optional Fabric biome preview adapters without linking the core preview to them. */
public final class FabricBiomePreviewIntegrations {
	private static boolean bootstrapped;

	private FabricBiomePreviewIntegrations() {
	}

	public static synchronized void bootstrap() {
		if (bootstrapped) {
			return;
		}
		bootstrapped = true;
		if (FabricLoader.getInstance().isModLoaded("biolith")) {
			BiolithRegistration.register();
		}
		if (FabricLoader.getInstance().isModLoaded("lithostitched")) {
			LithostitchedRegistration.register();
		}
	}

	private static final class BiolithRegistration {
		private static void register() {
			BiomePreviewIntegrations.register(new BiolithBiomePreviewIntegration());
		}
	}

	private static final class LithostitchedRegistration {
		private static void register() {
			BiomePreviewIntegrations.register(new LithostitchedBiomePreviewIntegration());
		}
	}
}
