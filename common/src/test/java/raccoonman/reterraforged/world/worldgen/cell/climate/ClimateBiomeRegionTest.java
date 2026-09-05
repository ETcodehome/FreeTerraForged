package raccoonman.reterraforged.world.worldgen.cell.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.MapCodec;
import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;

class ClimateBiomeRegionTest {
	@Test
	void lightweightRegionMatchesFullClimatePath() {
		Levels levels = new Levels(256, 256, 64, 63);
		ClimateModule module = new ClimateModule(
			4158, 1.0F / 225.0F, 80.0F,
			new TestNoise(0.0073F, 0.0041F), new TestNoise(0.0059F, 0.0083F),
			new TestNoise(0.11F, 0.07F), new TestNoise(0.09F, 0.13F),
			new TestNoise(0.05F, 0.03F), new FlatContinent(),
			new ControlPoints(0.0F, 0.074F, 0.1F, 0.25F, 0.327F, 0.448F, 0.502F),
			levels
		);
		Climate climate = new Climate(
			7123, new TestNoise(0.013F, 0.017F), new TestNoise(0.019F, 0.011F),
			80, levels, module
		);

		for (Terrain terrain : new Terrain[] {TerrainType.FLATS, TerrainType.MOUNTAIN_CHAIN}) {
			for (float height : new float[] {levels.water - 0.01F, levels.water + 0.01F}) {
				for (int z = -1536; z <= 1536; z += 37) {
					for (int x = -1536; x <= 1536; x += 41) {
						Cell full = cell(height, terrain);
						Cell region = cell(height, terrain);
						climate.apply(full, x, z, true);
						if (climate.applyInitialRegion(region, x, z) && height > levels.water) {
							climate.applyEdgeRegion(region, x, z);
						}
						assertEquals(full.biomeRegionX, region.biomeRegionX, x + "," + z);
						assertEquals(full.biomeRegionZ, region.biomeRegionZ, x + "," + z);
						assertEquals(full.biomeRegionCenterX, region.biomeRegionCenterX, x + "," + z);
						assertEquals(full.biomeRegionCenterZ, region.biomeRegionCenterZ, x + "," + z);
					}
				}
			}
		}
	}

	private static Cell cell(float height, Terrain terrain) {
		Cell cell = new Cell();
		cell.height = height;
		cell.terrain = terrain;
		return cell;
	}

	private record TestNoise(float xFrequency, float zFrequency) implements Noise {
		@Override
		public float compute(float x, float z, int seed) {
			return 0.5F + 0.5F * (float) Math.sin(x * this.xFrequency + z * this.zFrequency + seed);
		}

		@Override
		public float minValue() {
			return 0.0F;
		}

		@Override
		public float maxValue() {
			return 1.0F;
		}

		@Override
		public Noise mapAll(Visitor visitor) {
			return visitor.apply(this);
		}

		@Override
		public MapCodec<? extends Noise> codec() {
			return null;
		}
	}

	private static final class FlatContinent implements Continent {
		@Override
		public void apply(Cell cell, float x, float z) {
		}

		@Override
		public float getEdgeValue(float x, float z) {
			return 1.0F;
		}

		@Override
		public long getNearestCenter(float x, float z) {
			return 0L;
		}

		@Override
		public Rivermap getRivermap(int x, int z) {
			return null;
		}

		@Override
		public void close() {
		}
	}
}
