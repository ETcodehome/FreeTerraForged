package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.concurrent.cache.SafeCloseable;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;

class TileCachePendingOwnershipTest {
	@Test
	void advisoryHighFanoutIsCoalescedAndCloseStillOwnsTheAdmittedProducer() throws Exception {
		ControlledGenerator generator = new ControlledGenerator(true);
		TileCache cache = new TileCache(0, true, generator);

		cache.queue(0, 0);
		assertTrue(generator.entered.await(5, TimeUnit.SECONDS));
		CompletableFuture<?>[] submissions = IntStream.range(0, 64)
			.mapToObj(tile -> CompletableFuture.runAsync(() -> cache.queue(tile + 1, -tile - 1)))
			.toArray(CompletableFuture[]::new);
		CompletableFuture.allOf(submissions).join();

		assertEquals(1, generator.tasks.size());
		assertTrue(generator.tasks.stream().noneMatch(CompletableFuture::isCancelled));

		generator.release.countDown();
		assertTrue(generator.returned.await(5, TimeUnit.SECONDS));
		cache.close();
		assertTrue(generator.tasks.stream().allMatch(CompletableFuture::isCancelled));
		assertFalse(generator.tasks.stream().anyMatch(task -> task.closedMoreThanOnce));
	}

	@Test
	void failedPrefetchIsRetiredSoDemandCanRetryTheTile() throws Exception {
		ControlledGenerator generator = new ControlledGenerator(false);
		TileCache cache = new TileCache(0, true, generator);
		cache.queue(7, 11);
		assertTrue(generator.awaitTaskCount(1));
		OwnedTileTask failed = generator.tasks.getFirst();
		failed.completeExceptionally(new IllegalStateException("synthetic tile failure"));

		assertThrows(IllegalStateException.class, () -> cache.acquire(7, 11));
		CompletableFuture<Void> retried = CompletableFuture.runAsync(() -> {
			try (TileCache.Lease ignored = cache.acquire(7, 11)) {
			}
		});

		assertTrue(generator.awaitTaskCount(2));
		assertEquals(2, generator.tasks.size());
		assertFalse(generator.tasks.getLast().isCancelled());
		cache.close();
		assertThrows(java.util.concurrent.CompletionException.class, retried::join);
		assertTrue(generator.tasks.getLast().isCancelled());
	}

	private static final class ControlledGenerator extends TileGenerator {
		private final List<OwnedTileTask> tasks = new CopyOnWriteArrayList<>();
		private final boolean blockReturn;
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final CountDownLatch returned = new CountDownLatch(1);

		private ControlledGenerator(boolean blockReturn) {
			super(null, null, 1, 0, 1);
			this.blockReturn = blockReturn;
		}

		@Override
		public int getTileCellCount() {
			return 1 << 22;
		}

		@Override
		public CompletableFuture<Tile> generate(int tileX, int tileZ) {
			OwnedTileTask task = new OwnedTileTask();
			this.tasks.add(task);
			this.entered.countDown();
			if (this.blockReturn) {
				try {
					this.release.await();
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new java.util.concurrent.CancellationException("Synthetic prefetch interrupted");
				}
			}
			this.returned.countDown();
			return task;
		}

		private boolean awaitTaskCount(int count) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (this.tasks.size() < count && System.nanoTime() < deadline) {
				Thread.sleep(1L);
			}
			return this.tasks.size() >= count;
		}
	}

	private static final class OwnedTileTask extends CompletableFuture<Tile> implements SafeCloseable {
		private boolean closed;
		private boolean closedMoreThanOnce;

		@Override
		public synchronized void close() {
			if (this.closed) {
				this.closedMoreThanOnce = true;
				return;
			}
			this.closed = true;
			this.cancel(true);
		}
	}
}
