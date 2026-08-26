package raccoonman.reterraforged.world.worldgen.feature.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class UndergroundFeatureEnclosureTest {

	@Test
	void requiresFourCompleteBlocksBelowAFlatSurface() {
		int maximumY = UndergroundFeatureEnclosure.maximumPlacementY((x, z) -> 64, 0, 0);

		assertEquals(59, maximumY);
	}

	@Test
	void protectsAgainstTheLowestSurfaceOnAnySide() {
		int maximumY = UndergroundFeatureEnclosure.maximumPlacementY(
			(x, z) -> x == UndergroundFeatureEnclosure.BUFFER_BLOCKS && z == 0 ? 40 : 96,
			0,
			0
		);

		assertEquals(35, maximumY);
	}

	@Test
	void doesNotExpandBeyondTheDeclaredHorizontalBuffer() {
		int maximumY = UndergroundFeatureEnclosure.maximumPlacementY(
			(x, z) -> x == UndergroundFeatureEnclosure.BUFFER_BLOCKS + 1 && z == 0 ? 40 : 96,
			0,
			0
		);

		assertEquals(91, maximumY);
	}

	@Test
	void samplesOneFixedNeighborhoodIndependentOfCandidateHeight() {
		AtomicInteger samples = new AtomicInteger();
		UndergroundFeatureEnclosure.maximumPlacementY((x, z) -> {
			samples.incrementAndGet();
			return 64;
		}, 0, 0);

		int diameter = UndergroundFeatureEnclosure.BUFFER_BLOCKS * 2 + 1;
		assertEquals(diameter * diameter, samples.get());
	}
}
