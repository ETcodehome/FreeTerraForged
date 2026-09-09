package etcodehome.freeterraforged.registries;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import etcodehome.freeterraforged.world.worldgen.noise.domain.Domain;
import etcodehome.freeterraforged.world.worldgen.noise.function.CurveFunction;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.feature.chance.ChanceModifier;
import etcodehome.freeterraforged.world.worldgen.feature.template.decorator.TemplateDecorator;
import etcodehome.freeterraforged.world.worldgen.feature.template.placement.TemplatePlacement;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.structure.rule.StructureRule;

public class FTFRegistries {
	public static final ResourceKey<Registry<MapCodec<? extends Noise>>> NOISE_TYPE = createKey("worldgen/noise_type");
	public static final ResourceKey<Registry<MapCodec<? extends Domain>>> DOMAIN_TYPE = createKey("worldgen/domain_type");
	public static final ResourceKey<Registry<MapCodec<? extends CurveFunction>>> CURVE_FUNCTION_TYPE = createKey("worldgen/curve_function_type");
	public static final ResourceKey<Registry<MapCodec<? extends ChanceModifier>>> CHANCE_MODIFIER_TYPE = createKey("worldgen/chance_modifier_type");
	public static final ResourceKey<Registry<MapCodec<? extends TemplatePlacement<?>>>> TEMPLATE_PLACEMENT_TYPE = createKey("worldgen/template_placement_type");
	public static final ResourceKey<Registry<MapCodec<? extends TemplateDecorator<?>>>> TEMPLATE_DECORATOR_TYPE = createKey("worldgen/template_decorator_type");
	public static final ResourceKey<Registry<MapCodec<? extends BiomeModifier>>> BIOME_MODIFIER_TYPE = createKey("biome_modifier_serializers");
	public static final ResourceKey<Registry<MapCodec<? extends StructureRule>>> STRUCTURE_RULE_TYPE = createKey("worldgen/structure_rule_type");

	public static final ResourceKey<Registry<Noise>> NOISE = createKey("worldgen/noise");
	public static final ResourceKey<Registry<BiomeModifier>> BIOME_MODIFIER = createKey("neoforge:biome_modifier");
	public static final ResourceKey<Registry<StructureRule>> STRUCTURE_RULE = createKey("worldgen/structure_rule");

	@Deprecated
	public static final ResourceKey<Registry<Preset>> PRESET = createKey("worldgen/preset");
	
	public static <T> ResourceKey<T> createKey(ResourceKey<? extends Registry<T>> registryKey, String valueKey) {
		return ResourceKey.create(registryKey, FTFCommon.location(valueKey));
	}

	private static <T> ResourceKey<Registry<T>> createKey(String key) {
		return ResourceKey.createRegistryKey(FTFCommon.location(key));
	}
}
