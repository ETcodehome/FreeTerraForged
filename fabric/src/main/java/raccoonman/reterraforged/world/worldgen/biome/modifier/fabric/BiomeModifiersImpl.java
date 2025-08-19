package raccoonman.reterraforged.world.worldgen.biome.modifier.fabric;

import java.util.Map;
import java.util.Optional;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import raccoonman.reterraforged.platform.RegistryUtil;
import raccoonman.reterraforged.registries.RTFBuiltInRegistries;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifier;
import raccoonman.reterraforged.world.worldgen.biome.modifier.Filter;
import raccoonman.reterraforged.world.worldgen.biome.modifier.Order;

public class BiomeModifiersImpl {
	
	public static void bootstrap() {
		register("add", AddModifier.CODEC);
		register("replace", ReplaceModifier.CODEC);
		
		//prevent forge biome modifiers from being loaded
		//FIXME this is a bad way to do this 
		register("neoforge:none", Dummy.makeCodec());
		register("neoforge:add_features", Dummy.makeCodec());
		register("neoforge:remove_features", Dummy.makeCodec());
		register("neoforge:add_spawns", Dummy.makeCodec());
		register("neoforge:remove_spawns", Dummy.makeCodec());
	}
	
	public static BiomeModifier add(Order order, GenerationStep.Decoration step, Optional<Pair<Filter.Behavior, HolderSet<Biome>>> biomes, HolderSet<PlacedFeature> features) {
		return new AddModifier(order, step, biomes.map((p) -> new Filter(p.getSecond(), p.getFirst())), features);
	}

	public static BiomeModifier replace(GenerationStep.Decoration step, Optional<HolderSet<Biome>> biomes, Map<ResourceKey<PlacedFeature>, Holder<PlacedFeature>> replacements) {
		return new ReplaceModifier(step, biomes, replacements);
	}
	
	public static void register(String name, MapCodec<? extends BiomeModifier> value) {
		RegistryUtil.register(RTFBuiltInRegistries.BIOME_MODIFIER_TYPE, name, value);
	}

	private record Dummy() implements BiomeModifier	{
		
		@Override
		public MapCodec<Dummy> codec() {
			return makeCodec();
		}
		
		public static MapCodec<Dummy> makeCodec() {
			return MapCodec.unit(Dummy::new);
		}
	};
}
