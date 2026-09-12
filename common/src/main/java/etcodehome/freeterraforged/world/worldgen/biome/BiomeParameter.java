package etcodehome.freeterraforged.world.worldgen.biome;

import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public interface BiomeParameter {
	float min();
	
	float max();
	
	default float mid() {
		return (this.min() + this.max()) / 2.0F;
	}
	
	default float lerp(float alpha) {
		return this.min() + alpha * (this.max() - this.min());
	}

	default Noise source() {
		return Noises.constant(this.mid());
	}
}
