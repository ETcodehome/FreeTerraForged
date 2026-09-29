package etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.data.worldgen.preset.settings.FilterSettings;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Size;

class ErosionOutputTest {
	@Test
	void erosionProducesTheSameCellValues() {
		Size size = Size.make(64, 0);
		Cell[] cells = new Cell[size.arraySize()];
		for (int z = 0; z < size.total(); z++) {
			for (int x = 0; x < size.total(); x++) {
				Cell cell = new Cell();
				cell.terrain = TerrainType.FLATS;
				cell.height = 0.3F + x * 0.002F + z * 0.001F + ((x * 31 + z * 17) & 7) * 0.006F;
				cell.terrainRegionEdge = ((x + z) & 15) / 16.0F;
				cell.erosionMask = (x + z * 3) % 23 == 0;
				cells[size.indexOf(x, z)] = cell;
			}
		}
		Filterable map = new Filterable() {
			@Override public int getBlockX() { return -128; }
			@Override public int getBlockZ() { return 96; }
			@Override public Size getBlockSize() { return size; }
			@Override public Cell[] getBacking() { return cells; }
			@Override public Cell getCellRaw(int x, int z) { return cells[size.indexOf(x, z)]; }
		};
		FilterSettings.Erosion settings = new FilterSettings.Erosion(135, 12, 0.7F, 0.7F, 0.5F, 0.5F);
		new Erosion(81623, size.total(), settings, Modifier.range(0.1F, 0.9F)).apply(map, 0, 0, settings.dropletsPerChunk);
		long hash = 0xcbf29ce484222325L;
		for (Cell cell : cells) {
			hash = mix(hash, Float.floatToRawIntBits(cell.height));
			hash = mix(hash, Float.floatToRawIntBits(cell.sediment));
			hash = mix(hash, Float.floatToRawIntBits(cell.heightErosion));
		}
		assertEquals(4228436394326674008L, hash);
	}

	private static long mix(long hash, int value) {
		return (hash ^ value) * 0x100000001b3L;
	}
}
