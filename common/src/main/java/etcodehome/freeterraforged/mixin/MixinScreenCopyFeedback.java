package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import etcodehome.freeterraforged.client.gui.ClientCopyFeedback;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;

@Mixin(Screen.class)
public abstract class MixinScreenCopyFeedback {
	@Inject(method = "handleComponentClicked", at = @At("RETURN"))
	private void recordSuccessfulFtfCopy(Style style, CallbackInfoReturnable<Boolean> callback) {
		if (callback.getReturnValue()) {
			ClientCopyFeedback.recordSuccessfulClick(style);
		}
	}
}
