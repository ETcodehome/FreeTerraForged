package etcodehome.freeterraforged.server;

import etcodehome.freeterraforged.world.worldgen.feature.template.template.FeatureTemplateManager;
import etcodehome.freeterraforged.world.worldgen.feature.ore.DynamicOrePlan;

public interface FTFMinecraftServer {
	FeatureTemplateManager getFeatureTemplateManager();

	DynamicOrePlan getDynamicOrePlan();

	void publishDynamicOrePlan(DynamicOrePlan plan);
}
