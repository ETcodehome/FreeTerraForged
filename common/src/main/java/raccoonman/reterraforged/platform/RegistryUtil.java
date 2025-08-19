package raccoonman.reterraforged.platform;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameRules;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifier;

import java.util.List;

@Deprecated
public final class RegistryUtil {

//	public static <T> void register(Registry<T> registry, String name, T value) {
//		getWritable(registry).register(RTFRegistries.createKey(registry.key(), name), value, RegistrationInfo.BUILT_IN);
//	}
//
//	@ExpectPlatform
//	public static Registry<BiomeModifier> getBiomeModifierRegistry() {
//		throw new IllegalStateException();
//	}
//
//	@ExpectPlatform
//	public static <T> WritableRegistry<T> getWritable(Registry<T> registry) {
//		throw new IllegalStateException();
//	}
//
//	@ExpectPlatform
//	public static <T> Registry<T> createRegistry(ResourceKey<? extends Registry<T>> key) {
//		throw new IllegalStateException();
//	}
//
//	@ExpectPlatform
//	public static <T> void createDataRegistry(ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
//		throw new IllegalStateException();
//	}

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

	@ExpectPlatform
	public static <T extends GameRules.Value<T>> GameRules.Key<T> registerGameRule(String name, GameRules.Category category, GameRules.Type<T> type) {
		throw new IllegalStateException();
	}

	@ExpectPlatform
	public static List<RegistryDataLoader.RegistryData<?>> getDynamicRegistries() {
		throw new IllegalStateException();
	}

	public static List<RegistryDataLoader.RegistryData<?>> getDynamicRegistriesWithDimensions() {
		ImmutableList.Builder<RegistryDataLoader.RegistryData<?>> list = ImmutableList.builder();
		list.addAll(getDynamicRegistries());
		list.addAll(RegistryDataLoader.DIMENSION_REGISTRIES);
		return list.build();
	}
}
