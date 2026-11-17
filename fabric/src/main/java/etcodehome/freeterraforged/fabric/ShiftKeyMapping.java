package etcodehome.freeterraforged.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/** A fixed Shift modifier for Fabric's vanilla key-mapping implementation. */
final class ShiftKeyMapping extends KeyMapping {
	ShiftKeyMapping(String name, InputConstants.Type type, int keyCode, String category) {
		super(name, type, keyCode, category);
	}

	@Override
	public Component getTranslatedKeyMessage() {
		return Component.translatable("key.freeterraforged.shift", super.getTranslatedKeyMessage());
	}
}
