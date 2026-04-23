package raccoonman.reterraforged.platform.neoforge;

// Corrected: removed the extra '.neoforge'
import net.neoforged.fml.ModList;

public class ModLoaderUtilImpl {

	public static boolean isLoaded(String modId) {
		// ModList.get() is the standard way to check for mod presence in 1.21.1
		return ModList.get().isLoaded(modId);
	}
}