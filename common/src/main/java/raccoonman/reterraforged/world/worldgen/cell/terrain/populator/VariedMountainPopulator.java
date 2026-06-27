package raccoonman.reterraforged.world.worldgen.cell.terrain.populator;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;

public class VariedMountainPopulator implements CellPopulator, WeightedPopulator {
	private final TerrainPopulator[] variants;
	private final Noise selector;
	private final float offsetX;
	private final float offsetZ;
	private final float weight;

	public VariedMountainPopulator(TerrainPopulator[] variants, float weight) {
		this.variants = variants;
		this.selector = null;
		this.offsetX = 0;
		this.offsetZ = 0;
		this.weight = weight;
	}

	public VariedMountainPopulator(TerrainPopulator[] variants, Noise selector, float offsetX, float offsetZ, float weight) {
		this.variants = variants;
		this.selector = selector;
		this.offsetX = offsetX;
		this.offsetZ = offsetZ;
		this.weight = weight;
	}

	@Override
	public void apply(Cell cell, float x, float z) {
		int index;
		if (this.selector != null) {
			float val = Math.max(0.0F, Math.min(1.0F, this.selector.compute(x + this.offsetX, z + this.offsetZ, 0)));
			index = Math.min((int) (val * this.variants.length), this.variants.length - 1);
		} else {
			index = cellHash(cell.terrainRegionId, this.variants.length);
		}
		this.variants[index].apply(cell, x, z);
	}

	@Override
	public float weight() {
		return this.weight;
	}

	private static int cellHash(float regionId, int buckets) {
		int bits = Float.floatToIntBits(regionId);
		bits = ((bits >>> 16) ^ bits) * 0x45d9f3b;
		bits = ((bits >>> 16) ^ bits) * 0x45d9f3b;
		bits = (bits >>> 16) ^ bits;
		return (bits & 0x7fffffff) % buckets;
	}
}
