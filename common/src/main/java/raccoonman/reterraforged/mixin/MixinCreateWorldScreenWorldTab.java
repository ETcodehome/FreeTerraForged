package raccoonman.reterraforged.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class MixinCreateWorldScreenWorldTab {
	@Shadow
	@Final
	private EditBox seedEdit;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void rtf$syncSeedEditWithUiState(CreateWorldScreen screen, CallbackInfo callbackInfo) {
		screen.getUiState().addListener((uiState) -> {
			String seed = uiState.getSeed();
			if(seed == null) {
				seed = "";
			}
			if(!seed.equals(this.seedEdit.getValue())) {
				this.seedEdit.setValue(seed);
			}
		});
	}
}
