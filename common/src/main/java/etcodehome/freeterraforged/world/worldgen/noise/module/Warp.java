package etcodehome.freeterraforged.world.worldgen.noise.module;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import etcodehome.freeterraforged.world.worldgen.noise.domain.Domain;

record Warp(Noise input, Domain domain) implements Noise {
	public static final MapCodec<Warp> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		HOLDER_HELPER_CODEC.fieldOf("input").forGetter(Warp::input),
		Domain.CODEC.fieldOf("domain").forGetter(Warp::domain)
	).apply(instance, Warp::new));
	
	@Override
	public float compute(float x, float z, int seed) {
		return this.input.compute(this.domain.getX(x, z, seed), this.domain.getZ(x, z, seed), seed);
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
		return visitor.apply(new Warp(this.input.mapAll(visitor), this.domain.mapAll(visitor)));
	}

	@Override
	public MapCodec<Warp> codec() {
		return CODEC;
	}
}
