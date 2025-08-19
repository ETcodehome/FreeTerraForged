package raccoonman.reterraforged.platform.neoforge;

import net.neoforged.fml.loading.LoadingModList;

public class ModLoaderUtilImpl {
	
	public static boolean isLoaded(String modId) {
		return LoadingModList.get().getModFileById(modId) != null;
	}
}
