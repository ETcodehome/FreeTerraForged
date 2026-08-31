package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/** Request-local SPI discovery; results are not retained in a process-global semantic registry. */
public final class WorldgenCapabilityDiscovery {
	private WorldgenCapabilityDiscovery() {
	}

	public static List<WorldgenCapabilityProvider> discover(ClassLoader classLoader) {
		return ServiceLoader.load(WorldgenCapabilityProvider.class, classLoader)
			.stream()
			.map(ServiceLoader.Provider::get)
			.sorted(Comparator.comparing(provider -> provider.id().toString()))
			.toList();
	}
}
