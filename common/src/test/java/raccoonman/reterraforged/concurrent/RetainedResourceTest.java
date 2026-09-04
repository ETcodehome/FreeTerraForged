package raccoonman.reterraforged.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.concurrent.cache.SafeCloseable;

class RetainedResourceTest {
	@Test
	void retirementDefersRecyclingUntilEveryLeaseCloses() {
		AtomicInteger closes = new AtomicInteger();
		RetainedResource<Tracked> owner = new RetainedResource<>(new Tracked(closes));
		RetainedResource.Lease<Tracked> first = owner.acquire();
		RetainedResource.Lease<Tracked> second = owner.acquire();

		owner.close();
		assertEquals(0, closes.get());
		assertNull(owner.acquire());

		first.close();
		assertEquals(0, closes.get());
		second.close();
		assertEquals(1, closes.get());
		second.close();
		owner.close();
		assertEquals(1, closes.get());
		assertThrows(IllegalStateException.class, second::value);
	}

	private record Tracked(AtomicInteger closes) implements SafeCloseable {
		@Override
		public void close() {
			this.closes.incrementAndGet();
		}
	}
}
