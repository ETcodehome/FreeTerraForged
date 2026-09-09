package etcodehome.freeterraforged.data.worldgen.preset;

import java.util.List;

import com.google.common.collect.ImmutableList;

import etcodehome.freeterraforged.world.worldgen.feature.template.decorator.TemplateDecorator;
import etcodehome.freeterraforged.world.worldgen.feature.template.decorator.TemplateDecorators;
import etcodehome.freeterraforged.world.worldgen.feature.template.decorator.TreeContext;
import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;

public class PresetTemplateDecoratorLists {
	public static final List<TemplateDecorator<TreeContext>> BEEHIVE_RARITY_005 = ImmutableList.of(TemplateDecorators.tree(new BeehiveDecorator(0.05F)));
	
}
