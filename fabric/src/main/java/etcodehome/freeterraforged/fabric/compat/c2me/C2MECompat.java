package etcodehome.freeterraforged.fabric.compat.c2me;

import java.util.List;

import net.fabricmc.loader.api.FabricLoader;

public final class C2MECompat {
	public static final String OPENCL_MODULE_ID = "c2me-opts-accel-opencl";
	public static final List<String> OPENCL_COMPAT_MIXINS = List.of("MixinOpenCLCGen", "MixinCLDataUtil", "MixinGeneratedCLSource");

	private C2MECompat() {
	}

	public static boolean isOpenCLModuleLoaded() {
		try {
			return FabricLoader.getInstance().isModLoaded(OPENCL_MODULE_ID);
		} catch (Throwable t) {
			return false;
		}
	}
}
