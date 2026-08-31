package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/** Deterministic row-band execution with one mutable query object per worker. */
final class PreviewQueryExecutor {
	private PreviewQueryExecutor() {
	}

	static <T> Object[] resolve(
		int width,
		int height,
		boolean parallel,
		int parallelism,
		ResolverFactory<T> factory,
		BooleanSupplier cancelled,
		ExecutorService executor
	) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Preview dimensions must be positive");
		}
		if (parallelism <= 0) {
			throw new IllegalArgumentException("Preview parallelism must be positive");
		}
		Objects.requireNonNull(factory, "factory");
		Objects.requireNonNull(cancelled, "cancelled");
		Objects.requireNonNull(executor, "executor");

		Object[] values = new Object[Math.multiplyExact(width, height)];
		int workers = parallel ? Math.min(parallelism, height) : 1;
		if (workers == 1) {
			resolveRows(values, width, 0, height, factory.create(), cancelled);
			return values;
		}

		ExecutorCompletionService<Integer> completions = new ExecutorCompletionService<>(executor);
		List<Future<Integer>> futures = new ArrayList<>(workers);
		try {
			for (int worker = 0; worker < workers; worker++) {
				int startZ = worker * height / workers;
				int endZ = (worker + 1) * height / workers;
				PixelResolver<T> resolver = Objects.requireNonNull(factory.create(), "pixel resolver");
				futures.add(completions.submit(() -> {
					resolveRows(values, width, startZ, endZ, resolver, cancelled);
					return startZ;
				}));
			}
			for (int completed = 0; completed < workers; completed++) {
				checkCancellation(cancelled);
				completions.take().get();
			}
			return values;
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			CancellationException cancellation = new CancellationException("Preview resolution interrupted");
			cancellation.initCause(failure);
			throw cancellation;
		} catch (ExecutionException failure) {
			throw propagate(failure.getCause());
		} finally {
			if (futures.stream().anyMatch(future -> !future.isDone())) {
				futures.forEach(future -> future.cancel(true));
			}
		}
	}

	private static <T> void resolveRows(
		Object[] values,
		int width,
		int startZ,
		int endZ,
		PixelResolver<T> resolver,
		BooleanSupplier cancelled
	) {
		for (int z = startZ; z < endZ; z++) {
			checkCancellation(cancelled);
			int rowOffset = z * width;
			for (int x = 0; x < width; x++) {
				values[rowOffset + x] = Objects.requireNonNull(
					resolver.resolve(x, z), "resolved preview value"
				);
			}
		}
	}

	private static void checkCancellation(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Preview request superseded");
		}
	}

	private static RuntimeException propagate(Throwable failure) {
		if (failure instanceof RuntimeException runtime) {
			return runtime;
		}
		if (failure instanceof Error error) {
			throw error;
		}
		return new IllegalStateException("Preview worker failed", failure);
	}

	@FunctionalInterface
	interface ResolverFactory<T> {
		PixelResolver<T> create();
	}

	@FunctionalInterface
	interface PixelResolver<T> {
		T resolve(int x, int z);
	}
}
