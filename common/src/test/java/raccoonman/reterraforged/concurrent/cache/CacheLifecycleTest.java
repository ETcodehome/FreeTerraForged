package raccoonman.reterraforged.concurrent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CacheLifecycleTest {
	@Test
	void automaticCapacityEvictionReleasesOwnershipAndOwnerCloseIsIdempotent() {
		Cache<TrackedEntry> cache = new Cache<>(1, 1, 1, TimeUnit.DAYS,
			raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap::new);
		AtomicInteger closes = new AtomicInteger();

		cache.computeIfAbsent(1L, ignored -> new TrackedEntry(closes));
		cache.computeIfAbsent(2L, ignored -> new TrackedEntry(closes));
		assertEquals(1, closes.get());

		cache.close();
		cache.close();
		assertEquals(2, closes.get());
		org.junit.jupiter.api.Assertions.assertNull(cache.getIfOpen(1L));
		assertThrows(IllegalStateException.class, () ->
			cache.computeIfAbsent(3L, ignored -> new TrackedEntry(closes))
		);
	}

	@Test
	void explicitRemovalReleasesAnOwnedValue() {
		Cache<TrackedEntry> cache = new Cache<>(1, 1, 1, TimeUnit.DAYS,
			raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap::new);
		AtomicInteger closes = new AtomicInteger();
		cache.computeIfAbsent(1L, ignored -> new TrackedEntry(closes));

		cache.remove(1L);
		assertEquals(1, closes.get());
		cache.close();
		assertEquals(1, closes.get());
	}

	@Test
	void conditionalRemovalOnlyReleasesTheExpectedGeneration() {
		Cache<TrackedEntry> cache = new Cache<>(1, 1, 1, TimeUnit.DAYS,
			raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap::new);
		AtomicInteger firstCloses = new AtomicInteger();
		AtomicInteger replacementCloses = new AtomicInteger();
		TrackedEntry first = cache.computeIfAbsent(1L, ignored -> new TrackedEntry(firstCloses));
		TrackedEntry replacement = new TrackedEntry(replacementCloses);

		org.junit.jupiter.api.Assertions.assertFalse(cache.removeIfOpen(1L, replacement));
		assertEquals(0, firstCloses.get());
		assertEquals(0, replacementCloses.get());

		org.junit.jupiter.api.Assertions.assertTrue(cache.removeIfOpen(1L, first));
		assertEquals(1, firstCloses.get());
		assertEquals(0, replacementCloses.get());
		cache.close();
		assertEquals(1, firstCloses.get());
		assertEquals(0, replacementCloses.get());
	}

	@Test
	void ownerCloseReleasesACompletableValueThatFinishesLate() {
		CompletableFuture<LateValue> task = new CompletableFuture<>();
		CacheEntry<LateValue> entry = CacheEntry.supply(task);
		AtomicInteger closes = new AtomicInteger();

		entry.close();
		task.complete(new LateValue(closes));

		assertEquals(1, closes.get());
	}

	@Test
	void ownerCloseCancelsAProducerThatOwnsLateResultDisposal() {
		AtomicInteger closes = new AtomicInteger();
		OwnedTask task = new OwnedTask(closes);
		CacheEntry<LateValue> entry = CacheEntry.supply(task);

		entry.close();
		entry.close();

		assertEquals(1, closes.get());
		org.junit.jupiter.api.Assertions.assertTrue(task.isCancelled());
	}

	@Test
	void ownerClosePreservesProducerOwnershipAcrossAResultTransformation() {
		AtomicInteger producerCloses = new AtomicInteger();
		AtomicInteger valueCloses = new AtomicInteger();
		OwnedTask producer = new OwnedTask(producerCloses);
		CompletableFuture<LateValue> transformed = producer.thenApply(ignored -> new LateValue(valueCloses));
		CacheEntry<LateValue> entry = CacheEntry.supply(transformed, producer);

		entry.close();

		assertEquals(1, producerCloses.get());
		assertEquals(0, valueCloses.get());
		org.junit.jupiter.api.Assertions.assertTrue(producer.isCancelled());
	}

	@Test
	void capacityOverflowCannotEvictPendingOwnerState() {
		Cache<CacheEntry<LateValue>> cache = new Cache<>(1, 1, 1, TimeUnit.DAYS,
			raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap::new);
		AtomicInteger pendingValueCloses = new AtomicInteger();
		AtomicInteger completedValueCloses = new AtomicInteger();
		OwnedTask pending = new OwnedTask(new AtomicInteger());
		CacheEntry<LateValue> pendingEntry = cache.computeIfAbsent(1L,
			ignored -> CacheEntry.supply(pending));
		CacheEntry<LateValue> completedEntry = cache.computeIfAbsent(2L, ignored ->
			CacheEntry.supply(CompletableFuture.completedFuture(new LateValue(completedValueCloses)))
		);

		assertSame(pendingEntry, cache.getIfOpen(1L));
		assertSame(completedEntry, cache.getIfOpen(2L));
		org.junit.jupiter.api.Assertions.assertFalse(pending.isCancelled());

		pending.complete(new LateValue(pendingValueCloses));
		cache.trim();
		org.junit.jupiter.api.Assertions.assertNull(cache.getIfOpen(1L));
		assertSame(completedEntry, cache.getIfOpen(2L));
		assertEquals(1, pendingValueCloses.get());

		cache.close();
		assertEquals(1, completedValueCloses.get());
	}

	@Test
	void expiryCannotDiscardPendingOwnerState() {
		Cache<CacheEntry<LateValue>> cache = new Cache<>(1, 0, 1, TimeUnit.DAYS,
			raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap::new);
		OwnedTask pending = new OwnedTask(new AtomicInteger());
		CacheEntry<LateValue> pendingEntry = cache.computeIfAbsent(1L,
			ignored -> CacheEntry.supply(pending));

		cache.poll();

		assertSame(pendingEntry, cache.getIfOpen(1L));
		org.junit.jupiter.api.Assertions.assertFalse(pending.isCancelled());
		cache.close();
	}

	@Test
	void closingPendingProducerMayReenterCacheWithoutMapLockDeadlock() {
		Cache<CacheEntry<LateValue>> cache = new Cache<>(1, 1, 1, TimeUnit.DAYS,
			raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap::new);
		OwnedTask pending = new OwnedTask(new AtomicInteger());
		pending.whenComplete((value, failure) -> cache.trim());
		cache.computeIfAbsent(1L, ignored -> CacheEntry.supply(pending));

		assertTimeoutPreemptively(Duration.ofSeconds(2), cache::close);
		org.junit.jupiter.api.Assertions.assertTrue(pending.isCancelled());
	}

	@Test
	void ownerCloseAttemptsEveryRetirementWhenOneValueThrows() {
		Cache<FailingEntry> cache = new Cache<>(4, 1, 1, TimeUnit.DAYS,
			raccoonman.reterraforged.concurrent.cache.map.StampedLongMap::new);
		AtomicInteger closes = new AtomicInteger();
		cache.computeIfAbsent(1L, ignored -> new FailingEntry(closes, true));
		cache.computeIfAbsent(2L, ignored -> new FailingEntry(closes, false));

		assertThrows(IllegalStateException.class, cache::close);

		assertEquals(2, closes.get());
	}

	private static final class TrackedEntry implements ExpiringEntry {
		private final AtomicInteger closes;

		private TrackedEntry(AtomicInteger closes) {
			this.closes = closes;
		}

		@Override
		public long getTimestamp() {
			return System.currentTimeMillis();
		}

		@Override
		public void close() {
			this.closes.incrementAndGet();
		}
	}

	private record FailingEntry(AtomicInteger closes, boolean fail) implements ExpiringEntry {
		@Override
		public long getTimestamp() {
			return System.currentTimeMillis();
		}

		@Override
		public void close() {
			this.closes.incrementAndGet();
			if (this.fail) {
				throw new IllegalStateException("synthetic retirement failure");
			}
		}
	}

	private record LateValue(AtomicInteger closes) implements AutoCloseable {
		@Override
		public void close() {
			this.closes.incrementAndGet();
		}
	}

	private static final class OwnedTask extends CompletableFuture<LateValue> implements SafeCloseable {
		private final AtomicInteger closes;

		private OwnedTask(AtomicInteger closes) {
			this.closes = closes;
		}

		@Override
		public void close() {
			if (this.cancel(false)) {
				this.closes.incrementAndGet();
			}
		}
	}
}
