package etcodehome.freeterraforged.world.worldgen.surface;

import java.util.List;
import java.util.function.Function;

import etcodehome.freeterraforged.world.worldgen.surface.rule.StrataRule;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

public interface FTFSurfaceSystem {
	List<List<StrataRule.Layer>> getOrCreateStrata(ResourceLocation name, Function<RandomSource, List<List<StrataRule.Layer>>> factory);
}
