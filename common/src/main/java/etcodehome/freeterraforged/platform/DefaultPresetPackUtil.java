package etcodehome.freeterraforged.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;

public final class DefaultPresetPackUtil {
	private DefaultPresetPackUtil() {
	}

	@ExpectPlatform
	public static String defaultPresetPackId() {
		throw new IllegalStateException();
	}
}
