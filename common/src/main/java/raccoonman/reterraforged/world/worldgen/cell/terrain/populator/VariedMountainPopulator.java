package raccoonman.reterraforged.world.worldgen.cell.terrain.populator;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public class VariedMountainPopulator implements CellPopulator, WeightedPopulator {
	private final TerrainPopulator[] variants;
	private final TerrainPopulator edgeReference;
	private final float weight;

	public VariedMountainPopulator(TerrainPopulator[] variants, float weight) {
		this.variants = variants;
		this.edgeReference = null;
		this.weight = weight;
	}

	/**
	 * For populators not already wrapped by {@code RegionLerper} (e.g. mountain chains, which sit
	 * outside the terrain-region mosaic and are placed by their own silhouette mask instead), the
	 * hash-selected variant is blended toward {@code edgeReference} as {@code cell.terrainRegionEdge}
	 * approaches a region boundary, the same technique {@code RegionLerper} uses.
	 */
	public VariedMountainPopulator(TerrainPopulator[] variants, TerrainPopulator edgeReference, float weight) {
		this.variants = variants;
		this.edgeReference = edgeReference;
		this.weight = weight;
	}

	@Override
	public void apply(Cell cell, float x, float z) {
		int index = cellHash(cell.terrainRegionId, this.variants.length);
		TerrainPopulator selected = this.variants[index];
		if (this.edgeReference == null) {
			selected.apply(cell, x, z);
			return;
		}
		float alpha = cell.terrainRegionEdge;
		if (alpha >= 1.0F) {
			selected.apply(cell, x, z);
			return;
		}
		if (alpha <= 0.0F) {
			this.edgeReference.apply(cell, x, z);
			return;
		}
		this.edgeReference.apply(cell, x, z);
		float borderHeight = cell.height;
		float borderErosion = cell.erosion;
		float borderWeirdness = cell.weirdness;

		selected.apply(cell, x, z);
		cell.height = NoiseUtil.lerp(borderHeight, cell.height, alpha);
		cell.erosion = NoiseUtil.lerp(borderErosion, cell.erosion, alpha);
		cell.weirdness = NoiseUtil.lerp(borderWeirdness, cell.weirdness, alpha);
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
