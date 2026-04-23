package raccoonman.reterraforged.platform;

import net.neoforged.fml.ModList;

public class ModLoaderUtil {

	/**
	 * Checks if a mod is loaded using the NeoForge ModList.
	 * This replaces the @ExpectPlatform Architectury call.
	 *
	 * @param modId The namespace/modId to check (e.g., "terrablender")
	 * @return true if the mod is present and active in the current loading session.
	 */
	public static boolean isLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}
}
