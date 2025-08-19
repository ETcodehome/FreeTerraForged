package raccoonman.reterraforged.platform.fabric;

import com.mojang.serialization.Codec;

import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameRules;
import raccoonman.reterraforged.RTFCommon;

import java.util.List;

public class RegistryUtilImpl {
	
//	public static <T> WritableRegistry<T> getWritable(Registry<T> registry) {
//		return (WritableRegistry<T>) registry;
//	}
//
//	@SuppressWarnings("unchecked")
//	public static <T> Registry<T> createRegistry(ResourceKey<? extends Registry<T>> key) {
//		return FabricRegistryBuilder.createSimple((ResourceKey<Registry<T>>) key).buildAndRegister();
//	}
//
//	public static <T> void createDataRegistry(ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
//		DynamicRegistries.register(key, codec);
//	}

	public static <T> void register(Registry<T> registry, String name, T value) {
		Registry.register(registry, RTFCommon.location(name), value);
	}

	public static <T> Registry<T> createRegistry(ResourceKey<Registry<T>> key) {
		return FabricRegistryBuilder.createSimple(key).buildAndRegister();
	}

	public static <T> void createDataRegistry(ResourceKey<Registry<T>> key, Codec<T> codec, boolean synced) {
		if(synced) {
			DynamicRegistries.registerSynced(key, codec); // TODO what does SyncOption.SKIP_WHEN_EMPTY do?
		} else {
			DynamicRegistries.register(key, codec);
		}
	}

	public static <T extends GameRules.Value<T>> GameRules.Key<T> registerGameRule(String name, GameRules.Category category, GameRules.Type<T> type) {
		return GameRuleRegistry.register(name, category, type);
	}

	public static List<RegistryDataLoader.RegistryData<?>> getDynamicRegistries() {
		return DynamicRegistries.getDynamicRegistries();
	}
}
