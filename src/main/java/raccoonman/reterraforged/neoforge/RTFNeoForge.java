package raccoonman.reterraforged.neoforge; // Updated package naming

import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.data.RTFLanguageProvider;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.platform.neoforge.RegistryUtilImpl; // Renamed to neoforge

@Mod(RTFCommon.MOD_ID)
public class RTFNeoForge {

	// NeoForge injects IEventBus and ModContainer directly into the constructor
	public RTFNeoForge(IEventBus modBus, ModContainer container) {
		RTFCommon.bootstrap();

		// Check side using the ModContainer's distribution
		if (FMLEnvironment.dist == Dist.CLIENT) {
			modBus.addListener(RTFNeoForgeClient::registerPresetEditors);
		}

		modBus.addListener(RTFNeoForge::gatherData);

		// Register your deferred registries
		RegistryUtilImpl.register(modBus);
	}

	private static void gatherData(GatherDataEvent event) {
		DataGenerator generator = event.getGenerator();
		PackOutput output = generator.getPackOutput();

		// GatherDataEvent.includeClient() is still used for provider registration
		generator.addProvider(event.includeClient(), new RTFLanguageProvider.EnglishUS(output));
		generator.addProvider(event.includeClient(), PackMetadataGenerator.forFeaturePack(output, Component.translatable(RTFTranslationKeys.METADATA_DESCRIPTION)));
	}
}