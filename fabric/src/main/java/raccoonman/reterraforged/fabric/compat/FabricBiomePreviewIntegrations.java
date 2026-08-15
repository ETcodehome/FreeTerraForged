package raccoonman.reterraforged.fabric.compat;

import net.fabricmc.loader.api.FabricLoader;
import raccoonman.reterraforged.fabric.compat.biolith.BiolithBiomePreviewIntegration;
import raccoonman.reterraforged.fabric.compat.lithostitched.LithostitchedBiomePreviewIntegration;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewIntegrations;

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
