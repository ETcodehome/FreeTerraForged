package raccoonman.reterraforged.registries;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import raccoonman.reterraforged.platform.RegistryUtil;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifier;
import raccoonman.reterraforged.world.worldgen.feature.chance.ChanceModifier;
import raccoonman.reterraforged.world.worldgen.feature.template.decorator.TemplateDecorator;
import raccoonman.reterraforged.world.worldgen.feature.template.placement.TemplatePlacement;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunction;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.structure.rule.StructureRule;

public class RTFBuiltInRegistries {
	public static final Registry<MapCodec<? extends Noise>> NOISE_TYPE = RegistryUtil.createRegistry(RTFRegistries.NOISE_TYPE);
	public static final Registry<MapCodec<? extends Domain>> DOMAIN_TYPE = RegistryUtil.createRegistry(RTFRegistries.DOMAIN_TYPE);
	public static final Registry<MapCodec<? extends CurveFunction>> CURVE_FUNCTION_TYPE = RegistryUtil.createRegistry(RTFRegistries.CURVE_FUNCTION_TYPE);
	public static final Registry<MapCodec<? extends ChanceModifier>> CHANCE_MODIFIER_TYPE = RegistryUtil.createRegistry(RTFRegistries.CHANCE_MODIFIER_TYPE);
	public static final Registry<MapCodec<? extends TemplatePlacement<?>>> TEMPLATE_PLACEMENT_TYPE = RegistryUtil.createRegistry(RTFRegistries.TEMPLATE_PLACEMENT_TYPE);
	public static final Registry<MapCodec<? extends TemplateDecorator<?>>> TEMPLATE_DECORATOR_TYPE = RegistryUtil.createRegistry(RTFRegistries.TEMPLATE_DECORATOR_TYPE);
	public static final Registry<MapCodec<? extends BiomeModifier>> BIOME_MODIFIER_TYPE = RegistryUtil.createRegistry(RTFRegistries.BIOME_MODIFIER_TYPE);
	public static final Registry<MapCodec<? extends StructureRule>> STRUCTURE_RULE_TYPE = RegistryUtil.createRegistry(RTFRegistries.STRUCTURE_RULE_TYPE);

	public static void bootstrap() {
	}
}
