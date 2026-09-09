package etcodehome.freeterraforged.world.worldgen.densityfunction;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.platform.RegistryUtil;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.DensityFunction;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public class FTFDensityFunctions {

	public static void bootstrap() {
		register("noise", NoiseFunction.Marker.CODEC);
		register("cell", CellSampler.Marker.CODEC);
		register("clamp_to_nearest_unit", ClampToNearestUnit.CODEC);
		register("linear_spline", LinearSplineFunction.CODEC);
	}
	
	public static NoiseFunction.Marker noise(Holder<Noise> noise) {
		return new NoiseFunction.Marker(noise);
	}
	
	public static CellSampler.Marker cell(CellSampler.Field field) {
		return new CellSampler.Marker(field);
	}
	
	public static ClampToNearestUnit clampToNearestUnit(DensityFunction function, int resolution) {
		return new ClampToNearestUnit(function, resolution);
	}
	
	private static void register(String name, MapCodec<? extends DensityFunction> type) {
		RegistryUtil.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, name, type);
	}
}
