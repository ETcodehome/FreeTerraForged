package etcodehome.freeterraforged.fabric;

import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator.Pack;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.fabric.network.FTFFabricNetworking;
import etcodehome.freeterraforged.server.FTFMinecraftServer;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenLifecycle;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenResourceRevision;

public class FTFFabric implements ModInitializer, DataGeneratorEntrypoint {

	@Override
	public void onInitialize() {
		FTFCommon.bootstrap();
		FTFFabricNetworking.init();
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (!success) {
				return;
			}
			((FTFMinecraftServer) server).getFeatureTemplateManager().onReload(resources);
			WorldgenLifecycle.tagsReloaded(
				server, ((WorldgenResourceRevision) server).advanceWorldgenResourceRevision()
			);
		});
	}

	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		Pack pack = fabricDataGenerator.createPack();
		pack.addProvider((FabricDataOutput output) -> PackMetadataGenerator.forFeaturePack(output, Component.translatable(FTFTranslationKeys.METADATA_DESCRIPTION)));
	}
}
