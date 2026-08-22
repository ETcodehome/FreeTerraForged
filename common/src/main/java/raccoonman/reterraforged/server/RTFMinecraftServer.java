package raccoonman.reterraforged.server;

import raccoonman.reterraforged.world.worldgen.feature.template.template.FeatureTemplateManager;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan;

public interface RTFMinecraftServer {
	FeatureTemplateManager getFeatureTemplateManager();

	DynamicOrePlan getDynamicOrePlan();

	void publishDynamicOrePlan(DynamicOrePlan plan);
}
