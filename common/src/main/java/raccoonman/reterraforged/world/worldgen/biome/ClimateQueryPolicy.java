package raccoonman.reterraforged.world.worldgen.biome;

/**
 * Immutable sampler behavior selected by the owning worldgen plan.
 *
 * <p>Surface preview resolves a final surface biome and owns exact request-local quart caching, so
 * it neither applies underground banding to climate points nor populates the sampler's generation
 * cache. Worldgen retains both behaviors.</p>
 */
public enum ClimateQueryPolicy {
	PASSTHROUGH(false, false),
	WORLDGEN(true, true),
	SURFACE_PREVIEW(false, false);

	private final boolean undergroundBanding;
	private final boolean climatePointCache;

	ClimateQueryPolicy(boolean undergroundBanding, boolean climatePointCache) {
		this.undergroundBanding = undergroundBanding;
		this.climatePointCache = climatePointCache;
	}

	public boolean appliesUndergroundBanding() {
		return this.undergroundBanding;
	}

	public boolean cachesClimatePoints() {
		return this.climatePointCache;
	}
}
