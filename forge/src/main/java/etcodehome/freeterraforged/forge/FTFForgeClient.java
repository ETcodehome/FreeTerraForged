package etcodehome.freeterraforged.forge;

import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.client.event.RegisterPresetEditorsEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetConfigScreen;
import etcodehome.freeterraforged.network.FlowFieldSync;

class FTFForgeClient {

	public static void onClientSetup(FMLClientSetupEvent event) {
		FlowFieldSync.registerClientReceiver();
	}

	public static void registerPresetEditors(RegisterPresetEditorsEvent event) {
		event.register(WorldPresets.NORMAL, (screen, ctx) -> new PresetConfigScreen(screen));
	}
}
