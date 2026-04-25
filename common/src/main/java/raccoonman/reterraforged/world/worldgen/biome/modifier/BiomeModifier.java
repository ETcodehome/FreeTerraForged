package raccoonman.reterraforged.world.worldgen.biome.modifier;

import java.util.function.Function;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import raccoonman.reterraforged.registries.RTFBuiltInRegistries;
import raccoonman.reterraforged.world.worldgen.structure.rule.StructureRule;

public interface BiomeModifier {
    public static final Codec<BiomeModifier> DIRECT_CODEC = RTFBuiltInRegistries.BIOME_MODIFIER_TYPE.byNameCodec().dispatch(BiomeModifier::codec, Function.identity());

	MapCodec<? extends BiomeModifier> codec();
}
