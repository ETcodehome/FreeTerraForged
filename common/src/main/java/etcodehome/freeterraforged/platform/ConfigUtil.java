package etcodehome.freeterraforged.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.architectury.injectables.annotations.ExpectPlatform;
import etcodehome.freeterraforged.FTFCommon;

public class ConfigUtil {
	public static final Path FTF_CONFIG_PATH = getConfigPath().resolve(FTFCommon.MOD_ID);
	public static final Path LEGACY_TF_CONFIG_PATH = getConfigPath().resolve(FTFCommon.LEGACY_TF_MOD_ID);
	public static final Path LEGACY_RTF_CONFIG_PATH = getConfigPath().resolve(FTFCommon.LEGACY_RTF_MOD_ID);
	
	public static Path ftf(String path) {
		return FTF_CONFIG_PATH.resolve(path);
	}
	
	public static Path legacy_tf(String path) {
		return LEGACY_TF_CONFIG_PATH.resolve(path);
	}
	public static Path legacy_rtf(String path) {
		return LEGACY_RTF_CONFIG_PATH.resolve(path);
	}
	
	@ExpectPlatform
	public static Path getConfigPath() {
		throw new IllegalStateException();
	}
	
	static {
		if(!Files.exists(FTF_CONFIG_PATH)) {
			try {
				Files.createDirectory(FTF_CONFIG_PATH);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
