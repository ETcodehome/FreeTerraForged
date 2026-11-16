package etcodehome.freeterraforged.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresetDataPacks;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.runtime.PreServerWorldgenContext;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPreServerFinalizer;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenDimensionInputs;

@Mixin(WorldCreationUiState.class)
public class MixinWorldCreationUiState {
	@Shadow
	private WorldCreationContext settings;

	@Shadow
	private WorldCreationUiState.WorldTypeEntry worldType;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void freeterraforged$finalizeInitialDimensions(CallbackInfo callback) {
		this.freeterraforged$ensureSelectedGeneratorRoot();
		this.freeterraforged$finalizePreServerGraph();
	}

	@Inject(method = "setSettings", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;onChanged()V"))
	private void freeterraforged$selectOwnedGeneratorRootAfterSettings(WorldCreationContext context, CallbackInfo callback) {
		this.freeterraforged$ensureSelectedGeneratorRoot();
		this.freeterraforged$finalizePreServerGraph();
	}

	@Inject(method = "updateDimensions", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;onChanged()V"))
	private void freeterraforged$finalizeUpdatedDimensions(
		WorldCreationContext.DimensionsUpdater updater,
		CallbackInfo callback
	) {
		this.freeterraforged$finalizePreServerGraph();
	}

	@Inject(method = "tryUpdateDataConfiguration", at = @At("HEAD"), cancellable = true)
	private void freeterraforged$forceReloadWhenPresetPackPresent(
		WorldDataConfiguration configuration,
		CallbackInfoReturnable<Boolean> callback
	) {
		if (FTFWorldPresetDataPacks.requiresFullDataPackReload(configuration)
			|| (this.settings != null && FTFWorldPresetDataPacks.requiresFullDataPackReload(this.settings.dataConfiguration()))) {
			callback.setReturnValue(false);
		}
	}

	// The constructor can select an FTF preset without updating the context's initial dimensions.
	private void freeterraforged$ensureSelectedGeneratorRoot() {
		Holder<WorldPreset> preset = this.worldType.preset();
		if (preset != null
			&& preset.value().overworld().isPresent()
			&& preset.value().overworld().orElseThrow().generator() instanceof TerraForgedChunkGenerator
			&& !(this.settings.selectedDimensions().overworld() instanceof TerraForgedChunkGenerator)) {
			this.settings = this.settings.withDimensions(
				(registries, dimensions) -> preset.value().createWorldDimensions()
			);
		}
	}

	private void freeterraforged$finalizePreServerGraph() {
		var acquired = WorldgenDimensionInputs.acquire(
			this.settings.selectedDimensions(), this.settings.datapackDimensions()
		);
		if (acquired != this.settings.selectedDimensions()) {
			this.settings = this.settings.withDimensions((registries, dimensions) -> acquired);
		}
		WorldgenPreServerFinalizer.finalize(new PreServerWorldgenContext(
			this.settings.worldgenLoadContext(),
			this.settings.selectedDimensions(),
			this.settings.options().seed()
		));
	}
}
