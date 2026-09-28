package etcodehome.freeterraforged.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.packs.PackResources;

/** Acquisition-only provenance. An unrecognized origin always retains replacement semantics. */
public final class ResourcePackUtil {
	private ResourcePackUtil() {}

	@ExpectPlatform
	public static boolean isBundledModPack(PackResources pack) {
		throw new IllegalStateException();
	}
}
