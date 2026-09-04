package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class OwnerThreadCacheTest {
	@Test
	void anotherWorkerCannotEvictTheCurrentWorkersHotCollision() throws InterruptedException {
		int capacity = 2;
		OwnerThreadCache<String> cache = new OwnerThreadCache<>(capacity);
		long firstKey = 1L;
		long collisionKey = firstKey + 1L;
		while (OwnerThreadCache.slot(firstKey, capacity)
			!= OwnerThreadCache.slot(collisionKey, capacity)) {
			collisionKey++;
		}
		cache.store(firstKey, "first");
		long otherKey = collisionKey;

		Thread worker = workerOnAnotherStripe(() -> cache.store(otherKey, "collision"));
		worker.start();
		worker.join();

		assertEquals("first", cache.find(firstKey));
		assertEquals("collision", readOnNewThread(cache, otherKey));
	}

	@Test
	void clearRetiresSharedAndLocalEntries() throws InterruptedException {
		OwnerThreadCache<String> cache = new OwnerThreadCache<>(8);
		cache.store(4L, "value");
		assertEquals("value", readOnNewThread(cache, 4L));

		cache.clear();

		assertNull(cache.find(4L));
		assertNull(readOnNewThread(cache, 4L));
	}

	@Test
	void collidingConcurrentPublicationNeverAliasesAKeyToAnotherValue() throws InterruptedException {
		OwnerThreadCache<String> cache = new OwnerThreadCache<>(1);
		AtomicBoolean stop = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread first = new Thread(() -> publish(cache, 1L, "first", stop, failure), "cache-writer-first");
		Thread second = new Thread(() -> publish(cache, 2L, "second", stop, failure), "cache-writer-second");
		first.start();
		second.start();
		try {
			for (int attempt = 0; attempt < 100_000 && failure.get() == null; attempt++) {
				String firstValue = cache.find(1L);
				if (firstValue != null && !"first".equals(firstValue)) {
					failure.compareAndSet(null, new AssertionError("First key returned " + firstValue));
				}
				String secondValue = cache.find(2L);
				if (secondValue != null && !"second".equals(secondValue)) {
					failure.compareAndSet(null, new AssertionError("Second key returned " + secondValue));
				}
			}
		} finally {
			stop.set(true);
			first.join();
			second.join();
		}
		if (failure.get() != null) {
			throw new AssertionError("Concurrent cache publication was incoherent", failure.get());
		}
	}

	private static void publish(
		OwnerThreadCache<String> cache,
		long key,
		String value,
		AtomicBoolean stop,
		AtomicReference<Throwable> failure
	) {
		try {
			while (!stop.get()) {
				cache.store(key, value);
			}
		} catch (Throwable throwable) {
			failure.compareAndSet(null, throwable);
			stop.set(true);
		}
	}

	private static Thread workerOnAnotherStripe(Runnable action) {
		int currentStripe = OwnerThreadCache.stripeIndex(Thread.currentThread().threadId());
		for (int attempt = 0; attempt < OwnerThreadCache.STRIPE_COUNT; attempt++) {
			Thread worker = new Thread(action, "owner-cache-test-" + attempt);
			if (OwnerThreadCache.stripeIndex(worker.threadId()) != currentStripe) {
				return worker;
			}
		}
		throw new AssertionError("Could not allocate a thread on another cache stripe");
	}

	private static String readOnNewThread(OwnerThreadCache<String> cache, long key)
		throws InterruptedException {
		AtomicReference<String> result = new AtomicReference<>();
		Thread reader = new Thread(() -> result.set(cache.find(key)), "owner-cache-reader");
		reader.start();
		reader.join();
		return result.get();
	}
}
