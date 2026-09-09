package raccoonman.reterraforged.concurrent.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.concurrent.Resource;

class ArrayPoolLifecycleTest {
	@Test
	void capacityIsBoundedAndOwnerCloseDropsIdleArrays() {
		ArrayPool<Object> pool = ArrayPool.of(1, Object[]::new);
		Resource<Object[]> first = pool.get(16);
		Resource<Object[]> second = pool.get(16);

		first.close();
		second.close();
		assertEquals(1, pool.retainedSize());
		assertFalse(first.isOpen());
		assertFalse(second.isOpen());

		pool.close();
		assertEquals(0, pool.retainedSize());
		assertThrows(IllegalStateException.class, () -> pool.get(16));
	}

	@Test
	void activeArrayCannotReturnAfterOwnerClose() {
		ArrayPool<Object> pool = ArrayPool.of(1, Object[]::new);
		Resource<Object[]> active = pool.get(16);

		pool.close();
		active.close();

		assertEquals(0, pool.retainedSize());
		assertFalse(active.isOpen());
	}

	@Test
	void concurrentCloseReturnsOneResourceAtMostOnce() {
		ArrayPool<Object> pool = ArrayPool.of(4, Object[]::new);
		Resource<Object[]> active = pool.get(16);

		CompletableFuture<?>[] closes = IntStream.range(0, 64)
			.mapToObj(ignored -> CompletableFuture.runAsync(active::close))
			.toArray(CompletableFuture[]::new);
		CompletableFuture.allOf(closes).join();

		assertEquals(1, pool.retainedSize());
		assertFalse(active.isOpen());
	}

	@Test
	void constructorCannotReturnStorageSmallerThanTheRequestedLease() {
		ArrayPool<Object> pool = ArrayPool.of(1, ignored -> new Object[1]);

		assertThrows(IllegalStateException.class, () -> pool.get(2));
		assertEquals(0, pool.retainedSize());
	}
}
