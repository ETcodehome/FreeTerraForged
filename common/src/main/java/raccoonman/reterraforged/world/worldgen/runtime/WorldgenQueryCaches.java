package raccoonman.reterraforged.world.worldgen.runtime;

import raccoonman.reterraforged.world.worldgen.biome.ClimatePointCache;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection;

public final class WorldgenQueryCaches {
	private final ClimatePointCache climatePoints = new ClimatePointCache();
	private final UndergroundBiomeSurfaceProtection.Cache surfaceProtection =
		new UndergroundBiomeSurfaceProtection.Cache();
	private final BiomeCellCache<WorldgenPlan> biomeCells = new BiomeCellCache<>();

	public ClimatePointCache climatePoints() {
		return this.climatePoints;
	}

	public UndergroundBiomeSurfaceProtection.Cache surfaceProtection() {
		return this.surfaceProtection;
	}

	public BiomeCellCache<WorldgenPlan> biomeCells() {
		return this.biomeCells;
	}

	public void clearBiomeSelection() {
		this.biomeCells.clear();
	}

	public void clear() {
		this.climatePoints.clear();
		this.surfaceProtection.clear();
		this.biomeCells.clear();
	}
}
