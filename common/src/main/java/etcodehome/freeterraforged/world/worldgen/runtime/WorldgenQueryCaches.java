package etcodehome.freeterraforged.world.worldgen.runtime;

import etcodehome.freeterraforged.world.worldgen.biome.ClimatePointCache;

public final class WorldgenQueryCaches {
	private final ClimatePointCache climatePoints = new ClimatePointCache();
	private final BiomeCellCache<WorldgenPlan> biomeCells = new BiomeCellCache<>();

	public ClimatePointCache climatePoints() {
		return this.climatePoints;
	}

	public BiomeCellCache<WorldgenPlan> biomeCells() {
		return this.biomeCells;
	}

	public void clearBiomeSelection() {
		this.biomeCells.clear();
	}

	public void clear() {
		this.climatePoints.clear();
		this.biomeCells.clear();
	}
}
