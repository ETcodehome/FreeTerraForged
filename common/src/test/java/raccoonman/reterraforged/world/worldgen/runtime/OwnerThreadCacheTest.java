package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;

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
