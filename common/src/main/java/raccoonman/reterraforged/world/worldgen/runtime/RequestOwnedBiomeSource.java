package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;

import net.minecraft.world.level.biome.BiomeSource;

/** A fresh preview source and any request-confined state that must be released with it. */
public record RequestOwnedBiomeSource(
	BiomeSource source,
	AutoCloseable lifecycle
) implements AutoCloseable {
	private static final AutoCloseable NOOP = () -> {
	};

	public RequestOwnedBiomeSource {
		source = Objects.requireNonNull(source, "source");
		lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
	}

	public static RequestOwnedBiomeSource immutable(BiomeSource source) {
		return new RequestOwnedBiomeSource(source, NOOP);
	}

	@Override
	public void close() throws Exception {
		this.lifecycle.close();
	}
}
