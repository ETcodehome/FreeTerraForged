package etcodehome.freeterraforged.world.worldgen.terrablender;

import net.minecraft.core.Holder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import terrablender.DimensionTypeTags;
import terrablender.api.SurfaceRuleManager;
import terrablender.worldgen.IExtendedNoiseGeneratorSettings;
import etcodehome.freeterraforged.platform.ModLoaderUtil;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenOwner;

public final class TerraBlenderSurfaceBridge {
	private TerraBlenderSurfaceBridge() {
	}

	public static void bindSurfaceCategory(WorldgenOwner owner, NoiseBasedChunkGenerator generator) {
		if (!ModLoaderUtil.isLoaded("terrablender")) {
			return;
		}
		bindSurfaceCategory(owner.selectedStem().type(), generator.generatorSettings().value());
	}

	public static void bindSurfaceCategory(Holder<DimensionType> dimensionType, NoiseGeneratorSettings settings) {
		if (((Object) settings) instanceof IExtendedNoiseGeneratorSettings extended) {
			if (dimensionType.is(DimensionTypeTags.OVERWORLD_REGIONS)) {
				extended.setRuleCategory(SurfaceRuleManager.RuleCategory.OVERWORLD);
			} else if (dimensionType.is(DimensionTypeTags.NETHER_REGIONS)) {
				extended.setRuleCategory(SurfaceRuleManager.RuleCategory.NETHER);
			}
		}
	}
}
