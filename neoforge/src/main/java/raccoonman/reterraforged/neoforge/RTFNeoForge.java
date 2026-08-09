package raccoonman.reterraforged.neoforge;

import com.mojang.serialization.MapCodec;
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
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.data.RTFLanguageProvider;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.platform.neoforge.RegistryUtilImpl;
import raccoonman.reterraforged.world.worldgen.biome.modifier.neoforge.AddModifier;
import raccoonman.reterraforged.world.worldgen.biome.modifier.neoforge.ReplaceModifier;

@Mod(RTFCommon.MOD_ID)
public class RTFNeoForge {

	public RTFNeoForge(IEventBus modEventBus, ModContainer container) {
		RTFCommon.bootstrap();

		// Register RTF's biome modifier codec types into NeoForge's own serialiser
		// registry so NeoForge can decode neoforge/biome_modifier/*.json at runtime.
		// RTFBuiltInRegistries.BIOME_MODIFIER_TYPE (forge:biome_modifier_serializers)
		// is RTF's internal dispatch registry used by BiomeModifier.DIRECT_CODEC for
		// data-gen and Fabric; it is separate and both registries must be populated.
		DeferredRegister<MapCodec<? extends BiomeModifier>> biomeModifierSerializers =
				DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, RTFCommon.MOD_ID);
		biomeModifierSerializers.register("add",     () -> AddModifier.CODEC);
		biomeModifierSerializers.register("replace", () -> ReplaceModifier.CODEC);
		biomeModifierSerializers.register(modEventBus);

		// Register client-only listeners safely when running on the physical client
		if (FMLEnvironment.dist == Dist.CLIENT) {
			modEventBus.addListener(RTFNeoForgeClient::registerPresetEditors);
		}

		modEventBus.addListener(RTFNeoForge::gatherData);
		RegistryUtilImpl.register(modEventBus);
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