package raccoonman.reterraforged.world.worldgen.noise.function;

import java.util.function.Function;

import com.mojang.serialization.Codec;

import raccoonman.reterraforged.registries.RTFBuiltInRegistries;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;

public interface CurveFunction {
    public static final Codec<CurveFunction> CODEC = RTFBuiltInRegistries.CURVE_FUNCTION_TYPE.byNameCodec().dispatch(CurveFunction::codec, (codec) -> (com.mojang.serialization.MapCodec<? extends CurveFunction>) codec);
	
	float apply(float f);
	
	Codec<? extends CurveFunction> codec();
}
