package etcodehome.freeterraforged.server.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

/**
 * Finds the grid point nearest to an origin that passes a test, spreading the work across an executor.
 *
 * <p>Points sit {@code step} blocks apart and are visited in bands of square rings around the origin. Each band is
 * split into tasks that only compute and never wait on one another, so a search cannot stall a shared pool. Once a
 * match is found the search only continues while an unvisited ring could still hold a closer point, so the result
 * is the nearest matching point (ties broken by x, then z) however the tasks interleave.
 */
public final class ParallelGridSearch {
	private static final int POINTS_PER_TASK = 256;
	private static final int TASKS_PER_THREAD = 4;
	private static final int DEADLINE_CHECK_MASK = 63;

	private ParallelGridSearch() {
	}

	/** Tests one point. Each task gets its own instance, so implementations may keep mutable scratch state. */
	@FunctionalInterface
	public interface PointTest {
		boolean test(int x, int z);
	}

	public record Match(int x, int z, long distanceSq) {

		boolean isCloserThan(@Nullable Match other) {
			if (other == null) {
				return true;
			}
			if (this.distanceSq != other.distanceSq) {
				return this.distanceSq < other.distanceSq;
			}
			return this.x != other.x ? this.x < other.x : this.z < other.z;
		}
	}

	/**
	 * @param match          the nearest matching point, or null if there was none
	 * @param searchedRadius radius in blocks around the origin that was searched completely
	 * @param samples        number of points tested
	 * @param timedOut       whether the deadline stopped the search; a match found before then may not be the nearest
	 */
	public record Result(@Nullable Match match, int searchedRadius, long samples, boolean timedOut) {
	}

	public static CompletableFuture<Result> findNearest(int originX, int originZ, int step, int radius, Supplier<PointTest> tests, Executor executor, int parallelism, long deadlineNanos) {
		if (step < 1 || radius < 0 || parallelism < 1) {
			throw new IllegalArgumentException("step and parallelism must be positive and radius must not be negative");
		}
		return new Search(originX, originZ, step, radius, tests, executor, parallelism, deadlineNanos).searchFrom(0);
	}

	private static final class Search {
		private final int originX;
		private final int originZ;
		private final int step;
		private final int radius;
		private final long radiusSq;
		private final int lastRing;
		private final Supplier<PointTest> tests;
		private final Executor executor;
		private final int parallelism;
		private final long deadlineNanos;
		private final AtomicLong bestDistanceSq = new AtomicLong(Long.MAX_VALUE);
		private final AtomicLong samples = new AtomicLong();
		private final AtomicBoolean stopped = new AtomicBoolean();
		private final AtomicBoolean timedOut = new AtomicBoolean();
		@Nullable
		private volatile Match best;

		Search(int originX, int originZ, int step, int radius, Supplier<PointTest> tests, Executor executor, int parallelism, long deadlineNanos) {
			this.originX = originX;
			this.originZ = originZ;
			this.step = step;
			this.radius = radius;
			this.radiusSq = (long) radius * radius;
			this.lastRing = radius / step;
			this.tests = tests;
			this.executor = executor;
			this.parallelism = parallelism;
			this.deadlineNanos = deadlineNanos;
		}

		CompletableFuture<Result> searchFrom(int firstRing) {
			Match best = this.best;
			if (firstRing > this.lastRing || (best != null && this.ringDistanceSq(firstRing) > best.distanceSq()) || this.checkDeadline()) {
				return CompletableFuture.completedFuture(this.result(firstRing - 1));
			}
			int endRing = this.bandEnd(firstRing);
			int[] points = this.collect(firstRing, endRing);
			int count = points.length / 2;
			int taskCount = Math.max(1, Math.min(this.parallelism * TASKS_PER_THREAD, (count + POINTS_PER_TASK - 1) / POINTS_PER_TASK));
			List<CompletableFuture<Match>> parts = new ArrayList<>(taskCount);
			for (int task = 0; task < taskCount; task++) {
				int from = (int) ((long) count * task / taskCount);
				int to = (int) ((long) count * (task + 1) / taskCount);
				parts.add(CompletableFuture.supplyAsync(() -> this.scan(points, from, to), this.executor));
			}
			return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
				Match closest = this.best;
				for (CompletableFuture<Match> part : parts) {
					Match match = part.join();
					if (match != null && match.isCloserThan(closest)) {
						closest = match;
					}
				}
				this.best = closest;
				if (this.timedOut.get()) {
					return CompletableFuture.completedFuture(this.result(firstRing - 1));
				}
				return this.searchFrom(endRing + 1);
			});
		}

		@Nullable
		private Match scan(int[] points, int from, int to) {
			try {
				PointTest test = this.tests.get();
				Match closest = null;
				long tested = 0;
				for (int n = from; n < to; n++) {
					if (((n - from) & DEADLINE_CHECK_MASK) == 0 && this.checkDeadline()) {
						break;
					}
					int i = points[2 * n];
					int j = points[2 * n + 1];
					long distanceSq = this.distanceSq(i, j);
					if (distanceSq > this.bestDistanceSq.get()) {
						continue;
					}
					int x = this.originX + i * this.step;
					int z = this.originZ + j * this.step;
					tested++;
					if (test.test(x, z)) {
						Match match = new Match(x, z, distanceSq);
						if (match.isCloserThan(closest)) {
							closest = match;
						}
						this.bestDistanceSq.accumulateAndGet(distanceSq, Math::min);
					}
				}
				this.samples.addAndGet(tested);
				return closest;
			} catch (RuntimeException | Error failure) {
				this.stopped.set(true);
				throw failure;
			}
		}

		private boolean checkDeadline() {
			if (this.stopped.get()) {
				return true;
			}
			if (System.nanoTime() - this.deadlineNanos >= 0) {
				this.timedOut.set(true);
				this.stopped.set(true);
				return true;
			}
			return false;
		}

		/** Last ring of the band starting at {@code firstRing}, sized to give every worker a few tasks. */
		private int bandEnd(int firstRing) {
			long target = (long) this.parallelism * TASKS_PER_THREAD * POINTS_PER_TASK;
			long points = 0;
			int ring = firstRing;
			while (true) {
				points += ringSize(ring);
				if (points >= target || ring >= this.lastRing) {
					return ring;
				}
				ring++;
			}
		}

		/** Grid offsets of every point in rings {@code firstRing..endRing} that lies within the radius, packed as (i, j) pairs. */
		private int[] collect(int firstRing, int endRing) {
			long capacity = 0;
			for (int ring = firstRing; ring <= endRing; ring++) {
				capacity += ringSize(ring);
			}
			int[] points = new int[Math.toIntExact(capacity * 2)];
			int n = 0;
			for (int ring = firstRing; ring <= endRing; ring++) {
				if (ring == 0) {
					n = this.put(points, n, 0, 0);
					continue;
				}
				for (int a = -ring; a < ring; a++) {
					n = this.put(points, n, a, -ring);
					n = this.put(points, n, ring, a);
					n = this.put(points, n, -a, ring);
					n = this.put(points, n, -ring, -a);
				}
			}
			return n == points.length ? points : Arrays.copyOf(points, n);
		}

		private int put(int[] points, int n, int i, int j) {
			if (this.distanceSq(i, j) > this.radiusSq) {
				return n;
			}
			points[n] = i;
			points[n + 1] = j;
			return n + 2;
		}

		private long distanceSq(int i, int j) {
			long dx = (long) i * this.step;
			long dz = (long) j * this.step;
			return dx * dx + dz * dz;
		}

		private long ringDistanceSq(int ring) {
			long distance = (long) ring * this.step;
			return distance * distance;
		}

		private Result result(int lastCompletedRing) {
			int searchedRadius = lastCompletedRing >= this.lastRing ? this.radius : Math.max(0, lastCompletedRing) * this.step;
			return new Result(this.best, searchedRadius, this.samples.get(), this.timedOut.get());
		}

		private static long ringSize(int ring) {
			return ring == 0 ? 1 : 8L * ring;
		}
	}
}
