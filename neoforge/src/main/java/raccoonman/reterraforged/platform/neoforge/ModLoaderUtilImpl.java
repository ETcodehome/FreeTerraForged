package raccoonman.reterraforged.platform.neoforge;

import java.util.Optional;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

public class ModLoaderUtilImpl {
	
	public static boolean isLoaded(String modId) {
		LoadingModList loading = LoadingModList.get();
		if (loading != null) {
			return loading.getModFileById(modId) != null;
		}
		ModList loaded = ModList.get();
		return loaded != null && loaded.isLoaded(modId);
	}

	public static Optional<String> version(String modId) {
		LoadingModList loading = LoadingModList.get();
		if (loading != null) {
			var file = loading.getModFileById(modId);
			if (file != null) {
				return file.getMods().stream()
					.filter(mod -> mod.getModId().equals(modId))
					.findFirst()
					.map(mod -> mod.getVersion().toString());
			}
		}
		ModList loaded = ModList.get();
		return loaded == null ? Optional.empty() : loaded.getModContainerById(modId)
			.map(container -> container.getModInfo().getVersion().toString());
	}
}
