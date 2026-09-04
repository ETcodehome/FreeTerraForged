package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class CellRendezvousTest {
	private static final List<CellRendezvous.Choice<String>> CHOICES = List.of(
		new CellRendezvous.Choice<>(id("base"), 0.7D, "base"),
		new CellRendezvous.Choice<>(id("first"), 0.2D, "first"),
		new CellRendezvous.Choice<>(id("second"), 0.1D, "second")
	);

	@Test
	void semanticChoiceOrderDoesNotChangeResults() {
		CellRendezvous.Selector<String> selector = new CellRendezvous.Selector<>(17L, CHOICES);
		List<CellRendezvous.Choice<String>> reversed = CHOICES.reversed();
		for (int cell = 0; cell < 10_000; cell++) {
			assertEquals(
				CellRendezvous.select(17L, cell, -cell, CHOICES),
				CellRendezvous.select(17L, cell, -cell, reversed)
			);
			assertEquals(
				CellRendezvous.select(17L, cell, -cell, CHOICES),
				selector.select(cell, -cell)
			);
		}
	}

	@Test
	void parallelReadsMatchSerialReads() {
		String[] serial = IntStream.range(0, 20_000)
			.mapToObj(cell -> CellRendezvous.select(31L, cell / 137, cell % 137, CHOICES))
			.toArray(String[]::new);
		String[] parallel = IntStream.range(0, 20_000).parallel()
			.mapToObj(cell -> CellRendezvous.select(31L, cell / 137, cell % 137, CHOICES))
			.toArray(String[]::new);
		assertArrayEquals(serial, parallel);
	}

	@Test
	void observedCoverageTracksDeclaredWeights() {
		long first = IntStream.range(0, 50_000)
			.mapToObj(cell -> CellRendezvous.select(47L, cell / 223, cell % 223, CHOICES))
			.filter("first"::equals)
			.count();
		long second = IntStream.range(0, 50_000)
			.mapToObj(cell -> CellRendezvous.select(47L, cell / 223, cell % 223, CHOICES))
			.filter("second"::equals)
			.count();
		assertTrue(first > 9_000 && first < 11_000);
		assertTrue(second > 4_000 && second < 6_000);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}
}
