package etcodehome.freeterraforged.fabric.compat.c2me;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import etcodehome.freeterraforged.FTFCommon;

public class C2MEMixinPlugin implements IMixinConfigPlugin {

	@Override
	public void onLoad(String mixinPackage) {
		if(C2MECompat.isOpenCLModuleLoaded()) {
			FTFCommon.LOGGER.info("Enabling C2ME OpenCL compat");
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return C2MECompat.isOpenCLModuleLoaded();
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		if(C2MECompat.isOpenCLModuleLoaded()) {
			return C2MECompat.OPENCL_COMPAT_MIXINS;
		}
		return List.of();
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
