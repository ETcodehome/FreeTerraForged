package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.WorldDimensions;

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
