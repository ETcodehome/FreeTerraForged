package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WorldgenExecutionTest {
	@Test
	void defaultsEveryFacetToSerialAndRequiresEveryParallelParticipant() {
		WorldgenExecution execution = WorldgenExecution.serial()
			.withQueryMode(WorldgenFacet.PROVIDER_SELECTION, WorldgenQueryMode.ISOLATED_PARALLEL_READ)
			.withQueryMode(WorldgenFacet.SELECTION_DECORATION, WorldgenQueryMode.ISOLATED_PARALLEL_READ);

		assertTrue(execution.supportsIsolatedParallelRead(EnumSet.of(
			WorldgenFacet.PROVIDER_SELECTION, WorldgenFacet.SELECTION_DECORATION
		)));
		assertFalse(execution.supportsIsolatedParallelRead(EnumSet.of(
			WorldgenFacet.PROVIDER_SELECTION, WorldgenFacet.SPATIAL_OWNERSHIP
		)));
		assertFalse(execution.supportsIsolatedParallelRead(EnumSet.noneOf(WorldgenFacet.class)));
		assertEquals(WorldgenQueryMode.OWNER_SERIAL, WorldgenExecution.serial().queryMode(
			WorldgenFacet.PROVIDER_SELECTION
		));
	}

	@Test
	void isImmutableAndRejectsIncompleteCapabilityMaps() {
		WorldgenExecution execution = WorldgenExecution.serial();
		assertThrows(
			UnsupportedOperationException.class,
			() -> execution.queryModes().put(WorldgenFacet.PROVIDER_SELECTION, WorldgenQueryMode.ISOLATED_PARALLEL_READ)
		);

		Map<WorldgenFacet, WorldgenQueryMode> incomplete = new EnumMap<>(WorldgenFacet.class);
		incomplete.put(WorldgenFacet.PROVIDER_SELECTION, WorldgenQueryMode.OWNER_SERIAL);
		assertThrows(IllegalArgumentException.class, () -> new WorldgenExecution(incomplete));
	}
}
