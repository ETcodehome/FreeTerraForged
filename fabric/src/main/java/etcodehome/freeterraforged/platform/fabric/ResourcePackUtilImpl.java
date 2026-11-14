package etcodehome.freeterraforged.platform.fabric;

import net.fabricmc.fabric.api.resource.ModResourcePack;
import net.fabricmc.fabric.impl.resource.loader.FabricModResourcePack;
import net.minecraft.server.packs.PackResources;

public final class ResourcePackUtilImpl {
	private ResourcePackUtilImpl() {}

	public static boolean isBundledModPack(PackResources pack) {
		if (pack instanceof FabricModResourcePack) {
			return true;
		}
		// Fabric's root mod pack uses its container ID; registered optional packs use resource IDs.
		return pack instanceof ModResourcePack modPack
			&& modPack.getFabricModMetadata().getId().equals(pack.packId());
	}
}
