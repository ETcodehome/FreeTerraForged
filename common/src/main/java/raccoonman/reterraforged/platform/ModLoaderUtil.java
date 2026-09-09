package raccoonman.reterraforged.platform;

import java.util.Optional;

import dev.architectury.injectables.annotations.ExpectPlatform;

public class ModLoaderUtil {
	
	@ExpectPlatform
	public static boolean isLoaded(String modId) {
		throw new IllegalStateException();
	}

	@ExpectPlatform
	public static Optional<String> version(String modId) {
		throw new IllegalStateException();
	}
}
