package etcodehome.freeterraforged.platform;

import com.mojang.serialization.Codec;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

/**
 * Placeholder class that uses Architecturies Impl framework to substitute a platform specific when compiling
 * @see etcodehome.freeterraforged.platform.neoforge.RegistryUtilImpl
 * @see etcodehome.freeterraforged.platform.fabric.RegistryUtilImpl
 */

public final class RegistryUtil {

	@ExpectPlatform
	public static <T> void register(Registry<T> registry, String name, T value) {
		throw new IllegalStateException();
	}

	@ExpectPlatform
	public static <T> Registry<T> createRegistry(ResourceKey<Registry<T>> key) {
		throw new IllegalStateException();
	}

	@ExpectPlatform
	public static <T> void createDataRegistry(ResourceKey<Registry<T>> key, Codec<T> codec, boolean synced) {
		throw new IllegalStateException();
	}
}
