package raccoonman.reterraforged.world.worldgen.biome.modifier;

import java.util.function.Function;

import com.mojang.serialization.Codec;

import raccoonman.reterraforged.registries.RTFBuiltInRegistries;

public interface BiomeModifier {
    public static final Codec<BiomeModifier> CODEC = RTFBuiltInRegistries.BIOME_MODIFIER_TYPE.byNameCodec().dispatch(BiomeModifier::codec, (codec) -> (com.mojang.serialization.MapCodec<? extends BiomeModifier>) codec);
    
	Codec<? extends BiomeModifier> codec();
}
