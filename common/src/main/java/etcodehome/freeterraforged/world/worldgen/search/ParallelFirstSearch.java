package etcodehome.freeterraforged.world.worldgen.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.cell.filter.RegionFilterGrid;
import net.minecraft.world.level.Level;

/**
 * Searches fixed world-aligned tiles outwards and returns the first accepted match.
 *
 * <p>This engine intentionally does not prove that a result is the nearest one. It completes as soon as any worker
 * finds a match in the current outward tile ring. Only a bounded number of tasks are submitted for one ring, and all
 * work runs on caller-provided executors rather than the server thread.
 */
public final class ParallelFirstSearch {
	public static final int TILE_BLOCKS = RegionFilterGrid.REGION_BLOCKS;
	private static final int MIN_COORDINATE = -Level.MAX_LEVEL_SIZE;
	private static final int MAX_COORDINATE = Level.MAX_LEVEL_SIZE - 1;

	private ParallelFirstSearch() {
	}

	public static <M> CompletableFuture<Result<M>> findFirst(
		int originX,
		int originZ,
		Supplier<TileEvaluator<M>> evaluators,
		Executor executor,
		int parallelism
	) {
		return findFirst(
			originX,
			originZ,
			evaluators,
			executor,
			parallelism,
			LocateSearchSettings.DEFAULT_TIMEOUT_SECONDS
		);
	}

	public static <M> CompletableFuture<Result<M>> findFirst(
		int originX,
		int originZ,
		Supplier<TileEvaluator<M>> evaluators,
		Executor executor,
		int parallelism,
		long timeoutSeconds
	) {
		Objects.requireNonNull(evaluators, "evaluators");
		Objects.requireNonNull(executor, "executor");
		if (parallelism < 1) {
			throw new IllegalArgumentException("parallelism must be positive");
		}
		if (timeoutSeconds < 1L) {
			throw new IllegalArgumentException("timeoutSeconds must be positive");
		}
		Search<M> search = new Search<>(
			originX, originZ, evaluators, executor, parallelism, timeoutSeconds
		);
		search.start();
		return search.future();
	}

	@FunctionalInterface
	public interface TileEvaluator<M> {
		TileResult<M> find(Tile tile, Control control);
	}

	@FunctionalInterface
	public interface PointEvaluator<M> {
		@Nullable M test(int blockX, int blockZ);
	}

	@FunctionalInterface
	public interface Control {
		boolean stopped();
	}

	public record Tile(int regionChunkX, int regionChunkZ) {
		public int minBlockX() {
			return this.regionChunkX << 4;
		}

		public int minBlockZ() {
			return this.regionChunkZ << 4;
		}

		/** Tests every point from one origin-anchored lattice that falls inside this tile. */
		public <M> TileResult<M> findFirst(
			int latticeOriginX,
			int latticeOriginZ,
			int step,
			Control control,
			PointEvaluator<M> evaluator
		) {
			if (step < 1) {
				throw new IllegalArgumentException("step must be positive");
			}
			int minX = Math.max(MIN_COORDINATE, this.minBlockX());
			int minZ = Math.max(MIN_COORDINATE, this.minBlockZ());
			int maxX = Math.min(MAX_COORDINATE, this.minBlockX() + TILE_BLOCKS - 1);
			int maxZ = Math.min(MAX_COORDINATE, this.minBlockZ() + TILE_BLOCKS - 1);
			if (minX > maxX || minZ > maxZ) {
				return new TileResult<>(null, 0L);
			}

			long firstX = firstAtOrAfter(minX, latticeOriginX, step);
			long firstZ = firstAtOrAfter(minZ, latticeOriginZ, step);
			long samples = 0L;
			for (long z = firstZ; z <= maxZ; z += step) {
				for (long x = firstX; x <= maxX; x += step) {
					if (control.stopped()) {
						return new TileResult<>(null, samples);
					}
					samples++;
					M match = evaluator.test((int) x, (int) z);
					if (match != null) {
						return new TileResult<>(match, samples);
					}
				}
			}
			return new TileResult<>(null, samples);
		}

		/** Returns the number of points from an origin-anchored lattice that fall inside this tile. */
		public long sampleCount(int latticeOriginX, int latticeOriginZ, int step) {
			if (step < 1) {
				throw new IllegalArgumentException("step must be positive");
			}
			int minX = Math.max(MIN_COORDINATE, this.minBlockX());
			int minZ = Math.max(MIN_COORDINATE, this.minBlockZ());
			int maxX = Math.min(MAX_COORDINATE, this.minBlockX() + TILE_BLOCKS - 1);
			int maxZ = Math.min(MAX_COORDINATE, this.minBlockZ() + TILE_BLOCKS - 1);
			if (minX > maxX || minZ > maxZ) {
				return 0L;
			}
			long firstX = firstAtOrAfter(minX, latticeOriginX, step);
			long firstZ = firstAtOrAfter(minZ, latticeOriginZ, step);
			long countX = firstX > maxX ? 0L : ((long) maxX - firstX) / step + 1L;
			long countZ = firstZ > maxZ ? 0L : ((long) maxZ - firstZ) / step + 1L;
			return countX * countZ;
		}

		private static long firstAtOrAfter(int minimum, int origin, int step) {
			long delta = (long) minimum - origin;
			long index = Math.floorDiv(delta, step);
			long coordinate = (long) origin + index * step;
			return coordinate < minimum ? coordinate + step : coordinate;
		}
	}

	public record TileResult<M>(@Nullable M match, long samples) {
		public TileResult {
			if (samples < 0L) {
				throw new IllegalArgumentException("samples must not be negative");
			}
		}
	}

	public record Result<M>(
		@Nullable M match,
		long samples,
		boolean timedOut
	) {
	}

	private static final class Search<M> implements Control {
		private final int centerTileX;
		private final int centerTileZ;
		private final int lastRing;
		private final Supplier<TileEvaluator<M>> evaluators;
		private final Executor executor;
		private final int parallelism;
		private final long timeoutSeconds;
		private final long startedNanos;
		private final long timeoutNanos;
		private final AtomicBoolean stopped = new AtomicBoolean();
		private final AtomicLong samples = new AtomicLong();
		private final CancellableFuture<Result<M>> result;
		@Nullable
		private volatile ScheduledFuture<?> timeout;

		Search(
			int originX,
			int originZ,
			Supplier<TileEvaluator<M>> evaluators,
			Executor executor,
			int parallelism,
			long timeoutSeconds
		) {
			this.centerTileX = Math.floorDiv(originX, TILE_BLOCKS);
			this.centerTileZ = Math.floorDiv(originZ, TILE_BLOCKS);
			int minTile = Math.floorDiv(MIN_COORDINATE, TILE_BLOCKS);
			int maxTile = Math.floorDiv(MAX_COORDINATE, TILE_BLOCKS);
			this.lastRing = Math.max(
				Math.max(this.centerTileX - minTile, maxTile - this.centerTileX),
				Math.max(this.centerTileZ - minTile, maxTile - this.centerTileZ)
			);
			this.evaluators = evaluators;
			this.executor = executor;
			this.parallelism = parallelism;
			this.timeoutSeconds = timeoutSeconds;
			this.startedNanos = System.nanoTime();
			this.timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
			this.result = new CancellableFuture<>(this::cancel);
		}

		CompletableFuture<Result<M>> future() {
			return this.result;
		}

		void start() {
			this.timeout = LocateSearchExecutors.timer().schedule(
				this::finishTimeout,
				this.timeoutSeconds,
				TimeUnit.SECONDS
			);
			this.searchRing(0);
		}

		@Override
		public boolean stopped() {
			if (this.stopped.get() || Thread.currentThread().isInterrupted()) {
				return true;
			}
			if (System.nanoTime() - this.startedNanos >= this.timeoutNanos) {
				this.finishTimeout();
				return true;
			}
			return false;
		}

		private void searchRing(int ring) {
			if (this.result.isDone() || this.stopped()) {
				return;
			}
			if (ring > this.lastRing) {
				this.finish(null, false);
				return;
			}
			int tileCount = ring == 0 ? 1 : Math.multiplyExact(8, ring);
			int tasks = Math.min(this.parallelism, tileCount);
			List<CompletableFuture<Void>> parts = new ArrayList<>(tasks);
			try {
				for (int task = 0; task < tasks; task++) {
					int first = task;
					parts.add(CompletableFuture.runAsync(
						() -> this.scanRing(ring, first, tasks, tileCount),
						this.executor
					));
				}
			} catch (RuntimeException failure) {
				this.fail(failure);
				return;
			}
			CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new))
				.whenComplete((ignored, failure) -> {
					if (failure != null) {
						this.fail(unwrap(failure));
						return;
					}
					if (this.result.isDone() || this.stopped()) {
						return;
					}
					this.searchRing(ring + 1);
				});
		}

		private void scanRing(int ring, int first, int stride, int tileCount) {
			TileEvaluator<M> evaluator = Objects.requireNonNull(
				this.evaluators.get(), "tile evaluator supplier returned null"
			);
			for (int index = first; index < tileCount && !this.stopped(); index += stride) {
				Tile tile = this.tile(ring, index);
				TileResult<M> tileResult = Objects.requireNonNull(
					evaluator.find(tile, this), "tile evaluator returned null"
				);
				this.samples.addAndGet(tileResult.samples());
				M match = tileResult.match();
				if (match != null) {
					this.finish(match, false);
					return;
				}
			}
		}

		private Tile tile(int ring, int index) {
			if (ring == 0) {
				return new Tile(this.centerTileX << 2, this.centerTileZ << 2);
			}
			int side = ring * 2;
			int dx;
			int dz;
			if (index < side) {
				dx = -ring + index;
				dz = -ring;
			} else if (index < side * 2) {
				dx = ring;
				dz = -ring + index - side;
			} else if (index < side * 3) {
				dx = ring - (index - side * 2);
				dz = ring;
			} else {
				dx = -ring;
				dz = ring - (index - side * 3);
			}
			return new Tile((this.centerTileX + dx) << 2, (this.centerTileZ + dz) << 2);
		}

		private void finishTimeout() {
			this.finish(null, true);
		}

		private void finish(@Nullable M match, boolean timedOut) {
			if (timedOut || match != null) {
				this.stopped.set(true);
			}
			if (this.result.complete(new Result<>(match, this.samples.get(), timedOut))) {
				this.stopped.set(true);
				ScheduledFuture<?> scheduled = this.timeout;
				if (scheduled != null) {
					scheduled.cancel(false);
				}
			}
		}

		private void fail(Throwable failure) {
			this.stopped.set(true);
			ScheduledFuture<?> scheduled = this.timeout;
			if (scheduled != null) {
				scheduled.cancel(false);
			}
			this.result.completeExceptionally(failure);
		}

		private void cancel() {
			this.stopped.set(true);
			ScheduledFuture<?> scheduled = this.timeout;
			if (scheduled != null) {
				scheduled.cancel(false);
			}
		}

		private static Throwable unwrap(Throwable failure) {
			Throwable current = failure;
			while ((current instanceof java.util.concurrent.CompletionException
				|| current instanceof java.util.concurrent.ExecutionException)
				&& current.getCause() != null) {
				current = current.getCause();
			}
			return current;
		}
	}

	private static final class CancellableFuture<T> extends CompletableFuture<T> {
		private final Runnable cancellation;

		CancellableFuture(Runnable cancellation) {
			this.cancellation = cancellation;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			this.cancellation.run();
			return super.cancel(mayInterruptIfRunning);
		}
	}
}
