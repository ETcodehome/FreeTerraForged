package etcodehome.freeterraforged.world.worldgen.noise.function;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.platform.RegistryUtil;
import etcodehome.freeterraforged.registries.FTFBuiltInRegistries;

public class CurveFunctions {

	public static void bootstrap() {
		register("interpolation", Interpolation.CODEC);
		register("scurve", SCurveFunction.CODEC);
	}

	public static CurveFunction scurve(float lower, float upper) {
		return new SCurveFunction(lower, upper);
	}
	
	private static void register(String name, MapCodec<? extends CurveFunction> value) {
		RegistryUtil.register(FTFBuiltInRegistries.CURVE_FUNCTION_TYPE, name, value);
	}
}
