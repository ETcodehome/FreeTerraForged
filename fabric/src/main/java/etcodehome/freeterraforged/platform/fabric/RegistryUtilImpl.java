package etcodehome.freeterraforged.platform.fabric;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import etcodehome.freeterraforged.FTFCommon;

/**
 * RegistryUtil Implementation specific to Fabric.
 * Compile time resolved by Architectury Transformer.
 */
public class RegistryUtilImpl {

	public static <T> void register(Registry<T> registry, String name, T value) {
		Registry.register(registry, FTFCommon.location(name), value);
	}

	public static <T> Registry<T> createRegistry(ResourceKey<Registry<T>> key) {
		return FabricRegistryBuilder.createSimple(key).buildAndRegister();
	}

	public static <T> void createDataRegistry(ResourceKey<Registry<T>> key, Codec<T> codec, boolean synced) {
		if(synced) {
			DynamicRegistries.registerSynced(key, codec);
		} else {
			DynamicRegistries.register(key, codec);
		}
	}
}
