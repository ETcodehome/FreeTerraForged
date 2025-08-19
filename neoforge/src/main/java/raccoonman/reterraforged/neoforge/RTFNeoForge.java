package raccoonman.reterraforged.neoforge;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.data.RTFLanguageProvider;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.platform.neoforge.RegistryUtilImpl;

@Mod("reterraforged")
public class RTFNeoForge {

    public RTFNeoForge(IEventBus modEventBus, ModContainer container) {
    	RTFCommon.bootstrap();

    	if (FMLEnvironment.dist == Dist.CLIENT) {
    		modEventBus.addListener(RTFNeoForgeClient::registerPresetEditors);
    	}
    	modEventBus.addListener(RTFNeoForge::gatherData);
    	RegistryUtilImpl.register(modEventBus);
    }
    
    private static void gatherData(GatherDataEvent event) {
    	boolean includeClient = true; //event.includeClient();
    	DataGenerator generator = event.getGenerator();
    	PackOutput output = generator.getPackOutput();

    	generator.addProvider(includeClient, new RTFLanguageProvider.EnglishUS(output));
    	generator.addProvider(includeClient, PackMetadataGenerator.forFeaturePack(output, Component.translatable(RTFTranslationKeys.METADATA_DESCRIPTION)));
    }
}