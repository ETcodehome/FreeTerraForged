package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class PreviewQueryExecutorTest {
	@Test
	void parallelBandsProduceTheExactSequentialRowMajorResultWithIsolatedResolvers() {
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			AtomicInteger factories = new AtomicInteger();
			Object[] actual = PreviewQueryExecutor.resolve(
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
			Object[] actual = PreviewQueryExecutor.resolve(
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
	void cancellationAndWorkerFailurePropagateWithoutPartialSuccess() {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			assertThrows(
				java.util.concurrent.CancellationException.class,
				() -> PreviewQueryExecutor.resolve(
					4, 4, true, 2, () -> (x, z) -> x, () -> true, executor
				)
			);
			assertThrows(
				IllegalStateException.class,
				() -> PreviewQueryExecutor.resolve(
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
}
