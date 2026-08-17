package raccoonman.reterraforged.compat.biolith;

import java.util.List;

import com.google.common.collect.ImmutableList;

import raccoonman.reterraforged.platform.ModLoaderUtil;

public class BiolithCompat {
	public static final List<String> BIOLITH_COMPAT_MIXINS = ImmutableList.of(
		mixinClass("biolith.BiolithDimensionBiomePlacementAccessor"),
		mixinClass("biolith.BiolithReplacementRequestAccessor"),
		mixinClass("biolith.BiolithReplacementRequestSetAccessor"),
		mixinClass("biolith.BiolithSubBiomeRequestAccessor"),
		mixinClass("biolith.BiolithSubBiomeRequestSetAccessor"),
		mixinClass("biolith.MixinBiolithDimensionBiomePlacement"),
		mixinClass("biolith.MixinBiolithOverworldBiomePlacement")
	);

	public static boolean isEnabled() {
		return ModLoaderUtil.isLoaded("biolith");
	}

	public static boolean isBiolithMixin(String mixinClassName) {
		return BIOLITH_COMPAT_MIXINS.contains(mixinClassName);
	}

	private static String mixinClass(String className) {
		return "raccoonman.reterraforged.mixin." + className;
	}
}
