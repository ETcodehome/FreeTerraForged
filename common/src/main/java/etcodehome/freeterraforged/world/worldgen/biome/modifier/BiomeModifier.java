package etcodehome.freeterraforged.world.worldgen.biome.modifier;

import java.util.function.Function;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.registries.FTFBuiltInRegistries;

public interface BiomeModifier {
    public static final Codec<BiomeModifier> DIRECT_CODEC = FTFBuiltInRegistries.BIOME_MODIFIER_TYPE.byNameCodec().dispatch(BiomeModifier::codec, Function.identity());

	MapCodec<? extends BiomeModifier> codec();
}
