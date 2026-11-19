package etcodehome.freeterraforged.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import etcodehome.freeterraforged.client.input.ClientCommandShortcut;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

final class FTFNeoForgePreviewKeyMapping {
	private static final LeftShiftKeyMapping OPEN_PREVIEW = new LeftShiftKeyMapping(
		"key.freeterraforged.open_preview",
		GLFW.GLFW_KEY_M,
		"key.categories.freeterraforged"
	);

	private FTFNeoForgePreviewKeyMapping() {
	}

	static void register(RegisterKeyMappingsEvent event) {
		event.register(OPEN_PREVIEW);
	}

	static void onClientTick(ClientTickEvent.Post event) {
		Minecraft minecraft = Minecraft.getInstance();
		while (OPEN_PREVIEW.consumeClick()) {
			if (InputConstants.isKeyDown(
				minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT
			)) {
				ClientCommandShortcut.run(minecraft, "ftf preview");
			}
		}
	}
}
