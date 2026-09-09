package etcodehome.freeterraforged;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.platform.RegistryUtil;
import etcodehome.freeterraforged.registries.FTFBuiltInRegistries;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifiers;
import etcodehome.freeterraforged.world.worldgen.densityfunction.FTFDensityFunctions;
import etcodehome.freeterraforged.world.worldgen.feature.FTFFeatures;
import etcodehome.freeterraforged.world.worldgen.feature.placement.FTFPlacementModifiers;
import etcodehome.freeterraforged.world.worldgen.feature.template.decorator.TemplateDecorators;
import etcodehome.freeterraforged.world.worldgen.feature.template.placement.TemplatePlacements;
import etcodehome.freeterraforged.world.worldgen.floatproviders.FTFFloatProviderTypes;
import etcodehome.freeterraforged.world.worldgen.heightproviders.FTFHeightProviderTypes;
import etcodehome.freeterraforged.world.worldgen.noise.domain.Domains;
import etcodehome.freeterraforged.world.worldgen.noise.function.CurveFunctions;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.structure.rule.StructureRule;
import etcodehome.freeterraforged.world.worldgen.structure.rule.StructureRules;
import etcodehome.freeterraforged.world.worldgen.surface.rule.FTFSurfaceRules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.resources.ResourceLocation;
import etcodehome.freeterraforged.world.worldgen.feature.chance.FTFChanceModifiers;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public class FTFCommon {
	public static final String MOD_ID = "reterraforged";
	public static final String LEGACY_MOD_ID = "terraforged";
	public static final Logger LOGGER = LogManager.getLogger("ReTerraForged");

	public static void bootstrap() {
		FTFBuiltInRegistries.bootstrap();
		TemplatePlacements.bootstrap();
		TemplateDecorators.bootstrap();
		FTFChanceModifiers.bootstrap();
		FTFPlacementModifiers.bootstrap();
		FTFDensityFunctions.bootstrap();
		Noises.bootstrap();
		Domains.bootstrap();
		CurveFunctions.bootstrap();
		FTFFeatures.bootstrap();
		FTFHeightProviderTypes.bootstrap();
		FTFFloatProviderTypes.bootstrap();
		BiomeModifiers.bootstrap();
		FTFSurfaceRules.bootstrap();
		StructureRules.bootstrap();

		RegistryUtil.createDataRegistry(FTFRegistries.NOISE, Noise.DIRECT_CODEC, false);
		RegistryUtil.createDataRegistry(FTFRegistries.PRESET, Preset.DIRECT_CODEC, false);
		RegistryUtil.createDataRegistry(FTFRegistries.STRUCTURE_RULE, StructureRule.DIRECT_CODEC, false);
	}

	public static ResourceLocation location(String name) {
		if (name.contains(":")) return ResourceLocation.parse(name);
		return ResourceLocation.fromNamespaceAndPath(FTFCommon.MOD_ID, name);
	}
}
