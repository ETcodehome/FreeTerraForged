package etcodehome.freeterraforged.world.worldgen.search;

import etcodehome.freeterraforged.world.worldgen.runtime.BiomePlacementPlan;

/** Conservative quart-aligned sampling derived from the active preset and requested placement roles. */
public record BiomeSearchResolution(int horizontalStep, int verticalStep) {
	private static final int QUART = 4;
	// Surface-only searches can safely trade local lattice coverage for lower time-to-result at large preset scales.
	private static final int MAX_SURFACE_HORIZONTAL_STEP = 192;
	// Terrain, underground, vertical, and mixed/unknown domains have not established the same safe tradeoff.
	private static final int MAX_CONSERVATIVE_STEP = 64;

	public BiomeSearchResolution {
		if (horizontalStep < QUART || horizontalStep % QUART != 0
			|| verticalStep < QUART || verticalStep % QUART != 0) {
			throw new IllegalArgumentException("Biome search steps must be positive quart-aligned values");
		}
	}

	public static BiomeSearchResolution derive(
		BiomePlacementPlan.SearchScales scales,
		BiomePlacementPlan.TargetRoles roles
	) {
		int horizontal = Integer.MAX_VALUE;
		if (roles.searchesSurface()) {
			horizontal = Math.min(
				horizontal,
				roles.thinSurface()
					? QUART
					: fromScale(scales.surfaceBiomeSize(), MAX_SURFACE_HORIZONTAL_STEP)
			);
		}
		if (roles.searchesUnderground()) {
			horizontal = Math.min(horizontal, fromScale(scales.undergroundBiomeSize()));
		}
		if (horizontal == Integer.MAX_VALUE) {
			horizontal = QUART;
		}
		int vertical = roles.searchesUnderground()
			? fromScale(scales.undergroundBiomeVerticalSize())
			: QUART;
		return new BiomeSearchResolution(horizontal, vertical);
	}

	static int fromScale(int scale) {
		return fromScale(scale, MAX_CONSERVATIVE_STEP);
	}

	private static int fromScale(int scale, int maximum) {
		if (scale <= 0) {
			return QUART;
		}
		int proposed = Math.clamp(scale / 8, QUART, maximum);
		return Math.max(QUART, proposed - proposed % QUART);
	}
}
