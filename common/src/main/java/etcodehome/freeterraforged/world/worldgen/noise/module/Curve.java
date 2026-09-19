package etcodehome.freeterraforged.world.worldgen.noise.module;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import etcodehome.freeterraforged.world.worldgen.noise.function.CurveFunction;

record Curve(Noise input, CurveFunction curveFunction) implements Noise {
	public static final MapCodec<Curve> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		HOLDER_HELPER_CODEC.fieldOf("input").forGetter(Curve::input),
		CurveFunction.CODEC.fieldOf("curve_function").forGetter(Curve::curveFunction)
	).apply(instance, Curve::new));
	
	@Override
	public float compute(float x, float z, int seed) {
		return this.curveFunction.apply(this.input.compute(x, z, seed));
	}

	@Override
	public float minValue() {
		return this.input.minValue();
	}

	@Override
	public float maxValue() {
		return this.input.maxValue();
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return visitor.apply(new Curve(this.input.mapAll(visitor), this.curveFunction));
	}

	@Override
	public MapCodec<Curve> codec() {
		return CODEC;
	}
}
