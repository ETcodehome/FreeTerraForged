package etcodehome.freeterraforged.world.worldgen.structure.rule;

import com.google.common.collect.ImmutableSet;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.platform.RegistryUtil;
import etcodehome.freeterraforged.registries.FTFBuiltInRegistries;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;

public class StructureRules {

	public static void bootstrap() {
		register("cell_test", CellTest.CODEC);
	}
	
	public static CellTest cellTest(float cutoff, Terrain... terrainTypeBlacklist) {
		return new CellTest(cutoff, ImmutableSet.copyOf(terrainTypeBlacklist));
	}

	private static void register(String name, MapCodec<? extends StructureRule> value) {
		RegistryUtil.register(FTFBuiltInRegistries.STRUCTURE_RULE_TYPE, name, value);
	}
}
