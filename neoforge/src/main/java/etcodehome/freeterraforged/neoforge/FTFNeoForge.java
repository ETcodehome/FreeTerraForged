package etcodehome.freeterraforged.neoforge;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.platform.neoforge.RegistryUtilImpl;
import etcodehome.freeterraforged.server.FTFMinecraftServer;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenLifecycle;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenResourceRevision;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

@Mod(FTFCommon.MOD_ID)
public class FTFNeoForge {

	public FTFNeoForge(IEventBus modEventBus, ModContainer container) {
		FTFCommon.bootstrap();

		// Register client-only listeners safely when running on the physical client
		if (FMLEnvironment.dist == Dist.CLIENT) {
			modEventBus.addListener(FTFNeoForgeClient::registerPresetEditors);
		}

		modEventBus.addListener(FTFNeoForge::gatherData);
		RegistryUtilImpl.register(modEventBus);
		NeoForge.EVENT_BUS.addListener(FTFNeoForge::tagsUpdated);
	}

	private static void tagsUpdated(TagsUpdatedEvent event) {
		if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
			return;
		}
		var server = ServerLifecycleHooks.getCurrentServer();
		if (server == null) {
			return;
		}
		((FTFMinecraftServer) server).getFeatureTemplateManager().onReload(server.getResourceManager());
		WorldgenLifecycle.tagsReloaded(
			server, ((WorldgenResourceRevision) server).advanceWorldgenResourceRevision()
		);
	}

	private static void gatherData(GatherDataEvent event) {
		boolean includeClient = true;
		DataGenerator generator = event.getGenerator();
		PackOutput output = generator.getPackOutput();
		generator.addProvider(includeClient, PackMetadataGenerator.forFeaturePack(
				output, Component.translatable(FTFTranslationKeys.METADATA_DESCRIPTION)));
	}
}
