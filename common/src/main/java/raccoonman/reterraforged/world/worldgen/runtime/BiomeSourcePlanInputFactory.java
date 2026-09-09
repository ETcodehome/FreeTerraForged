package raccoonman.reterraforged.world.worldgen.runtime;

import net.minecraft.resources.ResourceLocation;

public interface BiomeSourcePlanInputFactory {
	ResourceLocation biomeSourcePlanFactoryId();

	BiomeSourcePlanInput createBiomeSourcePlanInput(WorldgenOwner owner);
}
