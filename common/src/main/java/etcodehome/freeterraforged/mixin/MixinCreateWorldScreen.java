package etcodehome.freeterraforged.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.DefaultPresetArchive;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresetDataPacks;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenDimensionInputs;

@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreen {
	@Shadow
	@Final
	WorldCreationUiState uiState;

	@Shadow
	private boolean recreated;

	@Shadow
	private Pair<Path, PackRepository> getDataPackSelectionSettings(WorldDataConfiguration configuration) {
		throw new AssertionError();
	}

	@Shadow
	private void tryApplyNewDataPacks(
		PackRepository repository,
		boolean recreate,
		Consumer<WorldDataConfiguration> callback
	) {
		throw new AssertionError();
	}

	@Unique
	private boolean freeterraforged$syncingPresetPack;

	@Unique
	private boolean freeterraforged$pendingPresetPackSync;

	// Vanilla maps every NoiseBasedChunkGenerator to NORMAL, so recognize FTF's generator explicitly.
	@Redirect(
		method = "createFromExisting",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/presets/WorldPresets;fromSettings(Lnet/minecraft/world/level/levelgen/WorldDimensions;)Ljava/util/Optional;"),
		require = 1,
		allow = 1
	)
	private static Optional<ResourceKey<WorldPreset>> freeterraforged$restoreFtfWorldType(WorldDimensions dimensions) {
		boolean ftfWorld = dimensions.get(LevelStem.OVERWORLD)
			.map(LevelStem::generator)
			.filter(TerraForgedChunkGenerator.class::isInstance)
			.isPresent();
		return ftfWorld
			? Optional.of(FTFWorldPresets.FREETERRAFORGED)
			: WorldPresets.fromSettings(dimensions);
	}

	@ModifyArg(
		method = "openFresh",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/screens/worldselection/WorldCreationContext;Ljava/util/Optional;Ljava/util/OptionalLong;)V"),
		index = 3,
		require = 1,
		allow = 1
	)
	private static Optional<ResourceKey<WorldPreset>> freeterraforged$selectDefaultWorldType(
		Optional<ResourceKey<WorldPreset>> original
	) {
		return Optional.of(FTFWorldPresets.FREETERRAFORGED);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void freeterraforged$watchSelectedWorldType(
		Minecraft minecraft,
		Screen lastScreen,
		WorldCreationContext context,
		Optional<ResourceKey<WorldPreset>> preset,
		OptionalLong seed,
		CallbackInfo callback
	) {
		this.uiState.addListener(this::freeterraforged$syncPresetPackWithWorldType);
	}

	@Inject(method = "openFresh", at = @At("TAIL"))
	private static void freeterraforged$applySavedDefaultPreset(
		Minecraft minecraft,
		Screen lastScreen,
		CallbackInfo callback
	) {
		if (minecraft.screen instanceof CreateWorldScreen screen) {
			((MixinCreateWorldScreen) (Object) screen).freeterraforged$initializeDefaultPreset();
		}
	}

	@Unique
	private void freeterraforged$initializeDefaultPreset() {
		this.freeterraforged$syncPresetPackWithWorldType(this.uiState);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void freeterraforged$syncPresetPackAfterRecreate(CallbackInfo callback) {
		if (this.recreated) {
			this.freeterraforged$pendingPresetPackSync = false;
			this.freeterraforged$syncPresetPackWithWorldType(this.uiState);
		}
	}

	@Unique
	private void freeterraforged$syncPresetPackWithWorldType(WorldCreationUiState state) {
		if (this.recreated && Minecraft.getInstance().screen != (Object) this) {
			this.freeterraforged$pendingPresetPackSync = true;
			return;
		}
		if (this.freeterraforged$syncingPresetPack) {
			return;
		}

		var selectedType = state.getWorldType();
		Holder<WorldPreset> selectedPreset = selectedType == null ? null : selectedType.preset();
		boolean ftfSelected = selectedPreset != null && selectedPreset.unwrapKey()
			.filter(FTFWorldPresets.FREETERRAFORGED::equals)
			.isPresent();

		WorldDataConfiguration configuration = state.getSettings().dataConfiguration();
		List<String> enabledPacks = configuration.dataPacks().getEnabled();
		String customPackId = FTFWorldPresets.GENERATED_PRESET_PACK_ID;
		boolean customPackEnabled = enabledPacks.contains(customPackId);

		Pair<Path, PackRepository> selection = this.getDataPackSelectionSettings(configuration);
		if (selection == null) {
			FTFCommon.LOGGER.error("Could not prepare a data pack repository while switching the world type");
			return;
		}

		Path presetZipPath = selection.getFirst().resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
		boolean zipExists = Files.isRegularFile(presetZipPath);

		boolean needsSync = ftfSelected
			? (!zipExists || !customPackEnabled)
			: (zipExists || customPackEnabled);

		if (!needsSync) {
			return;
		}

		PackRepository repository = selection.getSecond();
		repository.reload();
		repository.setSelected(enabledPacks);

		if (ftfSelected) {
			if (!zipExists) {
				try {
					Preset defaultPreset = DefaultPresetArchive.getActiveDefaultPreset();
					String presetName = DefaultPresetArchive.getActiveDefaultPresetName();
					RegistryAccess registries = state.getSettings().worldgenLoadContext();
					FTFWorldPresetDataPacks.exportPresetAsDatapack(presetZipPath, defaultPreset, presetName, registries);
				} catch (IOException exception) {
					FTFCommon.LOGGER.error("Could not export the FreeTerraForged preset datapack to {}", presetZipPath, exception);
					return;
				}
				repository.reload();
				repository.setSelected(enabledPacks);
			}
			if (!repository.getSelectedIds().contains(customPackId)) {
				if (!repository.addPack(customPackId)) {
					FTFCommon.LOGGER.warn("The FreeTerraForged preset pack is unavailable");
				}
			}
		} else {
			repository.removePack(customPackId);
		}

		this.freeterraforged$syncingPresetPack = true;
		try {
			this.tryApplyNewDataPacks(repository, false, ignored -> this.freeterraforged$syncingPresetPack = false);
		} catch (Throwable throwable) {
			this.freeterraforged$syncingPresetPack = false;
			throw throwable;
		}
	}

	// The unique encode call is in a reload lambda whose compiler-generated name is unstable.
	@ModifyArg(
		method = "*",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/WorldGenSettings;encode(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/world/level/levelgen/WorldDimensions;)Lcom/mojang/serialization/DataResult;"),
		index = 2,
		require = 1,
		allow = 1
	)
	private WorldDimensions freeterraforged$encodeSelectedInputsForReload(WorldDimensions dimensions) {
		return WorldgenDimensionInputs.forReload(dimensions);
	}

	@Inject(method = "onCreate", at = @At("HEAD"))
	private void freeterraforged$cleanupPresetPackIfNonFtfWorld(CallbackInfo callback) {
		var selectedType = this.uiState.getWorldType();
		Holder<WorldPreset> selectedPreset = selectedType == null ? null : selectedType.preset();
		boolean ftfSelected = selectedPreset != null && selectedPreset.unwrapKey()
			.filter(FTFWorldPresets.FREETERRAFORGED::equals)
			.isPresent();
		if (!ftfSelected) {
			Pair<Path, PackRepository> selection = this.getDataPackSelectionSettings(this.uiState.getSettings().dataConfiguration());
			if (selection != null) {
				Path presetZipPath = selection.getFirst().resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
				try {
					Files.deleteIfExists(presetZipPath);
				} catch (IOException exception) {
					FTFCommon.LOGGER.warn("Could not delete unused FreeTerraForged preset datapack before non-FTF world creation", exception);
				}
			}
		}
	}
}
