package etcodehome.freeterraforged.platform.forge;

import net.minecraftforge.fml.loading.LoadingModList;

public class ModLoaderUtilImpl {

	public static boolean isLoaded(String modId) {
		return LoadingModList.get().getModFileById(modId) != null;
	}
}
