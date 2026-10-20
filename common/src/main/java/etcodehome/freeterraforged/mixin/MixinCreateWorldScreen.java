package etcodehome.freeterraforged.mixin;

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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresetDataPacks;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenDimensionInputs;

@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreen {
	@Shadow
	@Final
	WorldCreationUiState uiState;

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
	private boolean freeterraforged$customPresetWasEnabled;

	@ModifyArg(
		method = "openFresh",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;createDefaultLoadConfig(Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/world/level/WorldDataConfiguration;)Lnet/minecraft/server/WorldLoader$InitConfig;"),
		index = 1,
		require = 1,
		allow = 1
	)
	private static WorldDataConfiguration freeterraforged$enableDefaultPresetPack(
		WorldDataConfiguration configuration
	) {
		return FTFWorldPresetDataPacks.setDefaultPresetEnabled(configuration, true);
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

	@Unique
	private void freeterraforged$syncPresetPackWithWorldType(WorldCreationUiState state) {
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
		String defaultPackId = FTFWorldPresets.defaultPresetPackId();
		String customPackId = FTFWorldPresets.GENERATED_PRESET_PACK_ID;
		boolean defaultPackEnabled = enabledPacks.contains(defaultPackId);
		boolean customPackEnabled = enabledPacks.contains(customPackId);

		if (customPackEnabled) {
			this.freeterraforged$customPresetWasEnabled = true;
		}

		boolean shouldEnableCustomPack = ftfSelected && this.freeterraforged$customPresetWasEnabled;
		boolean needsSync = ftfSelected
			? !defaultPackEnabled || (shouldEnableCustomPack && !customPackEnabled)
			: defaultPackEnabled || customPackEnabled;
		if (!needsSync) {
			return;
		}

		Pair<Path, PackRepository> selection = this.getDataPackSelectionSettings(configuration);
		if (selection == null) {
			FTFCommon.LOGGER.error("Could not prepare a data pack repository while switching the world type");
			return;
		}

		PackRepository repository = selection.getSecond();
		repository.reload();
		repository.setSelected(enabledPacks);
		if (ftfSelected) {
			if (!repository.getSelectedIds().contains(defaultPackId) && !repository.addPack(defaultPackId)) {
				FTFCommon.LOGGER.error("The built-in FreeTerraForged default preset pack is unavailable");
				return;
			}
			if (shouldEnableCustomPack
				&& !repository.getSelectedIds().contains(customPackId)
				&& !repository.addPack(customPackId)) {
				FTFCommon.LOGGER.warn("The previously selected FreeTerraForged preset pack is unavailable");
			}
		} else {
			repository.removePack(defaultPackId);
			repository.removePack(customPackId);
		}

		this.freeterraforged$syncingPresetPack = true;
		this.tryApplyNewDataPacks(repository, false, ignored -> this.freeterraforged$syncingPresetPack = false);
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
}
