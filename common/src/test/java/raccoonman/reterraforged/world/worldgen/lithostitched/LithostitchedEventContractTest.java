package raccoonman.reterraforged.world.worldgen.lithostitched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
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

	@Test
	void equalNaturalFinalizerInvocationsFreezeAnImmutableOutput() {
		LithostitchedInjectionBridge.RepeatableOutput<String> output =
			new LithostitchedInjectionBridge.RepeatableOutput<>("test listeners");
		ArrayList<String> first = new ArrayList<>(List.of("a", "b"));
		output.accept(first);
		first.clear();
		output.accept(List.of("a", "b"));

		assertEquals(List.of("a", "b"), output.freeze());
		assertThrows(UnsupportedOperationException.class, () -> output.freeze().add("c"));
	}

	@Test
	void changingNaturalFinalizerOutputIsRejected() {
		LithostitchedInjectionBridge.RepeatableOutput<String> output =
			new LithostitchedInjectionBridge.RepeatableOutput<>("test listeners");
		output.accept(List.of("first"));

		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> output.accept(List.of("second"))
		);
		assertEquals(
			"test listeners changed output across one creation-graph finalization",
			failure.getMessage()
		);
	}

	@Test
	void concurrentEqualObservationsRemainStable() throws Exception {
		LithostitchedInjectionBridge.RepeatableOutput<Integer> output =
			new LithostitchedInjectionBridge.RepeatableOutput<>("test listeners");
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
			for (int index = 0; index < 64; index++) {
				futures.add(executor.submit(() -> output.accept(List.of(1, 2, 3))));
			}
			for (var future : futures) {
				future.get();
			}
		}

		assertEquals(List.of(1, 2, 3), output.freeze());
	}
}
