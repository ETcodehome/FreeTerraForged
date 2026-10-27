package etcodehome.freeterraforged.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.packs.PackResources;

public final class ResourcePackUtil {
	private ResourcePackUtil() {}

	@ExpectPlatform
	public static boolean isBundledModPack(PackResources pack) {
		throw new IllegalStateException();
	}
}
