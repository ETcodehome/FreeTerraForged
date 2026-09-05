package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class CellIntervalSelectorTest {
	private static final List<CellIntervalSelector.Choice<String>> CHOICES = List.of(
		new CellIntervalSelector.Choice<>(id("base"), 0.7D, "base"),
		new CellIntervalSelector.Choice<>(id("first"), 0.2D, "first"),
		new CellIntervalSelector.Choice<>(id("second"), 0.1D, "second")
	);

	@Test
	void choiceOrderDoesNotChangeTheNormalizedSelection() {
		CellIntervalSelector<String> forward = new CellIntervalSelector<>(17L, CHOICES);
		CellIntervalSelector<String> reverse = new CellIntervalSelector<>(17L, CHOICES.reversed());
		for (int cell = 0; cell < 10_000; cell++) {
			assertEquals(forward.select(cell, -cell), reverse.select(cell, -cell));
		}
	}

	@Test
	void selectedRangeContainsTheReusableSample() {
		CellIntervalSelector<String> selector = new CellIntervalSelector<>(31L, CHOICES);
		for (int cell = 0; cell < 10_000; cell++) {
			CellIntervalSelector.Selection<String> selected = selector.select(cell / 137, cell % 137);
			assertTrue(selected.sample() >= selected.minInclusive());
			assertTrue(selected.sample() <= selected.maxInclusive());
			assertEquals(selected, selector.select(selected.sample()));
		}
	}

	@Test
	void parallelReadsMatchSerialReadsAndTrackWeights() {
		CellIntervalSelector<String> selector = new CellIntervalSelector<>(47L, CHOICES);
		String[] serial = IntStream.range(0, 50_000)
			.mapToObj(cell -> selector.select(cell / 223, cell % 223).value())
			.toArray(String[]::new);
		String[] parallel = IntStream.range(0, 50_000).parallel()
			.mapToObj(cell -> selector.select(cell / 223, cell % 223).value())
			.toArray(String[]::new);
		assertArrayEquals(serial, parallel);
		long first = java.util.Arrays.stream(serial).filter("first"::equals).count();
		long second = java.util.Arrays.stream(serial).filter("second"::equals).count();
		assertTrue(first > 9_000 && first < 11_000);
		assertTrue(second > 4_000 && second < 6_000);
	}

	@Test
	void rejectsDuplicateIdentitiesAndInvalidSamples() {
		assertThrows(IllegalArgumentException.class, () -> new CellIntervalSelector<>(1L, List.of(
			new CellIntervalSelector.Choice<>(id("same"), 1.0D, "first"),
			new CellIntervalSelector.Choice<>(id("same"), 1.0D, "second")
		)));
		CellIntervalSelector<String> selector = new CellIntervalSelector<>(1L, CHOICES);
		assertThrows(IllegalArgumentException.class, () -> selector.select(1.0D));
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}
}
