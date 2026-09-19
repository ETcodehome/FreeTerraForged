package etcodehome.freeterraforged.data.worldgen;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import etcodehome.freeterraforged.data.worldgen.preset.PresetConfiguredFeatures;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.tags.FTFBlockTagsProvider;
import etcodehome.freeterraforged.data.worldgen.tags.FTFDensityFunctionTagsProvider;
import etcodehome.freeterraforged.platform.DataGenUtil;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import etcodehome.freeterraforged.world.worldgen.feature.FTFFeatures;
import etcodehome.freeterraforged.world.worldgen.structure.rule.StructureRule;
import net.minecraft.core.Cloner;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.DataGenerator.PackGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.data.worldgen.features.FeatureUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.RegistryDataLoader;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.world.worldgen.feature.SwampSurfaceFeature;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public class Datapacks {

	public static DataGenerator makeMudSwamps(RegistryAccess registryAccess, Path dataGenPath, Path dataGenOutputPath) {
		DataGenerator dataGenerator = new DataGenerator(dataGenPath, SharedConstants.getCurrentVersion(), true);
		PackGenerator packGenerator = dataGenerator.new PackGenerator(true, "Mud Swamps", new PackOutput(dataGenOutputPath));
		CompletableFuture<HolderLookup.Provider> lookup = CompletableFuture.supplyAsync(() -> {
			RegistrySetBuilder builder = new RegistrySetBuilder();
			builder.add(Registries.CONFIGURED_FEATURE, (ctx) -> {
				FeatureUtils.register(ctx, PresetConfiguredFeatures.SWAMP_SURFACE, FTFFeatures.SWAMP_SURFACE, new SwampSurfaceFeature.Config(Blocks.CLAY.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.MUD.defaultBlockState()));
			});
			Cloner.Factory factory = new Cloner.Factory();
			RegistryDataLoader.WORLDGEN_REGISTRIES.forEach(registryData -> registryData.runWithArguments(factory::addCodec));
			factory.addCodec(FTFRegistries.NOISE, Noise.DIRECT_CODEC);
			factory.addCodec(FTFRegistries.BIOME_MODIFIER, BiomeModifier.DIRECT_CODEC);
			factory.addCodec(FTFRegistries.STRUCTURE_RULE, StructureRule.DIRECT_CODEC);
			factory.addCodec(FTFRegistries.PRESET, Preset.DIRECT_CODEC);
			return builder.buildPatch(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), registryAccess,factory).patches();
		});
		packGenerator.addProvider((output) -> {
			return DataGenUtil.createRegistryProvider(output, lookup);
		});
		packGenerator.addProvider((output) -> {
			return PackMetadataGenerator.forFeaturePack(output, Component.translatable(FTFTranslationKeys.MUD_SWAMPS_METADATA_DESCRIPTION));
		});
		return dataGenerator;
	}

	public static DataGenerator makePreset(Preset preset, HolderLookup.Provider registries, Path dataGenPath, Path dataGenOutputPath, String presetName) {
		DataGenerator dataGenerator = new DataGenerator(dataGenPath, SharedConstants.getCurrentVersion(), true);
		PackGenerator packGenerator = dataGenerator.new PackGenerator(true, presetName, new PackOutput(dataGenOutputPath));
		CompletableFuture<HolderLookup.Provider> lookup = CompletableFuture.supplyAsync(() -> preset.buildPatch(registries));
		
		packGenerator.addProvider((output) -> {
			return DataGenUtil.createRegistryProvider(output, lookup);
		});
		packGenerator.addProvider((output) -> {
			return new FTFDensityFunctionTagsProvider(output, lookup);
		});
		packGenerator.addProvider((output) -> {
			return new FTFBlockTagsProvider(preset, output, lookup);
		});
		packGenerator.addProvider((output) -> {
			return PackMetadataGenerator.forFeaturePack(output, Component.translatable(FTFTranslationKeys.PRESET_METADATA_DESCRIPTION));
		});
		return dataGenerator;
	}
}
