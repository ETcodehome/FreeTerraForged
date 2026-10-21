package etcodehome.freeterraforged.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.tabs.Tab;
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

	@Unique
	private boolean ftf$recreatingFtfWorld;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ftf$syncSeedEditWithUiState(CreateWorldScreen screen, CallbackInfo callbackInfo) {
		this.ftf$recreatingFtfWorld = ((CreateWorldScreenAccessor) (Object) screen).freeterraforged$isRecreated()
			&& this.ftf$isFtfWorldType(screen.getUiState().getWorldType());
		this.ftf$syncFtfWorldControls(screen, screen.getUiState());
		screen.getUiState().addListener((uiState) -> {
			this.ftf$syncFtfWorldControls(screen, uiState);
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
	private void ftf$syncFtfWorldControls(CreateWorldScreen screen, WorldCreationUiState uiState) {
		boolean ftfSelected = this.ftf$isFtfWorldType(uiState.getWorldType());
		if (ftfSelected) {
			this.customizeTypeButton.active = !((CreateWorldScreenAccessor) (Object) screen).freeterraforged$isRecreated();
		}
		// Changing world types replaces selected dimensions, so keep a recreated FTF preset intact.
		if (this.ftf$recreatingFtfWorld) {
			((Tab) (Object) this).visitChildren(widget -> {
				if (widget instanceof CycleButton<?> cycleButton
					&& cycleButton.getValue() instanceof WorldCreationUiState.WorldTypeEntry) {
					cycleButton.active = false;
				}
			});
		}
	}

	@Unique
	private boolean ftf$isFtfWorldType(WorldCreationUiState.WorldTypeEntry worldType) {
		return worldType != null
			&& worldType.preset() != null
			&& worldType.preset().unwrapKey().filter(FTFWorldPresets.FREETERRAFORGED::equals).isPresent();
	}
}
