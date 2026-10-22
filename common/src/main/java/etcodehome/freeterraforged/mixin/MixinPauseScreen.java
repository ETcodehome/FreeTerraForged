package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import etcodehome.freeterraforged.world.worldgen.FTFAcceleration;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

@Mixin(PauseScreen.class)
public abstract class MixinPauseScreen extends Screen {
	private static final int FTF_PRIMARY_COLOR = 0xFFE0E0E0;
	private static final int FTF_SECONDARY_COLOR = 0xFF909090;
	private static final int FTF_MARGIN = 6;
	private static final int FTF_LINE_HEIGHT = 10;

	protected MixinPauseScreen(Component title) {
		super(title);
	}

	@Inject(
		method = "render",
		at = @At("TAIL")
	)
	private void freeterraforged$renderAccelerationStatus(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		if(!FTFAcceleration.isWorldActive()) {
			return;
		}

		String primary = "FreeTerraForged worldgen: " + FTFAcceleration.backendLabel();
		String secondary = FTFAcceleration.detail() + "  " + FTFAcceleration.compilerDetail();

		int secondaryY = this.height - FTF_MARGIN - FTF_LINE_HEIGHT;
		int primaryY = secondaryY - FTF_LINE_HEIGHT;

		guiGraphics.drawString(this.font, primary, FTF_MARGIN, primaryY, FTF_PRIMARY_COLOR, true);
		guiGraphics.drawString(this.font, secondary, FTF_MARGIN, secondaryY, FTF_SECONDARY_COLOR, true);
	}
}
