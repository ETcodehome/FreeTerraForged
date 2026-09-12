package etcodehome.freeterraforged.neoforge;

import com.mojang.serialization.MapCodec;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.FTFLanguageProvider;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.platform.neoforge.RegistryUtilImpl;
import etcodehome.freeterraforged.neoforge.compat.NeoForgeBiomePreviewIntegrations;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.neoforge.AddModifier;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.neoforge.ReplaceModifier;

@Mod(FTFCommon.MOD_ID)
public class FTFNeoForge {

	public FTFNeoForge(IEventBus modEventBus, ModContainer container) {
		FTFCommon.bootstrap();
		NeoForgeBiomePreviewIntegrations.bootstrap();

		// Register FTF's biome modifier codec types into NeoForge's own serialiser
		// registry so NeoForge can decode neoforge/biome_modifier/*.json at runtime.
		// FTFBuiltInRegistries.BIOME_MODIFIER_TYPE (forge:biome_modifier_serializers)
		// is FTF's internal dispatch registry used by BiomeModifier.DIRECT_CODEC for
		// data-gen and Fabric; it is separate and both registries must be populated.
		DeferredRegister<MapCodec<? extends BiomeModifier>> biomeModifierSerializers =
				DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, FTFCommon.MOD_ID);
		biomeModifierSerializers.register("add",     () -> AddModifier.CODEC);
		biomeModifierSerializers.register("replace", () -> ReplaceModifier.CODEC);
		biomeModifierSerializers.register(modEventBus);

		// Register client-only listeners safely when running on the physical client
		if (FMLEnvironment.dist == Dist.CLIENT) {
			modEventBus.addListener(FTFNeoForgeClient::registerPresetEditors);
		}

		modEventBus.addListener(FTFNeoForge::gatherData);
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
