package etcodehome.freeterraforged.world.worldgen.biome;

import java.util.function.Supplier;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;

public final class UndergroundBiomeClimatePolicy {
	private static final long SURFACE_DEPTH = Climate.quantizeCoord(0.0F);
	private static final ThreadLocal<Boolean> SKIP_BANDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private UndergroundBiomeClimatePolicy() {
	}

	public static boolean isSkippingBanding() {
		return SKIP_BANDING.get();
	}

	public static Climate.TargetPoint sampleWithoutBanding(Supplier<Climate.TargetPoint> supplier) {
		Boolean prev = SKIP_BANDING.get();
		SKIP_BANDING.set(Boolean.TRUE);
		try {
			return supplier.get();
		} finally {
			SKIP_BANDING.set(prev);
		}
	}

	public static Climate.TargetPoint apply(
		Climate.Sampler sampler,
		Climate.TargetPoint target,
		int quartX,
		int quartY,
		int quartZ,
		ClimateQueryPolicy policy,
		Preset preset,
		long seed
	) {
		if (!((Object) sampler instanceof FTFClimateSampler ftfSampler)) {
			return target;
		}
		if (!policy.appliesUndergroundBanding()) {
			return target;
		}
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
			seed,
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
