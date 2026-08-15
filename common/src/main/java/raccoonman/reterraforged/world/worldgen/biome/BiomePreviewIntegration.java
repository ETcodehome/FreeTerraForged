package raccoonman.reterraforged.world.worldgen.biome;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;

/**
 * Optional bridge for biome selectors that need seed-scoped state before they can be queried by
 * the world-creation preview. Implementations are registered at runtime, keeping the core preview
 * independent of any particular biome mod.
 */
public interface BiomePreviewIntegration {
	String id();

	default boolean supports(Context context) {
		return true;
	}

	Session open(Context context);

	record Context(
		long seed,
		RegistryAccess registries,
		HolderLookup.Provider provider,
		BiomeSource biomeSource,
		ChunkGenerator generator,
		Preset preset,
		GeneratorContext generatorContext
	) {
	}

	@FunctionalInterface
	interface Session extends AutoCloseable {
		Session NONE = () -> {
		};

		@Override
		void close();
	}
}
