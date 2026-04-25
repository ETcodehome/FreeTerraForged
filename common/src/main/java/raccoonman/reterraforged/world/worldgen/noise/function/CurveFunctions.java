package raccoonman.reterraforged.world.worldgen.noise.function;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import raccoonman.reterraforged.platform.RegistryUtil;
import raccoonman.reterraforged.registries.RTFBuiltInRegistries;

public class CurveFunctions {

	public static void bootstrap() {
		register("interpolation", Interpolation.CODEC);
		register("scurve", SCurveFunction.CODEC);
	}

	public static CurveFunction scurve(float lower, float upper) {
		return new SCurveFunction(lower, upper);
	}
	
	private static void register(String name, MapCodec<? extends CurveFunction> value) {
		RegistryUtil.register(RTFBuiltInRegistries.CURVE_FUNCTION_TYPE, name, value);
	}
}
