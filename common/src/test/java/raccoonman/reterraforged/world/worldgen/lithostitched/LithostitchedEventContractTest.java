package raccoonman.reterraforged.world.worldgen.lithostitched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.worldgen.lithostitched.impl.event.LithostitchedEvent;

class LithostitchedEventContractTest {
	@Test
	void eventListenersCanBeStatefulAndNonRepeatable() {
		AtomicInteger calls = new AtomicInteger();
		LithostitchedEvent<Runnable> event = new LithostitchedEvent<>(
			listeners -> () -> listeners.forEach(Runnable::run)
		);
		event.register(() -> {
			if (calls.incrementAndGet() > 1) {
				throw new IllegalStateException("consumed");
			}
		});

		event.invoker().run();
		assertEquals(1, calls.get());
		assertThrows(IllegalStateException.class, () -> event.invoker().run());
		assertEquals(2, calls.get());
	}

	@Test
	void registeringAfterCreatingAnInvokerChangesThatInvoker() {
		AtomicInteger calls = new AtomicInteger();
		LithostitchedEvent<Runnable> event = new LithostitchedEvent<>(
			listeners -> () -> listeners.forEach(Runnable::run)
		);
		Runnable invoker = event.invoker();
		event.register(calls::incrementAndGet);

		invoker.run();
		assertEquals(1, calls.get());
	}
}
