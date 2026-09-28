package etcodehome.freeterraforged.platform.neoforge;

import java.util.stream.Collectors;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.fml.ModList;

public final class ResourcePackUtilImpl {
	private ResourcePackUtilImpl() {}

	public static boolean isBundledModPack(PackResources pack) {
		var known = pack.knownPackInfo();
		if (known.isEmpty() || !known.get().namespace().equals("neoforge")
			|| !known.get().id().equals(pack.packId()) || pack.location().source() == PackSource.WORLD
			|| pack.location().source() == PackSource.SERVER) return false;
		var mods = ModList.get();
		// Pack.withChildren replaces DEFAULT with its private child source, retaining KnownPack identity.
		return mods != null && mods.getModFiles().stream().anyMatch(file -> pack.packId().equals("mod/"
			+ file.getMods().stream().map(mod -> mod.getModId()).collect(Collectors.joining(","))));
	}
}
