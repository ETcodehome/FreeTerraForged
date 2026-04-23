package raccoonman.reterraforged.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths; // Use NeoForge's native pathing
import raccoonman.reterraforged.RTFCommon;

public class ConfigUtil {
	public static final Path RTF_CONFIG_PATH = getConfigPath().resolve(RTFCommon.MOD_ID);
	public static final Path LEGACY_CONFIG_PATH = getConfigPath().resolve(RTFCommon.LEGACY_MOD_ID);

	public static Path rtf(String path) {
		return RTF_CONFIG_PATH.resolve(path);
	}

	public static Path legacy(String path) {
		return LEGACY_CONFIG_PATH.resolve(path);
	}

	public static Path getConfigPath() {
		// In NeoForge 1.21.1, this is the standard way to get the config folder
		return FMLPaths.CONFIGDIR.get();
	}

	static {
		try {
			if(!Files.exists(RTF_CONFIG_PATH)) {
				Files.createDirectories(RTF_CONFIG_PATH); // Use createDirectories to handle parents
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}