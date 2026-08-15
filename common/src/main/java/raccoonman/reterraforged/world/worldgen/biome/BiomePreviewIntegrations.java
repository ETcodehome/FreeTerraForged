package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import raccoonman.reterraforged.RTFCommon;

/** Runtime registry for loader- and mod-specific biome preview integrations. */
public final class BiomePreviewIntegrations {
	private static final Map<String, BiomePreviewIntegration> INTEGRATIONS = new ConcurrentHashMap<>();

	private BiomePreviewIntegrations() {
	}

	public static Registration register(BiomePreviewIntegration integration) {
		Objects.requireNonNull(integration, "integration");
		String id = Objects.requireNonNull(integration.id(), "integration id");
		BiomePreviewIntegration previous = INTEGRATIONS.putIfAbsent(id, integration);
		if (previous != null && previous != integration) {
			throw new IllegalStateException("Biome preview integration already registered: " + id);
		}
		return () -> INTEGRATIONS.remove(id, integration);
	}

	static BiomePreviewIntegration.Session open(BiomePreviewIntegration.Context context) {
		return open(context, error -> {
		});
	}

	static BiomePreviewIntegration.Session open(
		BiomePreviewIntegration.Context context,
		Consumer<Throwable> failureHandler
	) {
		List<BiomePreviewIntegration.Session> sessions = new ArrayList<>();
		for (BiomePreviewIntegration integration : INTEGRATIONS.values()) {
			try {
				if (integration.supports(context)) {
					BiomePreviewIntegration.Session session = integration.open(context);
					if (session != null && session != BiomePreviewIntegration.Session.NONE) {
						sessions.add(session);
					}
				}
			} catch (RuntimeException | LinkageError error) {
				failureHandler.accept(error);
				RTFCommon.LOGGER.error(
					"Failed opening biome preview integration {}; positional selection will use its safe fallback if necessary",
					integration.id(), error
				);
			}
		}
		if (sessions.isEmpty()) {
			return BiomePreviewIntegration.Session.NONE;
		}
		return () -> {
			Collections.reverse(sessions);
			for (BiomePreviewIntegration.Session session : sessions) {
				try {
					session.close();
				} catch (RuntimeException error) {
					RTFCommon.LOGGER.error("Failed closing a biome preview integration", error);
				}
			}
		};
	}

	@FunctionalInterface
	public interface Registration extends AutoCloseable {
		@Override
		void close();
	}
}
