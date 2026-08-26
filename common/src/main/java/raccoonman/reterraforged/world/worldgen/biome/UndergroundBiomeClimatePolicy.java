package raccoonman.reterraforged.world.worldgen.biome;

import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

/** Applies cave/background ownership to the shared climate input seen by biome providers. */
public final class UndergroundBiomeClimatePolicy {
	private static final long SURFACE_DEPTH = Climate.quantizeCoord(0.0F);

	private UndergroundBiomeClimatePolicy() {
	}

	public static Climate.TargetPoint apply(
		Climate.Sampler sampler,
		Climate.TargetPoint target,
		int quartX,
		int quartY,
		int quartZ
	) {
		if (!((Object) sampler instanceof RTFClimateSampler rtfSampler)) {
			return target;
		}
		Preset preset = rtfSampler.getUndergroundBiomeBandingPreset();
		if (preset == null) {
			return target;
		}
		float surfaceCoverageFactor = UndergroundBiomeSurfaceProtection.coverageFactor(
			sampler,
			target,
			quartX,
			quartY,
			quartZ
		);
		if (UndergroundBiomeBanding.allowsCaveBiome(
			preset,
			rtfSampler.getUndergroundBiomeBandingSeed(),
			target,
			quartX,
			quartY,
			quartZ,
			surfaceCoverageFactor
		)) {
			return target;
		}
		return new Climate.TargetPoint(
			target.temperature(),
			target.humidity(),
			target.continentalness(),
			target.erosion(),
			SURFACE_DEPTH,
			target.weirdness()
		);
	}
}
