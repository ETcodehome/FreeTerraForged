package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import etcodehome.freeterraforged.client.gui.ClientCopyFeedback;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

@Mixin(GuiGraphics.class)
public abstract class MixinGuiGraphicsCopyFeedback {
	@Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true)
	private void renderFtfCopyFeedback(Font font, Style style, int mouseX, int mouseY, CallbackInfo callback) {
		if (!ClientCopyFeedback.shouldShowCopied(style)) {
			return;
		}
		((GuiGraphics) (Object) this).renderTooltip(
			font,
			Component.translatableWithFallback(
				"freeterraforged.gui.preset_view.copied", "Copied to clipboard"
			),
			mouseX,
			mouseY
		);
		callback.cancel();
	}
}
