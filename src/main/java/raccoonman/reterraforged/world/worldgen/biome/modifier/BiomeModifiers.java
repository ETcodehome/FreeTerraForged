package raccoonman.reterraforged.world.worldgen.biome.modifier;

import java.util.Map;
import java.util.Optional;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import raccoonman.reterraforged.platform.RegistryUtil;
import raccoonman.reterraforged.registries.RTFBuiltInRegistries;
import raccoonman.reterraforged.world.worldgen.biome.modifier.neoforge.AddModifier;
import raccoonman.reterraforged.world.worldgen.biome.modifier.neoforge.ReplaceModifier;

public class BiomeModifiers {

	/**
	 * In a native NeoForge port, bootstrap usually handles the registration
	 * of the Codecs for your specific modifier types.
	 */
	public static void bootstrap() {
		// Register your custom modifier serializers here
		register("add", AddModifier.CODEC);
		register("replace", ReplaceModifier.CODEC);
	}

	@SafeVarargs
	public static BiomeModifier add(Order order, GenerationStep.Decoration step, Holder<PlacedFeature>... features) {
		return add(order, step, HolderSet.direct(features));
	}

	public static BiomeModifier add(Order order, GenerationStep.Decoration step, HolderSet<PlacedFeature> features) {
		return add(order, step, Optional.empty(), features);
	}

	@SafeVarargs
	public static BiomeModifier add(Order order, GenerationStep.Decoration step, Filter.Behavior filterBehavior, HolderSet<Biome> biomes, Holder<PlacedFeature>... features) {
		return add(order, step, filterBehavior, biomes, HolderSet.direct(features));
	}

	public static BiomeModifier add(Order order, GenerationStep.Decoration step, Filter.Behavior filterBehavior, HolderSet<Biome> biomes, HolderSet<PlacedFeature> features) {
		return add(order, step, Optional.of(Pair.of(filterBehavior, biomes)), features);
	}

	/**
	 * Native implementation of the 'add' modifier logic.
	 */
	public static BiomeModifier add(Order order, GenerationStep.Decoration step, Optional<Pair<Filter.Behavior, HolderSet<Biome>>> biomes, HolderSet<PlacedFeature> features) {
		return new AddModifier(order, step, biomes, features);
	}

	public static BiomeModifier replace(GenerationStep.Decoration step, Map<ResourceKey<PlacedFeature>, Holder<PlacedFeature>> replacements) {
		return replace(step, Optional.empty(), replacements);
	}

	public static BiomeModifier replace(GenerationStep.Decoration step, HolderSet<Biome> biomes, Map<ResourceKey<PlacedFeature>, Holder<PlacedFeature>> replacements) {
		return replace(step, Optional.of(biomes), replacements);
	}

	/**
	 * Native implementation of the 'replace' modifier logic.
	 */
	public static BiomeModifier replace(GenerationStep.Decoration step, Optional<HolderSet<Biome>> biomes, Map<ResourceKey<PlacedFeature>, Holder<PlacedFeature>> replacements) {
		return new ReplaceModifier(step, biomes, replacements);
	}

	public static void register(String name, Codec<? extends BiomeModifier> value) {
		RegistryUtil.register(RTFBuiltInRegistries.BIOME_MODIFIER_TYPE, name, value);
	}
}