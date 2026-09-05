package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

	@Test
	void ownerSerialPolicyIsAnExecutableGateSharedByDerivedPlans() throws Exception {
		Set<WorldgenFacet> queryFacets = EnumSet.of(
			WorldgenFacet.PROVIDER_SELECTION,
			WorldgenFacet.SELECTION_DECORATION,
			WorldgenFacet.SPATIAL_OWNERSHIP
		);
		WorldgenExecution execution = WorldgenExecution.serial()
			.withQueryMode(WorldgenFacet.PROVIDER_SELECTION, WorldgenQueryMode.ISOLATED_PARALLEL_READ)
			.withQueryMode(WorldgenFacet.SELECTION_DECORATION, WorldgenQueryMode.ISOLATED_PARALLEL_READ);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		var workers = Executors.newFixedThreadPool(2);
		try {
			var first = workers.submit(() -> execution.execute(queryFacets, () -> {
				firstEntered.countDown();
				await(releaseFirst);
				return 1;
			}));
			assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
			var second = workers.submit(() -> {
				secondStarted.countDown();
				return execution.execute(queryFacets, () -> {
					secondEntered.countDown();
					return 2;
				});
			});
			assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
			assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
			releaseFirst.countDown();
			assertEquals(1, first.get(5, TimeUnit.SECONDS));
			assertEquals(2, second.get(5, TimeUnit.SECONDS));
		} finally {
			releaseFirst.countDown();
			workers.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("Timed out waiting for serial gate control");
			}
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted waiting for serial gate control", failure);
		}
	}
}
