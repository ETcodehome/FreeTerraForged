package etcodehome.freeterraforged.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.runtime.PreServerWorldgenContext;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPreServerFinalizer;

@Mixin(WorldCreationUiState.class)
public class MixinWorldCreationUiState {
	@Shadow
	private WorldCreationContext settings;

	@Shadow
	private WorldCreationUiState.WorldTypeEntry worldType;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void freeterraforged$finalizeInitialDimensions(CallbackInfo callback) {
		this.freeterraforged$finalizePreServerGraph();
	}

	@Inject(method = "setSettings", at = @At("TAIL"))
	private void freeterraforged$selectOwnedGeneratorRoot(WorldCreationContext context, CallbackInfo callback) {
		Holder<WorldPreset> preset = this.worldType.preset();
		if (preset != null
			&& preset.value().overworld().isPresent()
			&& preset.value().overworld().orElseThrow().generator() instanceof TerraForgedChunkGenerator
			&& !(this.settings.selectedDimensions().overworld() instanceof TerraForgedChunkGenerator)) {
			this.settings = this.settings.withDimensions(
				(registries, dimensions) -> preset.value().createWorldDimensions()
			);
		}
		this.freeterraforged$finalizePreServerGraph();
	}

	@Inject(method = "updateDimensions", at = @At("TAIL"))
	private void freeterraforged$finalizeUpdatedDimensions(
		WorldCreationContext.DimensionsUpdater updater,
		CallbackInfo callback
	) {
		this.freeterraforged$finalizePreServerGraph();
	}

	private void freeterraforged$finalizePreServerGraph() {
		WorldgenPreServerFinalizer.finalize(new PreServerWorldgenContext(
			this.settings.worldgenLoadContext(),
			this.settings.selectedDimensions(),
			this.settings.options().seed()
		));
	}
}
