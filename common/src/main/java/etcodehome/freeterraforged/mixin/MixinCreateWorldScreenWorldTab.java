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

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ftf$syncSeedEditWithUiState(CreateWorldScreen screen, CallbackInfo callbackInfo) {
		this.ftf$syncCustomizeButton(screen, screen.getUiState());
		screen.getUiState().addListener((uiState) -> {
			this.ftf$syncCustomizeButton(screen, uiState);
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
	private void ftf$syncCustomizeButton(CreateWorldScreen screen, WorldCreationUiState uiState) {
		WorldCreationUiState.WorldTypeEntry worldType = uiState.getWorldType();
		boolean ftfSelected = worldType != null
			&& worldType.preset() != null
			&& worldType.preset().unwrapKey().filter(FTFWorldPresets.FREETERRAFORGED::equals).isPresent();
		if (ftfSelected) {
			this.customizeTypeButton.active = !((CreateWorldScreenAccessor) (Object) screen).freeterraforged$isRecreated();
		}
	}
}
