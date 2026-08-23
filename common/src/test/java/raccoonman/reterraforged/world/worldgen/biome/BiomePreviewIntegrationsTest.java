package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class BiomePreviewIntegrationsTest {
	@Test
	void opensAndClosesRegisteredIntegrationSessions() {
		AtomicInteger state = new AtomicInteger();
		BiomePreviewIntegration integration = new BiomePreviewIntegration() {
			@Override
			public String id() {
				return "reterraforged:test";
			}

			@Override
			public Session open(Context context) {
				state.incrementAndGet();
				return state::decrementAndGet;
			}
		};

		try (BiomePreviewIntegrations.Registration ignored = BiomePreviewIntegrations.register(integration)) {
			try (BiomePreviewIntegration.Session session = BiomePreviewIntegrations.open(null)) {
				assertEquals(1, state.get());
			}
			assertEquals(0, state.get());
		}
	}

	@Test
	void reportsIntegrationFailuresToTheResolver() {
		IllegalStateException failure = new IllegalStateException("preview initialization failed");
		BiomePreviewIntegration integration = new BiomePreviewIntegration() {
			@Override
			public String id() {
				return "reterraforged:failing_test";
			}

			@Override
			public Session open(Context context) {
				throw failure;
			}
		};

		try (BiomePreviewIntegrations.Registration ignored = BiomePreviewIntegrations.register(integration)) {
			try (BiomePreviewIntegration.Session session = BiomePreviewIntegrations.open(null, error -> assertSame(failure, error))) {
				assertSame(BiomePreviewIntegration.Session.NONE, session);
			}
		}
	}
}
