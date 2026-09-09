package raccoonman.reterraforged.world.worldgen.feature.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkLocalFeaturePlacementTest {
	@Test
	void wrapsBothSidesIntoOneChunkWithoutEdgeClamping() {
		assertEquals(14, ChunkLocalFeaturePlacement.wrap(-2, 0));
		assertEquals(15, ChunkLocalFeaturePlacement.wrap(-1, 0));
		assertEquals(0, ChunkLocalFeaturePlacement.wrap(0, 0));
		assertEquals(15, ChunkLocalFeaturePlacement.wrap(15, 0));
		assertEquals(0, ChunkLocalFeaturePlacement.wrap(16, 0));
		assertEquals(1, ChunkLocalFeaturePlacement.wrap(17, 0));
	}

	@Test
	void wrapsNegativeChunkCoordinatesRelativeToTheirMinimum() {
		assertEquals(-386, ChunkLocalFeaturePlacement.wrap(-386, -400));
		assertEquals(-385, ChunkLocalFeaturePlacement.wrap(-401, -400));
		assertEquals(-400, ChunkLocalFeaturePlacement.wrap(-384, -400));
	}
}
