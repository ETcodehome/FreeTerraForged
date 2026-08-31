package raccoonman.reterraforged.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

/** Keeps selected dimensions aligned when the active datapack replaces the selected preset root. */
@Mixin(WorldCreationUiState.class)
public class MixinWorldCreationUiState {
	@Shadow
	private WorldCreationContext settings;

	@Shadow
	private WorldCreationUiState.WorldTypeEntry worldType;

	@Inject(method = "setSettings", at = @At("TAIL"))
	private void reterraforged$selectOwnedGeneratorRoot(WorldCreationContext context, CallbackInfo callback) {
		Holder<WorldPreset> preset = this.worldType.preset();
		if (preset == null
			|| preset.value().overworld().isEmpty()
			|| !(preset.value().overworld().orElseThrow().generator() instanceof TerraForgedChunkGenerator)
			|| this.settings.selectedDimensions().overworld() instanceof TerraForgedChunkGenerator) {
			return;
		}
		this.settings = this.settings.withDimensions(
			(registries, dimensions) -> preset.value().createWorldDimensions()
		);
	}
}
