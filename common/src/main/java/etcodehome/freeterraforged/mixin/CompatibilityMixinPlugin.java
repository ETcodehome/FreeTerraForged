package etcodehome.freeterraforged.mixin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import etcodehome.freeterraforged.platform.ModLoaderUtil;

public final class CompatibilityMixinPlugin implements IMixinConfigPlugin {
	private static final Set<String> BIOLITH_VERSIONS = Set.of("3.0.11", "3.0.14");
	private static final AtomicBoolean LITHOSTITCHED_WARNING_LOGGED = new AtomicBoolean();

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
			LithostitchedBridgeContract.Assessment assessment = LithostitchedBridgeContract.current();
			if (ModLoaderUtil.isLoaded("lithostitched") && !assessment.supported()
				&& LITHOSTITCHED_WARNING_LOGGED.compareAndSet(false, true)) {
				LogManager.getLogger("FreeTerraForged").warn(
					"Lithostitched code-listener bridge is unavailable: {}", assessment.reason()
				);
			}
			return assessment.supported();
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
