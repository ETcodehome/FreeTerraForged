package raccoonman.reterraforged.world.worldgen.cell.terrain;

import raccoonman.reterraforged.world.worldgen.biome.BiomeParameter;
import raccoonman.reterraforged.world.worldgen.biome.Weirdness;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.util.Seed;

/**
 * Shared, spatially coherent sources for the terrain-owned climate axes.
 *
 * <p>The two-octave Perlin source is theoretically normalized to {@code [0, 1]}, but almost all
 * samples occupy roughly {@code [0.3, 0.7]}. Mapping its theoretical endpoints directly onto a
 * vanilla parameter range makes the outer bands effectively unreachable. This sampler normalizes
 * that stable central distribution before terrain families map it onto their intended ranges.</p>
 *
 * <p>Normalization amplifies the source gradient by {@code 1 / 0.4}. The source scale compensates
 * by the same factor, so biome-band boundaries retain the preset's requested spatial scale. The
 * terrain pipeline evaluates these sources in horizontally scaled terrain coordinates, hence the
 * inverse terrain-scale adjustment.</p>
 */
public record ClimateParameterSampler(Noise erosionSource, Noise weirdnessSource) {
	static final int EROSION_SEED_OFFSET = 48291;
	static final int WEIRDNESS_SEED_OFFSET = 73519;
	static final float DISTRIBUTION_MIN = 0.3F;
	static final float DISTRIBUTION_MAX = 0.7F;

	public static ClimateParameterSampler make(Seed terrainSeed, int biomeSize, float terrainHorizontalScale) {
		int scale = sourceScale(biomeSize, terrainHorizontalScale);
		return new ClimateParameterSampler(
			normalizedVariation(terrainSeed.offset(EROSION_SEED_OFFSET), scale),
			normalizedVariation(terrainSeed.offset(WEIRDNESS_SEED_OFFSET), scale)
		);
	}

	public Noise erosion(BiomeParameter from, BiomeParameter to) {
		return Noises.map(this.erosionSource, from.min(), to.max());
	}

	public Noise ordinaryWeirdness() {
		return Noises.map(
			this.weirdnessSource,
			Weirdness.MID_SLICE_NORMAL_DESCENDING.min(),
			Weirdness.LOW_SLICE_NORMAL_DESCENDING.max() - 0.01F
		);
	}

	static int sourceScale(int biomeSize, float terrainHorizontalScale) {
		float normalizationWidth = DISTRIBUTION_MAX - DISTRIBUTION_MIN;
		float safeTerrainScale = Math.max(0.01F, terrainHorizontalScale);
		return Math.max(1, Math.round(biomeSize / normalizationWidth / safeTerrainScale));
	}

	private static Noise normalizedVariation(Seed seed, int scale) {
		Noise variation = Noises.perlin(seed.next(), scale, 2);
		variation = Noises.clamp(variation, DISTRIBUTION_MIN, DISTRIBUTION_MAX);
		return Noises.map(variation, 0.0F, 1.0F);
	}
}
