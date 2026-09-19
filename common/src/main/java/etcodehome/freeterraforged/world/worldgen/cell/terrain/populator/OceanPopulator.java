package etcodehome.freeterraforged.world.worldgen.cell.terrain.populator;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.CellPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public record OceanPopulator(Terrain terrainType, Noise height, float minHeight) implements CellPopulator {
	public OceanPopulator(Terrain terrainType, Noise height) {
		this(terrainType, height, 0.0F);
	}

	@Override
	public void apply(Cell cell, float x, float z) {
		cell.terrain = this.terrainType;
		cell.height = Math.max(this.height.compute(x, z, 0), this.minHeight);
		cell.erosion = -1.1F;
		cell.weirdness = -1.1F;
	}
}
