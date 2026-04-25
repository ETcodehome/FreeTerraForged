package raccoonman.reterraforged.world.worldgen.noise.module;

import com.mojang.serialization.MapCodec;

record Constant(float value) implements Noise {
	public static final MapCodec<Constant> CODEC = Noises.NOISE_VALUE_CODEC.xmap(Constant::new, Constant::value).fieldOf("value");

	@Override
	public float compute(float x, float z, int seed) {
		return this.value;
	}

	@Override
	public float minValue() {
		return this.value;
	}

	@Override
	public float maxValue() {
		return this.value;
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return visitor.apply(this);
	}

	@Override
	public MapCodec<Constant> codec() {
		return CODEC;
	}
}
