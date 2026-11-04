package etcodehome.freeterraforged.neoforge;

import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetConfigScreen;

class FTFNeoForgeClient {

	public static void registerPresetEditors(RegisterPresetEditorsEvent event) {
		event.register(FTFWorldPresets.FREETERRAFORGED, (screen, ctx) -> new PresetConfigScreen(screen));
	}
}
