package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class PreviewQueryExecutorTest {
	@Test
	void parallelBandsProduceTheExactSequentialRowMajorResultWithIsolatedResolvers() {
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			AtomicInteger factories = new AtomicInteger();
			Integer[] actual = PreviewQueryExecutor.resolve(
				new Integer[77],
				7, 11, true, 4,
				() -> {
					factories.incrementAndGet();
					return (x, z) -> z * 7 + x;
				},
				() -> false,
				executor
			);
			Integer[] expected = new Integer[77];
			for (int index = 0; index < expected.length; index++) {
				expected[index] = index;
			}
			assertArrayEquals(expected, actual);
			assertEquals(4, factories.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void serialFallbackConstructsAndUsesOnlyOneResolver() {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			AtomicInteger factories = new AtomicInteger();
			String[] actual = PreviewQueryExecutor.resolve(
				new String[6],
				3, 2, false, 8,
				() -> {
					factories.incrementAndGet();
					return (x, z) -> x + ":" + z;
				},
				() -> false,
				executor
			);
			assertArrayEquals(new Object[]{"0:0", "1:0", "2:0", "0:1", "1:1", "2:1"}, actual);
			assertEquals(1, factories.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void coordinatorCanShareTheComputePoolWithNMinusOneBands() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			Integer[] actual = CompletableFuture.supplyAsync(() -> PreviewQueryExecutor.resolve(
				new Integer[48], 8, 6, true, 3,
				() -> (x, z) -> z * 8 + x,
				() -> false,
				executor
			), executor).get(5, TimeUnit.SECONDS);
			Integer[] expected = new Integer[48];
			for (int index = 0; index < expected.length; index++) {
				expected[index] = index;
			}
			assertArrayEquals(expected, actual);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void cancellationAndWorkerFailurePropagateWithoutPartialSuccess() {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			assertThrows(
				java.util.concurrent.CancellationException.class,
				() -> PreviewQueryExecutor.resolve(
					new Integer[16],
					4, 4, true, 2, () -> (x, z) -> x, () -> true, executor
				)
			);
			assertThrows(
				IllegalStateException.class,
				() -> PreviewQueryExecutor.resolve(
					new Integer[16],
					4, 4, true, 2,
					() -> (x, z) -> {
						throw new IllegalStateException("synthetic worker failure");
					},
					() -> false, executor
				)
			);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void workerFailureJoinsCancelledSiblingsBeforeRequestOwnershipReturns() throws Exception {
		ExecutorService workers = Executors.newFixedThreadPool(2);
		ExecutorService coordinator = Executors.newSingleThreadExecutor();
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch siblingInterrupted = new CountDownLatch(1);
		CountDownLatch releaseSibling = new CountDownLatch(1);
		AtomicInteger resolverIds = new AtomicInteger();
		try {
			CompletableFuture<Integer[]> request = CompletableFuture.supplyAsync(() ->
				PreviewQueryExecutor.resolve(
					new Integer[2], 1, 2, true, 2,
					() -> {
						int id = resolverIds.getAndIncrement();
						return (x, z) -> {
							entered.countDown();
							awaitUninterruptibly(entered);
							if (id == 0) {
								throw new IllegalStateException("synthetic owner-bound worker failure");
							}
							while (true) {
								try {
									if (releaseSibling.await(5, TimeUnit.SECONDS)) {
										return z;
									}
									throw new AssertionError("Sibling release was not signalled");
								} catch (InterruptedException cancelled) {
									siblingInterrupted.countDown();
								}
							}
						};
					},
					() -> false, workers
				), coordinator
			);

			assertTrue(siblingInterrupted.await(5, TimeUnit.SECONDS));
			assertFalse(request.isDone());
			releaseSibling.countDown();
			CompletionException failure = assertThrows(CompletionException.class, request::join);
			assertTrue(failure.getCause() instanceof IllegalStateException);
		} finally {
			releaseSibling.countDown();
			workers.shutdownNow();
			coordinator.shutdownNow();
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try {
				if (!latch.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("Timed out coordinating preview workers");
				}
				break;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
