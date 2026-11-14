package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record SurfaceSettings(Scree scree) {
	public static final Codec<SurfaceSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Scree.CODEC.fieldOf("erosion").forGetter(SurfaceSettings::scree)
	).apply(instance, SurfaceSettings::new));

	public SurfaceSettings copy() {
		return new SurfaceSettings(this.scree.copy());
	}

	public static class Scree {
		public static final Codec<Scree> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.INT.fieldOf("rockVariance").forGetter((o) -> o.rockVariance),
				Codec.INT.fieldOf("rockMin").forGetter((o) -> o.rockMin),
				Codec.INT.fieldOf("dirtVariance").forGetter((o) -> o.dirtVariance),
				Codec.INT.fieldOf("dirtMin").forGetter((o) -> o.dirtMin),
				Codec.FLOAT.fieldOf("rockSteepness").forGetter((o) -> o.rockSteepness),
				Codec.FLOAT.fieldOf("dirtSteepness").forGetter((o) -> o.dirtSteepness),
				Codec.FLOAT.fieldOf("screeSteepness").forGetter((o) -> o.screeSteepness)
		).apply(instance, Scree::new));

		public int rockVariance;
		public int rockMin;
		public int dirtVariance;
		public int dirtMin;
		public float rockSteepness;
		public float dirtSteepness;
		public float screeSteepness;

		public Scree(int rockVariance, int rockMin, int dirtVariance, int dirtMin, float rockSteepness, float dirtSteepness, float screeSteepness) {
			this.rockVariance = rockVariance;
			this.rockMin = rockMin;
			this.dirtVariance = dirtVariance;
			this.dirtMin = dirtMin;
			this.rockSteepness = rockSteepness;
			this.dirtSteepness = dirtSteepness;
			this.screeSteepness = screeSteepness;
		}

		public Scree copy() {
			return new Scree(this.rockVariance, this.rockMin, this.dirtVariance, this.dirtMin, this.rockSteepness, this.dirtSteepness, this.screeSteepness);
		}
	}

	public static SurfaceSettings makeDefault(){
		return new SurfaceSettings(
				new Scree(30,
						400,
						40,
						95,
						0.8F,
						0.4F,
						0.6F
				)
		);
	}
}