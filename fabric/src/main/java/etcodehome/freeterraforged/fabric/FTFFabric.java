package etcodehome.freeterraforged.fabric;

import etcodehome.freeterraforged.client.command.FTFCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator.Pack;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.data.PackOutput;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.FTFLanguageProvider;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.data.worldgen.FTFDefaultPresetDataProvider;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import etcodehome.freeterraforged.fabric.network.FTFFabricNetworking;
import etcodehome.freeterraforged.server.FTFMinecraftServer;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenLifecycle;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenResourceRevision;

public class FTFFabric implements ModInitializer, DataGeneratorEntrypoint {

	@Override
	public void onInitialize() {
		FTFCommon.bootstrap();
		FTFFabricNetworking.init();
		if (System.getProperty("fabric-api.datagen") == null) {
			FabricLoader.getInstance().getModContainer(FTFCommon.MOD_ID).ifPresentOrElse(container -> {
				boolean registered = ResourceManagerHelper.registerBuiltinResourcePack(
					FTFWorldPresets.DEFAULT_PRESET_PACK_ID,
					FTFWorldPresets.DEFAULT_PRESET_PACK_RESOURCE_PATH,
					container,
					false
				);
				if (!registered) {
					FTFCommon.LOGGER.error("Failed to register the built-in FreeTerraForged default preset data pack");
				}
			}, () -> FTFCommon.LOGGER.error("Could not find the FreeTerraForged mod container to register its default preset data pack"));
		}
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (!success) {
				return;
			}
			((FTFMinecraftServer) server).getFeatureTemplateManager().onReload(resources);
			WorldgenLifecycle.tagsReloaded(
				server, ((WorldgenResourceRevision) server).advanceWorldgenResourceRevision()
			);
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			FTFCommands.register(dispatcher);
		});
	}

	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		Pack pack = fabricDataGenerator.createPack();

		pack.addProvider((FabricDataOutput output) -> new FTFLanguageProvider.EnglishUS(output));
		pack.addProvider((FabricDataOutput output) -> PackMetadataGenerator.forFeaturePack(output, Component.translatable(FTFTranslationKeys.METADATA_DESCRIPTION)));
		pack.addProvider((output, registries) -> new FTFDefaultPresetDataProvider(
			new PackOutput(output.getOutputFolder().resolve(FTFWorldPresets.DEFAULT_PRESET_PACK_RESOURCE_PATH)),
			registries
		));
	}
}
