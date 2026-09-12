package etcodehome.freeterraforged.data.worldgen.preset;

import java.util.ArrayList;
import java.util.List;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.tags.FTFBlockTags;
import etcodehome.freeterraforged.world.worldgen.surface.rule.FTFSurfaceRules;
import etcodehome.freeterraforged.world.worldgen.surface.rule.StrataRule;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.data.worldgen.SurfaceRuleData;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.SurfaceRules;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public class PresetSurfaceRuleData {
    
    public static SurfaceRules.RuleSource overworld(Preset preset, HolderGetter<DensityFunction> densityFunctions, HolderGetter<Noise> noise) {
		if (preset.miscellaneous().strataDecorator) {
			return SurfaceRules.sequence(SurfaceRuleData.overworld(), makeStrataRule(noise));
		}
		return SurfaceRules.sequence(SurfaceRuleData.overworld());
    }
    
	private static SurfaceRules.RuleSource makeStrataRule(HolderGetter<Noise> noise) {
		Holder<Noise> depth = noise.getOrThrow(PresetStrataNoise.STRATA_DEPTH);

		List<StrataRule.Strata> strata = new ArrayList<>();
		strata.add(new StrataRule.Strata(FTFBlockTags.SOIL, depth, 3, 0, 1, 0.1F, 0.25F));
		strata.add(new StrataRule.Strata(FTFBlockTags.SEDIMENT, depth, 3, 0, 2, 0.05F, 0.15F));
		strata.add(new StrataRule.Strata(FTFBlockTags.CLAY, depth, 3, 0, 2, 0.05F, 0.1F));
		strata.add(new StrataRule.Strata(FTFBlockTags.ROCK, depth, 3, 10, 30, 0.1F, 1.5F));
		return FTFSurfaceRules.strata(FTFCommon.location("overworld_strata"), noise.getOrThrow(PresetStrataNoise.STRATA_SELECTOR), strata, 100);
	}
}
