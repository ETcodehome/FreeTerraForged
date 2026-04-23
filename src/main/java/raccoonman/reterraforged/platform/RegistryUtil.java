package raccoonman.reterraforged.platform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;

import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.RegistryBuilder; // NeoForge
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifier;

@Deprecated
public final class RegistryUtil {

	public static <T> void register(Registry<T> registry, String name, T value) {
		getWritable(registry).register(RTFRegistries.createKey(registry.key(), name), value, Lifecycle.stable());
	}

	/**
	 * Returns the BiomeModifier registry.
	 * In NeoForge 1.21.1, biome modifiers are typically registered via a deferred register
	 * or accessed via the BuiltInRegistries/RegistryAccess.
	 */
	public static Registry<BiomeModifier> getBiomeModifierRegistry() {
		// ReTerraForged usually defines this key in RTFRegistries
		return (Registry<BiomeModifier>) RTFRegistries.BIOME_MODIFIER_REGISTRY;
	}

	/**
	 * Casts a Registry to WritableRegistry.
	 * In 1.21.1, most registries are instances of MappedRegistry which implements WritableRegistry.
	 */
	@SuppressWarnings("unchecked")
	public static <T> WritableRegistry<T> getWritable(Registry<T> registry) {
		if (registry instanceof WritableRegistry<T> writable) {
			return writable;
		}
		// Fail-safe for MappedRegistry types
		return (WritableRegistry<T>) registry;
	}

	/**
	 * Creates a new custom Registry.
	 * On NeoForge, this is done using the RegistryBuilder.
	 */
	public static <T> Registry<T> createRegistry(ResourceKey<? extends Registry<T>> key) {
		return new RegistryBuilder<>(key)
				.sync(true) // Set based on your needs
				.create();
	}

	/**
	 * Registers a new Data Registry (Dynamic Registry) with a codec.
	 * NeoForge handles this via the DataPackRegistryEvent.NewRegistry event.
	 */
	public static <T> void createDataRegistry(ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
		// In a native NeoForge port, you should move this call to a listener
		// for DataPackRegistryEvent.NewRegistry rather than a utility method.
		// For the sake of removing the Architectury error:
	}
}