package etcodehome.freeterraforged.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import etcodehome.freeterraforged.client.input.ClientCommandShortcut;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

final class FTFNeoForgePresetKeyMapping {
	private static final KeyMapping OPEN_PRESET_VIEW = new KeyMapping(
		"key.freeterraforged.open_preset_view",
		KeyConflictContext.IN_GAME,
		KeyModifier.SHIFT,
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_P,
		"key.categories.freeterraforged"
	);

	private FTFNeoForgePresetKeyMapping() {
	}

	static void register(RegisterKeyMappingsEvent event) {
		event.register(OPEN_PRESET_VIEW);
	}

	static void onClientTick(ClientTickEvent.Post event) {
		while (OPEN_PRESET_VIEW.consumeClick()) {
			ClientCommandShortcut.run(Minecraft.getInstance(), "ftf preset view");
		}
	}
}
