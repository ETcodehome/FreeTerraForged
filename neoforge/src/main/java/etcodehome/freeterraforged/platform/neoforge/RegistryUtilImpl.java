package etcodehome.freeterraforged.platform.neoforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;
import etcodehome.freeterraforged.FTFCommon;

/**
 * RegistryUtil Implementation specific to Neoforge.
 * Compile time resolved by Architectury Transformer.
 */
public final class RegistryUtilImpl {
	private static final List<Registry<?>> BUILTIN_REGISTRIES = Collections.synchronizedList(new ArrayList<>());
	private static final List<DataRegistry<?>> DATA_REGISTRIES = Collections.synchronizedList(new ArrayList<>());
	private static final Map<ResourceKey<?>, List<Registration<?>>> REGISTRATIONS = new HashMap<>();
	private static final Set<RegistrationKey> REGISTERED_ENTRIES = new HashSet<>();
	private static final Set<ResourceKey<?>> COMPLETED_REGISTRIES = new HashSet<>();
	private static boolean attached;

	public static synchronized void register(IEventBus bus) {
		if (attached) {
			throw new IllegalStateException("Registry listeners are already attached");
		}
		attached = true;
		bus.addListener((NewRegistryEvent event) -> {
			BUILTIN_REGISTRIES.forEach(event::register);
		});

		bus.addListener((DataPackRegistryEvent.NewRegistry event) -> {
			DATA_REGISTRIES.forEach((registry) -> registry.register(event));
		});

		bus.addListener(RegistryUtilImpl::registerEntries);
	}

	// required to satisfy @expectplatform interface
	public static synchronized <T> void register(Registry<T> registry, String name, T value) {
		if (COMPLETED_REGISTRIES.contains(registry.key())) {
			throw new IllegalStateException("Cannot register entries after RegisterEvent for " + registry.key());
		}
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(FTFCommon.MOD_ID, name);
		if (!REGISTERED_ENTRIES.add(new RegistrationKey(registry.key(), id))) {
			throw new IllegalArgumentException("Duplicate registration " + name);
		}

		// Capture the key/value type together. The event API performs the matching
		// registry dispatch without an unchecked cast in our heterogeneous storage.
		REGISTRATIONS.computeIfAbsent(registry.key(), key -> new ArrayList<>())
			.add(new Registration<>(registry.key(), id, value));
	}

	private static synchronized void registerEntries(RegisterEvent event) {
		COMPLETED_REGISTRIES.add(event.getRegistryKey());
		for (Registration<?> registration : REGISTRATIONS.getOrDefault(event.getRegistryKey(), List.of())) {
			registration.register(event);
		}
	}

	// required to satisfy @expectplatform interface
	public static <T> Registry<T> createRegistry(ResourceKey<Registry<T>> key) {
		Registry<T> registry = new RegistryBuilder<>(key).create();
		BUILTIN_REGISTRIES.add(registry);
		return registry;
	}

	// required to satisfy @expectplatform interface
	public static <T> void createDataRegistry(ResourceKey<Registry<T>> key, Codec<T> codec, boolean synced) {
		DATA_REGISTRIES.add(new DataRegistry<>(key, codec, synced));
	}

	private record RegistrationKey(ResourceKey<?> registry, ResourceLocation entry) {}

	private record Registration<T>(ResourceKey<? extends Registry<T>> key, ResourceLocation id, T value) {
		void register(RegisterEvent event) {
			event.register(this.key, this.id, () -> this.value);
		}
	}

	private record DataRegistry<T>(ResourceKey<Registry<T>> key, Codec<T> codec, boolean synced) {
		public void register(DataPackRegistryEvent.NewRegistry event) {
			event.dataPackRegistry(this.key, this.codec, this.synced ? this.codec : null);
		}
	}
}
