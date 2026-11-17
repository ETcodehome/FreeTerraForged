package etcodehome.freeterraforged.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import etcodehome.freeterraforged.client.input.ClientCommandShortcut;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

final class FTFFabricPresetKeyMapping {
	private static final KeyMapping OPEN_PRESET_VIEW = new ShiftKeyMapping(
		"key.freeterraforged.open_preset_view",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_P,
		"key.categories.freeterraforged"
	);

	private FTFFabricPresetKeyMapping() {
	}

	static void register() {
		KeyBindingHelper.registerKeyBinding(OPEN_PRESET_VIEW);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			while (OPEN_PRESET_VIEW.consumeClick()) {
				if (Screen.hasShiftDown()) {
					ClientCommandShortcut.run(minecraft, "ftf preset view");
				}
			}
		});
	}
}
