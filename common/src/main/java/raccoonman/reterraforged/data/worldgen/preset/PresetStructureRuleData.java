package raccoonman.reterraforged.data.worldgen.preset;

import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.structure.rule.StructureRule;

public class PresetStructureRuleData {
	public static final ResourceKey<StructureRule> CELL_TEST = createKey("cell_test");
	
	public static void bootstrap(Preset preset, BootstrapContext<StructureRule> ctx) {
		//TODO have this be a separate toggle for each structure once we fix structure options
		//ctx.register(CELL_TEST, StructureRules.cellTest(0.225F, TerrainType.MOUNTAIN_CHAIN, TerrainType.MOUNTAINS_1, TerrainType.MOUNTAINS_2, TerrainType.MOUNTAINS_3));
	}
	
	private static ResourceKey<StructureRule> createKey(String name) {
        return ResourceKey.create(RTFRegistries.STRUCTURE_RULE, RTFCommon.location(name));
	}
}
