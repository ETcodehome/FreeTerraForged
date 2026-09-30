package etcodehome.freeterraforged.forge.compat.lithostitched;

import etcodehome.freeterraforged.world.worldgen.biome.BiomePreviewIntegration;

/**
 * In 1.20.1, Lithostitched does not use biome injectors or runtime noise bindings.
 */
public final class LithostitchedBiomePreviewIntegration implements BiomePreviewIntegration {

	@Override
	public String id() {
		return "freeterraforged:lithostitched-forge";
	}

	@Override
	public boolean supports(Context context) {
		return false;
	}

	@Override
	public Session open(Context context) {
		return () -> {};
	}
}
