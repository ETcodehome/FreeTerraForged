package etcodehome.freeterraforged.forge;

import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import net.minecraftforge.client.event.RegisterPresetEditorsEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetConfigScreen;
import etcodehome.freeterraforged.network.FlowFieldSync;

class FTFForgeClient {

	public static void onClientSetup(FMLClientSetupEvent event) {
		FlowFieldSync.registerClientReceiver();
	}

	public static void registerPresetEditors(RegisterPresetEditorsEvent event) {
		event.register(FTFWorldPresets.FREETERRAFORGED, (screen, ctx) -> new PresetConfigScreen(screen));
	}
}
