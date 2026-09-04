package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.WorldDimensions;

/**
 * The completed world-creation graph before an integrated server exists.
 *
 * <p>Providers may resolve a mechanism only into request-independent immutable input here. They
 * must not publish a mechanism-owned runtime source or retain mutable registries or generators.
 */
public record PreServerWorldgenContext(
	RegistryAccess.Frozen registries,
	WorldDimensions dimensions,
	long seed
) {
	public PreServerWorldgenContext {
		registries = Objects.requireNonNull(registries, "registries");
		dimensions = Objects.requireNonNull(dimensions, "dimensions");
	}
}
