package raccoonman.reterraforged.concurrent.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.concurrent.Resource;

class ThreadLocalPoolLifecycleTest {
	@Test
	void leasesAreLazyBoundedAndReturnedExactlyOnce() {
		AtomicInteger constructions = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();
		ThreadLocalPool<Object> pool = new ThreadLocalPool<>(1, () -> {
			constructions.incrementAndGet();
			return new Object();
		}, ignored -> cleanups.incrementAndGet());

		Resource<Object> first = pool.get();
		Resource<Object> second = pool.get();
		Object firstValue = first.get();
		Object secondValue = second.get();
		assertNotSame(firstValue, secondValue);
		assertEquals(2, constructions.get());

		first.close();
		first.close();
		second.close();
		assertFalse(first.isOpen());
		assertEquals(2, cleanups.get());
		assertThrows(IllegalStateException.class, first::get);

		Resource<Object> reused = pool.get();
		assertTrue(reused.isOpen());
		assertSame(firstValue, reused.get());
		assertEquals(2, constructions.get());
		reused.close();
	}

	@Test
	void zeroCapacityNeverRetainsIdleValues() {
		AtomicInteger constructions = new AtomicInteger();
		ThreadLocalPool<Object> pool = new ThreadLocalPool<>(0, () -> {
			constructions.incrementAndGet();
			return new Object();
		});

		pool.get().close();
		pool.get().close();

		assertEquals(2, constructions.get());
	}
}
