package etcodehome.freeterraforged.platform.forge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;

import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader.RegistryData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DataPackRegistriesHooks;
import net.minecraftforge.registries.DataPackRegistryEvent;
import net.minecraftforge.registries.DeferredRegister;
import etcodehome.freeterraforged.FTFCommon;

public final class RegistryUtilImpl {
	private static final List<DataRegistry<?>> DATA_REGISTRIES = Collections.synchronizedList(new ArrayList<>());
	private static final Map<ResourceKey<?>, DeferredRegister<?>> REGISTERS = new ConcurrentHashMap<>();

	public static void register(IEventBus bus) {
		bus.addListener((DataPackRegistryEvent.NewRegistry event) -> {
			DATA_REGISTRIES.forEach((registry) -> registry.register(event));
		});

		REGISTERS.values().forEach((register) -> register.register(bus));
	}

	public static <T> void register(Registry<T> registry, String name, T value) {
		DeferredRegister<T> deferredRegistry = getRegister(registry.key());
		deferredRegistry.register(name, () -> value);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static <T> Registry<T> createRegistry(ResourceKey<Registry<T>> key) {
		MappedRegistry<T> registry = new MappedRegistry<>(key, Lifecycle.stable(), false);
		if (BuiltInRegistries.REGISTRY instanceof MappedRegistry rootRegistry) {
			rootRegistry.unfreeze();
		}
		((WritableRegistry) BuiltInRegistries.REGISTRY).register(key, registry, Lifecycle.stable());
		return registry;
	}

	public static <T> void createDataRegistry(ResourceKey<Registry<T>> key, Codec<T> codec, boolean synced) {
		DATA_REGISTRIES.add(new DataRegistry<>(key, codec, synced));
	}

	public static <T extends GameRules.Value<T>> GameRules.Key<T> registerGameRule(String name, GameRules.Category category, GameRules.Type<T> type) {
		return GameRules.register(name, category, type);
	}

	public static List<RegistryData<?>> getDynamicRegistries() {
		return DataPackRegistriesHooks.getDataPackRegistries();
	}

	@SuppressWarnings("unchecked")
	private static <T> DeferredRegister<T> getRegister(ResourceKey<? extends Registry<T>> key) {
		return (DeferredRegister<T>) REGISTERS.computeIfAbsent(key, (k) -> DeferredRegister.create(key, FTFCommon.MOD_ID));
	}

	private record DataRegistry<T>(ResourceKey<Registry<T>> key, Codec<T> codec, boolean synced) {

		public void register(DataPackRegistryEvent.NewRegistry event) {
			event.dataPackRegistry(this.key, this.codec, this.synced ? this.codec : null);
		}
	}
}