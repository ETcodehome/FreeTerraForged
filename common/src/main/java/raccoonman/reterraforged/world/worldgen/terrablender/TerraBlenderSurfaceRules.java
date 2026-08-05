package raccoonman.reterraforged.world.worldgen.terrablender;

import java.util.Set;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

public final class TerraBlenderSurfaceRules {
	private static final String MINECRAFT_NAMESPACE = "minecraft";

	private TerraBlenderSurfaceRules() {
	}

	public static boolean hasOnlyMinecraftNamespace(Set<String> namespaces) {
		return namespaces.size() == 1 && namespaces.contains(MINECRAFT_NAMESPACE);
	}

	/**
	 * Avoids resolving a biome when both possible branches already produced the same result. The
	 * namespace check remains available as a fallback whenever the two branches differ.
	 */
	public static <T> @Nullable T select(@Nullable T minecraftValue, @Nullable T baseValue, BooleanSupplier isMinecraftBiome) {
		if (minecraftValue == null || Objects.equals(minecraftValue, baseValue)) {
			return baseValue;
		}
		return isMinecraftBiome.getAsBoolean() ? minecraftValue : baseValue;
	}
}
