package etcodehome.freeterraforged.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

/** A key mapping whose primary key is displayed with a fixed left-Shift modifier. */
final class LeftShiftKeyMapping extends KeyMapping {
	LeftShiftKeyMapping(String name, int keyCode, String category) {
		super(
			name,
			KeyConflictContext.IN_GAME,
			KeyModifier.NONE,
			InputConstants.Type.KEYSYM,
			keyCode,
			category
		);
	}

	@Override
	public Component getTranslatedKeyMessage() {
		return Component.translatable("key.freeterraforged.shift", super.getTranslatedKeyMessage());
	}
}
