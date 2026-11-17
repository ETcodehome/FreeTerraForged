package etcodehome.freeterraforged.platform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import dev.architectury.injectables.annotations.ExpectPlatform;
import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.core.MappedRegistry;
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
		Registry.register(registry, FTFCommon.location(name), value);
	}

	@ExpectPlatform
	public static <T> Registry<T> createRegistry(ResourceKey<Registry<T>> key) {
		return new MappedRegistry<>(key, Lifecycle.stable());
	}

	@ExpectPlatform
	public static <T> void createDataRegistry(ResourceKey<Registry<T>> key, Codec<T> codec, boolean synced) {
	}
}
