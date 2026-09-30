package etcodehome.freeterraforged.platform.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackResources;

public final class ResourcePackUtilImpl {
	private ResourcePackUtilImpl() {}

	public static boolean isBundledModPack(PackResources pack) {
		// Fabric's root mod pack uses its container ID; registered optional packs use resource IDs.
		// KnownPack survives overlay wrapping, unlike a test of the resource implementation class.
		return pack.knownPackInfo().filter(known -> known.namespace().equals("fabric")
			&& known.id().equals(pack.packId())
			&& FabricLoader.getInstance().getModContainer(known.id()).isPresent()).isPresent();
	}
}
