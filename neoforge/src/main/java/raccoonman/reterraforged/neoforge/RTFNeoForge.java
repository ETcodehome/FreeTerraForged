package raccoonman.reterraforged.neoforge;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.data.RTFLanguageProvider;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.platform.neoforge.RegistryUtilImpl;
import raccoonman.reterraforged.server.RTFMinecraftServer;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenLifecycle;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

@Mod(RTFCommon.MOD_ID)
public class RTFNeoForge {

	public RTFNeoForge(IEventBus modEventBus, ModContainer container) {
		RTFCommon.bootstrap();

		// Register client-only listeners safely when running on the physical client
		if (FMLEnvironment.dist == Dist.CLIENT) {
			modEventBus.addListener(RTFNeoForgeClient::registerPresetEditors);
		}

		modEventBus.addListener(RTFNeoForge::gatherData);
		RegistryUtilImpl.register(modEventBus);
		NeoForge.EVENT_BUS.addListener(RTFNeoForge::tagsUpdated);
	}

	private static void tagsUpdated(TagsUpdatedEvent event) {
		if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
			return;
		}
		var server = ServerLifecycleHooks.getCurrentServer();
		if (server == null) {
			return;
		}
		((RTFMinecraftServer) server).getFeatureTemplateManager().onReload(server.getResourceManager());
		WorldgenLifecycle.tagsReloaded(server);
	}

	private static void gatherData(GatherDataEvent event) {
		boolean includeClient = true;
		DataGenerator generator = event.getGenerator();
		PackOutput output = generator.getPackOutput();

		generator.addProvider(includeClient, new RTFLanguageProvider.EnglishUS(output));
		generator.addProvider(includeClient, PackMetadataGenerator.forFeaturePack(
				output, Component.translatable(RTFTranslationKeys.METADATA_DESCRIPTION)));
	}
}
