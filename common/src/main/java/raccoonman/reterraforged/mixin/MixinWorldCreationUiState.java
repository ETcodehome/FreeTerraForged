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
import raccoonman.reterraforged.world.worldgen.runtime.PreServerWorldgenContext;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPreServerFinalizer;

/** Keeps selected dimensions aligned when the active datapack replaces the selected preset root. */
@Mixin(WorldCreationUiState.class)
public class MixinWorldCreationUiState {
	@Shadow
	private WorldCreationContext settings;

	@Shadow
	private WorldCreationUiState.WorldTypeEntry worldType;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void reterraforged$finalizeInitialDimensions(CallbackInfo callback) {
		this.reterraforged$finalizePreServerGraph();
	}

	@Inject(method = "setSettings", at = @At("TAIL"))
	private void reterraforged$selectOwnedGeneratorRoot(WorldCreationContext context, CallbackInfo callback) {
		Holder<WorldPreset> preset = this.worldType.preset();
		if (preset != null
			&& preset.value().overworld().isPresent()
			&& preset.value().overworld().orElseThrow().generator() instanceof TerraForgedChunkGenerator
			&& !(this.settings.selectedDimensions().overworld() instanceof TerraForgedChunkGenerator)) {
			this.settings = this.settings.withDimensions(
				(registries, dimensions) -> preset.value().createWorldDimensions()
			);
		}
		this.reterraforged$finalizePreServerGraph();
	}

	@Inject(method = "updateDimensions", at = @At("TAIL"))
	private void reterraforged$finalizeUpdatedDimensions(
		WorldCreationContext.DimensionsUpdater updater,
		CallbackInfo callback
	) {
		this.reterraforged$finalizePreServerGraph();
	}

	private void reterraforged$finalizePreServerGraph() {
		WorldgenPreServerFinalizer.finalize(new PreServerWorldgenContext(
			this.settings.worldgenLoadContext(),
			this.settings.selectedDimensions(),
			this.settings.options().seed()
		));
	}
}
