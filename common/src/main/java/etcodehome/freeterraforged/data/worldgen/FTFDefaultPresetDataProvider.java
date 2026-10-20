package etcodehome.freeterraforged.data.worldgen;

import java.util.concurrent.CompletableFuture;

import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.data.worldgen.tags.FTFBlockTagsProvider;
import etcodehome.freeterraforged.data.worldgen.tags.FTFDensityFunctionTagsProvider;
import etcodehome.freeterraforged.platform.DataGenUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.network.chat.Component;

public final class FTFDefaultPresetDataProvider implements DataProvider {
	private final PackOutput output;
	private final CompletableFuture<HolderLookup.Provider> registries;

	public FTFDefaultPresetDataProvider(
		PackOutput output,
		CompletableFuture<HolderLookup.Provider> registries
	) {
		this.output = output;
		this.registries = registries;
	}

	@Override
	public CompletableFuture<?> run(CachedOutput cache) {
		Preset preset = Presets.makeFTFDefault();
		CompletableFuture<RegistrySetBuilder.PatchedRegistries> patchedRegistries = this.registries.thenApply(
			preset::buildPatchedRegistries
		);
		CompletableFuture<HolderLookup.Provider> patchedLookups = patchedRegistries.thenApply(
			RegistrySetBuilder.PatchedRegistries::patches
		);

		CompletableFuture<Void> generated = CompletableFuture.allOf(
			DataGenUtil.createRegistryProvider(this.output, patchedRegistries).run(cache),
			new FTFDensityFunctionTagsProvider(this.output, patchedLookups).run(cache),
			new FTFBlockTagsProvider(preset, this.output, patchedLookups).run(cache),
			PackMetadataGenerator.forFeaturePack(
				this.output,
				Component.translatable(FTFTranslationKeys.DEFAULT_PRESET_PACK_DESCRIPTION)
			).run(cache)
		);
		if (Boolean.getBoolean("freeterraforged.datagen.exit")) {
			// The Fabric server launcher leaves non-daemon executors alive after cancellable datagen returns.
			generated.whenComplete((ignored, failure) -> System.exit(failure == null ? 0 : 1));
		}
		return generated;
	}

	@Override
	public String getName() {
		return "FreeTerraForged default preset data pack";
	}
}
