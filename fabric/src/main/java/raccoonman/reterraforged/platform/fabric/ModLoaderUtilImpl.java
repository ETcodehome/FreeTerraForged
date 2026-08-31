package raccoonman.reterraforged.platform.fabric;

import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;

public final class ModLoaderUtilImpl {
	
	public static boolean isLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	public static Optional<String> version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
			.map(container -> container.getMetadata().getVersion().getFriendlyString());
	}
}
