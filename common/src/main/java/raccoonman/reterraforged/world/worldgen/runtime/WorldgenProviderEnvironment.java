package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Optional;

public interface WorldgenProviderEnvironment {
	boolean isLoaded(String modId);

	Optional<String> version(String modId);
}
