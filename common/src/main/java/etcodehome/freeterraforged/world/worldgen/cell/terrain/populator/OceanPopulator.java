package etcodehome.freeterraforged.world.worldgen.cell.terrain.populator;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.CellPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.ClimateParameterSampler;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public record OceanPopulator(Terrain terrainType, Noise height, float minHeight, Noise erosion, Noise weirdness) implements CellPopulator {
	public OceanPopulator(Terrain terrainType, Noise height, float minHeight, ClimateParameterSampler climate) {
		this(terrainType, height, minHeight,
			Noises.map(climate.erosionSource(), -1.0F, 1.0F),
			Noises.map(climate.weirdnessSource(), -1.0F, 1.0F));
	}

	@Override
	public void apply(Cell cell, float x, float z) {

		cell.terrain = this.terrainType;
		cell.height = Math.max(this.height.compute(x, z, 0), this.minHeight);

		// deliberately out of range to indicate that these values are not used by ocean biome selection
		cell.erosion = -1.1F;
		cell.weirdness = -1.1F;
	}
}
