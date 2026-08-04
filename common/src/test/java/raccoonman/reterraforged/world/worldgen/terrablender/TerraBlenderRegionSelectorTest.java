package raccoonman.reterraforged.world.worldgen.terrablender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class TerraBlenderRegionSelectorTest {
	@Test
	void oneRegionNeverEvaluatesUniquenessFallback() {
		AtomicBoolean fallbackCalled = new AtomicBoolean();

		assertEquals(0, TerraBlenderRegionSelector.select(0, Double.NaN, () -> {
			fallbackCalled.set(true);
			return 7;
		}));
		assertFalse(fallbackCalled.get());
	}

	@Test
	void multipleRegionsPreserveNoiseAndFallbackSelection() {
		assertEquals(2, TerraBlenderRegionSelector.select(4, 0.5D, () -> 7));
		assertEquals(7, TerraBlenderRegionSelector.select(4, Double.NaN, () -> 7));
	}

	@Test
	void uniquenessIsNeededOnlyWithARealChoice() {
		assertFalse(TerraBlenderRegionSelector.needsUniqueness(0));
		assertFalse(TerraBlenderRegionSelector.needsUniqueness(1));
		assertTrue(TerraBlenderRegionSelector.needsUniqueness(2));
	}
}
