package raccoonman.reterraforged.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import raccoonman.reterraforged.platform.ModLoaderUtil;

public final class CompatibilityMixinPlugin implements IMixinConfigPlugin {
	private static final Set<String> BIOLITH_VERSIONS = Set.of("3.0.11", "3.0.14");
	private static final Set<String> LITHOSTITCHED_VERSIONS = Set.of(
		"1.8.0+beta4", "1.8.0+beta5"
	);

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".compat.MixinBiolithDimensionBiomePlacement")) {
			return ModLoaderUtil.version("biolith").filter(BIOLITH_VERSIONS::contains).isPresent();
		}
		if (mixinClassName.endsWith(".compat.MixinLithostitchedBiomeInjectorManager")
			|| mixinClassName.endsWith(".compat.MixinLithostitchedEvent")) {
			return ModLoaderUtil.version("lithostitched")
				.filter(LITHOSTITCHED_VERSIONS::contains)
				.isPresent();
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
