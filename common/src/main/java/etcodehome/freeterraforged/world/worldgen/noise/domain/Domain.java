package etcodehome.freeterraforged.world.worldgen.noise.domain;

import java.util.function.Function;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import etcodehome.freeterraforged.registries.FTFBuiltInRegistries;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public interface Domain {
    public static final Codec<Domain> CODEC = FTFBuiltInRegistries.DOMAIN_TYPE.byNameCodec().dispatch(Domain::codec, Function.identity());
	
    float getOffsetX(float x, float z, int seed);
    
    float getOffsetZ(float x, float z, int seed);
    
    Domain mapAll(Noise.Visitor visitor);
    
    MapCodec<? extends Domain> codec();

    default float getX(float x, float z, int seed) {
        return x + this.getOffsetX(x, z, seed);
    }
    
    default float getZ(float x, float z, int seed) {
        return z + this.getOffsetZ(x, z, seed);
    }
}
