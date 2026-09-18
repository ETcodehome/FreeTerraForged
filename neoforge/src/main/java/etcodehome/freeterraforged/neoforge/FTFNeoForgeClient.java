package etcodehome.freeterraforged.neoforge;

import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetConfigScreen;

class FTFNeoForgeClient {

	public static void registerPresetEditors(RegisterPresetEditorsEvent event) {
		// TODO we probably shouldn't register this for the default preset
		event.register(WorldPresets.NORMAL, (screen, ctx) -> new PresetConfigScreen(screen));
	}
}
