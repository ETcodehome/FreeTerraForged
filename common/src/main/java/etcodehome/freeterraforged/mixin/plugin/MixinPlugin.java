package etcodehome.freeterraforged.mixin.plugin;

import java.util.List;
import java.util.Set;

import etcodehome.freeterraforged.compat.biolith.BiolithCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.terrablender.TBCompat;

public class MixinPlugin implements IMixinConfigPlugin {

	@Override
	public void onLoad(String mixinPackage) {
		if(TBCompat.isEnabled()) {
			FTFCommon.LOGGER.info("Enabling Terrablender compat");
		} else {
			FTFCommon.LOGGER.info("Disabling Terrablender compat");
		}
		if(BiolithCompat.isEnabled()) {
			FTFCommon.LOGGER.info("Enabling Biolith preview compat");
		} else {
			FTFCommon.LOGGER.info("Disabling Biolith preview compat");
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (TBCompat.isTBMixin(mixinClassName)) return TBCompat.isEnabled();
		if (BiolithCompat.isBiolithMixin(mixinClassName)) return BiolithCompat.isEnabled();
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return TBCompat.TERRABLENDER_COMPAT_MIXINS.stream().map((str) -> {
			return str.replace("etcodehome.freeterraforged.mixin.", "");
		}).toList();
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
