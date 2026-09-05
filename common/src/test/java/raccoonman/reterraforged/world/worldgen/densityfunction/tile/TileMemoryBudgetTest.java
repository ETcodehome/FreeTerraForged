package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;

class TileMemoryBudgetTest {
	@Test
	void defaultServerTilesUseCellDerivedCacheAndInFlightBounds() {
		int cells = Size.blocks(3, 2).arraySize();

		assertEquals(28, TileCache.cacheCapacity(cells));
		assertEquals(7, TileGenerator.maxInFlightTiles(cells));
		assertEquals(14, TileGenerator.maxPendingTiles(cells));
	}

	@Test
	void previewSizedTilesCannotQueueByProcessorCount() {
		int cells = Size.blocks(4, 0).arraySize();

		assertEquals(16, TileCache.cacheCapacity(cells));
		assertEquals(4, TileGenerator.maxInFlightTiles(cells));
		assertEquals(8, TileGenerator.maxPendingTiles(cells));
	}

	@Test
	void oversizedTilesStillPermitProgressWithMinimumRetention() {
		int cells = 1 << 22;

		assertEquals(4, TileCache.cacheCapacity(cells));
		assertEquals(1, TileGenerator.maxInFlightTiles(cells));
		assertEquals(2, TileGenerator.maxPendingTiles(cells));
	}
}
