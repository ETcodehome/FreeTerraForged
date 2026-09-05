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

final class PreviewQueryExecutor {
	private PreviewQueryExecutor() {
	}

	static <T> T[] resolve(
		T[] values,
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
		Objects.requireNonNull(values, "values");
		if (values.length != Math.multiplyExact(width, height)) {
			throw new IllegalArgumentException("Preview output length does not match its dimensions");
		}
		Objects.requireNonNull(factory, "factory");
		Objects.requireNonNull(cancelled, "cancelled");
		Objects.requireNonNull(executor, "executor");

		int workers = parallel ? Math.min(parallelism, height) : 1;
		if (workers == 1) {
			resolveRows(values, width, 0, height, factory.create(), cancelled);
			return values;
		}

		ExecutorCompletionService<Integer> completions = new ExecutorCompletionService<>(executor);
		List<WorkerTask<Integer>> tasks = new ArrayList<>(workers);
		try {
			for (int worker = 0; worker < workers; worker++) {
				int startZ = worker * height / workers;
				int endZ = (worker + 1) * height / workers;
				PixelResolver<T> resolver = Objects.requireNonNull(factory.create(), "pixel resolver");
				WorkerTask<Integer> task = new WorkerTask<>(() -> {
					resolveRows(values, width, startZ, endZ, resolver, cancelled);
					return startZ;
				});
				task.attach(completions.submit(task::run));
				tasks.add(task);
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
			cancelAndJoin(tasks);
		}
	}

	private static void cancelAndJoin(List<? extends WorkerTask<?>> tasks) {
		for (WorkerTask<?> task : tasks) {
			task.cancel();
		}
		for (WorkerTask<?> task : tasks) {
			task.awaitTermination();
		}
	}

	private static final class WorkerTask<T> {
		private final java.util.concurrent.Callable<T> work;
		private final java.util.concurrent.CompletableFuture<Void> terminated =
			new java.util.concurrent.CompletableFuture<>();
		private Future<T> future;
		private boolean started;

		private WorkerTask(java.util.concurrent.Callable<T> work) {
			this.work = work;
		}

		private synchronized void attach(Future<T> future) {
			if (this.future != null) {
				throw new IllegalStateException("Preview worker was submitted more than once");
			}
			this.future = future;
		}

		private T run() throws Exception {
			synchronized (this) {
				this.started = true;
			}
			try {
				return this.work.call();
			} finally {
				this.terminated.complete(null);
			}
		}

		private synchronized void cancel() {
			if (this.future == null) {
				throw new IllegalStateException("Preview worker has no submitted future");
			}
			if (this.future.cancel(true) && !this.started) {
				this.terminated.complete(null);
			}
		}

		private void awaitTermination() {
			this.terminated.join();
		}
	}

	private static <T> void resolveRows(
		T[] values,
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
