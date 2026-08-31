package raccoonman.reterraforged.neoforge.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import raccoonman.reterraforged.platform.ModLoaderUtil;

public final class NeoForgeOptionalMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.contains("Biolith")) {
			// NOTE: previously used NeoForgeBiomePreviewIntegrations.isBiolithLoaded()
			// (net.neoforged.fml.ModList#isLoaded), which throws until mods have been
			// constructed. shouldApplyMixin runs whenever the JVM loads the target
			// class - here, Biolith's own TerraBlenderCompatNeoForge, on Biolith's
			// schedule rather than ours - and that can happen before ModList is
			// populated, silently disabling this mixin for the rest of the session.
			// ModLoaderUtil (backed by LoadingModList, populated during early mod
			// discovery) is safe to query this early, and is what every other
			// Biolith-gated mixin in the common module already uses.
			return ModLoaderUtil.isLoaded("biolith");
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return List.of();
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}