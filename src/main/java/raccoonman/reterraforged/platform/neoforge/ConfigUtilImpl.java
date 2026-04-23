package raccoonman.reterraforged.platform.neoforge;

import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

public final class ConfigUtilImpl {

	public static Path getConfigPath() {
		return FMLPaths.CONFIGDIR.get();
	}
}