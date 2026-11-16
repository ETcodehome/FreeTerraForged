package etcodehome.freeterraforged.platform.forge;

import net.minecraft.server.packs.PackResources;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.resource.DelegatingPackResources;
import net.minecraftforge.resource.PathPackResources;

public final class ResourcePackUtilImpl {
	private ResourcePackUtilImpl() {}

	public static boolean isBundledModPack(PackResources pack) {
		if (pack instanceof DelegatingPackResources delegating && "mod_resources".equals(delegating.packId())) {
			return !delegating.getChildren().isEmpty()
				&& delegating.getChildren().stream().allMatch(ResourcePackUtilImpl::isBundledModPack);
		}
		if (!(pack instanceof PathPackResources) || !pack.isBuiltin()) {
			return false;
		}
		ModList mods = ModList.get();
		if (mods == null) {
			return false;
		}
		String packId = pack.packId();
		return mods.getModFiles().stream().anyMatch(file ->
			packId.equals(file.getFile().getFileName())
				|| file.getMods().stream().anyMatch(mod -> packId.equals("mod:" + mod.getModId()))
		);
	}
}
