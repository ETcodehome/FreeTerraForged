package etcodehome.freeterraforged.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class MixinCreateWorldScreenWorldTab {
	@Shadow
	@Final
	private EditBox seedEdit;

	@Shadow
	@Final
	private Button customizeTypeButton;

	@ModifyArg(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/CycleButton$Builder;create(IIIILnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/components/CycleButton$OnValueChange;)Lnet/minecraft/client/gui/components/CycleButton;"
		),
		index = 2,
		require = 1,
		allow = 1
	)
	private int ftf$widenWorldTypeButton(int width) {
		return Math.max(width, 200);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ftf$syncSeedEditWithUiState(CreateWorldScreen screen, CallbackInfo callbackInfo) {
		this.ftf$syncCustomizeButton(screen.getUiState());
		screen.getUiState().addListener((uiState) -> {
			this.ftf$syncCustomizeButton(uiState);
			String seed = uiState.getSeed();
			if (seed == null) {
				seed = "";
			}
			if (!seed.equals(this.seedEdit.getValue())) {
				this.seedEdit.setValue(seed);
			}
		});
	}

	@Unique
	private void ftf$syncCustomizeButton(WorldCreationUiState uiState) {
		WorldCreationUiState.WorldTypeEntry worldType = uiState.getWorldType();
		this.customizeTypeButton.visible = !uiState.isDebug()
			&& worldType != null
			&& worldType.preset() != null
			&& worldType.preset().unwrapKey().filter(FTFWorldPresets.FREETERRAFORGED::equals).isPresent();
	}
}
