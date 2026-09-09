package raccoonman.reterraforged.world.worldgen.runtime;

import net.minecraft.resources.ResourceLocation;

public interface RequestOwnedBiomeSourceFactory {
	ResourceLocation requestOwnedFactoryId();

	RequestOwnedBiomeSource createRequestOwnedSource(PreviewSourceContext context) throws Exception;
}
