package etcodehome.freeterraforged.world.worldgen.biome.modifier.fabric;

import java.util.Map;
import java.util.Optional;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;

import etcodehome.freeterraforged.platform.RegistryUtil;
import etcodehome.freeterraforged.registries.FTFBuiltInRegistries;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.Filter;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.Order;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public class BiomeModifiersImpl {

	public static void bootstrap() {
		register("add", AddModifier.CODEC);
		register("replace", ReplaceModifier.CODEC);
		// NeoForge compatibility: mods ported from NeoForge may ship biome modifiers
		// using the platform-provided "neoforge:add_features" serializer, which has
		// no equivalent registered on Fabric. Without this, FTF fails to parse those
		// datapack files and world creation crashes (issue #287). The shim parses but
		// applies nothing, since the owning mods already register the features via
		// the Fabric BiomeModifications API.
		register("neoforge:add_features", NeoForgeAddFeaturesModifier.CODEC);
	}

	public static BiomeModifier add(Order order, GenerationStep.Decoration step, Optional<Pair<Filter.Behavior, HolderSet<Biome>>> biomes, HolderSet<PlacedFeature> features) {
		return new AddModifier(order, step, biomes.map((p) -> new Filter(p.getSecond(), p.getFirst())), features);
	}

	public static BiomeModifier replace(GenerationStep.Decoration step, Optional<HolderSet<Biome>> biomes, Map<ResourceKey<PlacedFeature>, ResourceKey<PlacedFeature>> replacements) {
		return new ReplaceModifier(step, biomes, replacements);
	}

	public static void register(String name, MapCodec<? extends BiomeModifier> value) {
		RegistryUtil.register(FTFBuiltInRegistries.BIOME_MODIFIER_TYPE, name, value);
	}
}