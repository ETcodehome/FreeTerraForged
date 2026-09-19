package etcodehome.freeterraforged.world.worldgen.feature.chance;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.platform.RegistryUtil;
import etcodehome.freeterraforged.registries.FTFBuiltInRegistries;

public class FTFChanceModifiers {

	public static void bootstrap() {
		register("elevation", ElevationChanceModifier.CODEC);
		register("biome_edge", BiomeEdgeChanceModifier.CODEC);
	}
	
	public static ElevationChanceModifier elevation(float from, float to) {
		return elevation(from, to, false);
	}
	
	public static ElevationChanceModifier elevation(float from, float to, boolean exclusive) {
		return new ElevationChanceModifier(from, to, exclusive);
	}
	
	public static BiomeEdgeChanceModifier biomeEdge(float from, float to) {
		return biomeEdge(from, to, false);
	}
	
	public static BiomeEdgeChanceModifier biomeEdge(float from, float to, boolean exclusive) {
		return new BiomeEdgeChanceModifier(from, to, exclusive);
	}
	
	private static void register(String name, MapCodec<? extends ChanceModifier> placement) {
		RegistryUtil.register(FTFBuiltInRegistries.CHANCE_MODIFIER_TYPE, name, placement);
	}
}
