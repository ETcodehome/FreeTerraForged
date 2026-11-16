package etcodehome.freeterraforged.forge;

import com.mojang.brigadier.CommandDispatcher;
import etcodehome.freeterraforged.client.command.FTFCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.FTFLanguageProvider;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.platform.forge.RegistryUtilImpl;
import etcodehome.freeterraforged.server.FTFMinecraftServer;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenLifecycle;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenResourceRevision;

@Mod(FTFCommon.MOD_ID)
public class FTFForge {

	public FTFForge() {
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

		FTFCommon.bootstrap();

		if (FMLEnvironment.dist == Dist.CLIENT) {
			modEventBus.addListener(FTFForgeClient::registerPresetEditors);
			modEventBus.addListener(FTFForgeClient::onClientSetup);
		}

		modEventBus.addListener(FTFForge::gatherData);
		RegistryUtilImpl.register(modEventBus);
		MinecraftForge.EVENT_BUS.addListener(FTFForge::tagsUpdated);
		MinecraftForge.EVENT_BUS.register(this);
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

		generator.addProvider(includeClient, new FTFLanguageProvider.EnglishUS(output));
		generator.addProvider(includeClient, PackMetadataGenerator.forFeaturePack(
				output, Component.translatable(FTFTranslationKeys.METADATA_DESCRIPTION)));
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
		FTFCommands.register(dispatcher);
	}
}
