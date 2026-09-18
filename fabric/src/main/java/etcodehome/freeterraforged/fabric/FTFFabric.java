package etcodehome.freeterraforged.fabric;

import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator.Pack;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.FTFLanguageProvider;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.fabric.network.FTFFabricNetworking;
import etcodehome.freeterraforged.fabric.compat.FabricBiomePreviewIntegrations;
import etcodehome.freeterraforged.platform.RegistryUtil;

public class FTFFabric implements ModInitializer, DataGeneratorEntrypoint {

	@Override
	public void onInitialize() {
		FTFCommon.bootstrap();
		FabricBiomePreviewIntegrations.bootstrap();
		FTFFabricNetworking.init();

		RegistryUtil.createDataRegistry(FTFRegistries.BIOME_MODIFIER, BiomeModifier.DIRECT_CODEC, false);
	}

	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		Pack pack = fabricDataGenerator.createPack();

		pack.addProvider((FabricDataOutput output) -> new FTFLanguageProvider.EnglishUS(output));
		pack.addProvider((FabricDataOutput output) -> PackMetadataGenerator.forFeaturePack(output, Component.translatable(FTFTranslationKeys.METADATA_DESCRIPTION)));
	}
}
