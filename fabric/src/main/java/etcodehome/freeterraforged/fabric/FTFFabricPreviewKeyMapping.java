package etcodehome.freeterraforged.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import etcodehome.freeterraforged.client.input.ClientCommandShortcut;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

final class FTFFabricPreviewKeyMapping {
	private static final KeyMapping OPEN_PREVIEW = new ShiftKeyMapping(
		"key.freeterraforged.open_preview",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_M,
		"key.categories.freeterraforged"
	);

	private FTFFabricPreviewKeyMapping() {
	}

	static void register() {
		KeyBindingHelper.registerKeyBinding(OPEN_PREVIEW);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			while (OPEN_PREVIEW.consumeClick()) {
				if (InputConstants.isKeyDown(
					minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT
				)) {
					ClientCommandShortcut.run(minecraft, "ftf preview");
				}
			}
		});
	}
}
