package raccoonman.reterraforged.platform.neoforge;

import java.util.Optional;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

public class ModLoaderUtilImpl {
	
	public static boolean isLoaded(String modId) {
		return LoadingModList.get().getModFileById(modId) != null;
	}

	public static Optional<String> version(String modId) {
		return ModList.get().getModContainerById(modId)
			.map(container -> container.getModInfo().getVersion().toString());
	}
}
