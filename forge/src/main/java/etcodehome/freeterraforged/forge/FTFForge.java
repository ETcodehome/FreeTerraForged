package etcodehome.freeterraforged.forge;

import com.mojang.serialization.Codec;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.FTFLanguageProvider;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.platform.forge.RegistryUtilImpl;
import etcodehome.freeterraforged.forge.compat.ForgeBiomePreviewIntegrations;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.forge.AddModifier;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.forge.ReplaceModifier;

@Mod(FTFCommon.MOD_ID)
public class FTFForge {

	public FTFForge() {
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

		FTFCommon.bootstrap();
		ForgeBiomePreviewIntegrations.bootstrap();

		DeferredRegister<Codec<? extends BiomeModifier>> biomeModifierSerializers =
				DeferredRegister.create(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, FTFCommon.MOD_ID);
		biomeModifierSerializers.register("add",     () -> AddModifier.CODEC);
		biomeModifierSerializers.register("replace", () -> ReplaceModifier.CODEC);
		biomeModifierSerializers.register(modEventBus);

		if (FMLEnvironment.dist == Dist.CLIENT) {
			modEventBus.addListener(FTFForgeClient::registerPresetEditors);
			modEventBus.addListener(FTFForgeClient::onClientSetup);
		}

		modEventBus.addListener(FTFForge::gatherData);
		RegistryUtilImpl.register(modEventBus);
	}

	private static void gatherData(GatherDataEvent event) {
		boolean includeClient = true;
		DataGenerator generator = event.getGenerator();
		PackOutput output = generator.getPackOutput();

		generator.addProvider(includeClient, new FTFLanguageProvider.EnglishUS(output));
		generator.addProvider(includeClient, PackMetadataGenerator.forFeaturePack(
				output, Component.translatable(FTFTranslationKeys.METADATA_DESCRIPTION)));
	}
}
