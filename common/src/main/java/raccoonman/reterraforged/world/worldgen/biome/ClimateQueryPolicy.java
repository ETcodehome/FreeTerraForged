package raccoonman.reterraforged.world.worldgen.biome;

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
