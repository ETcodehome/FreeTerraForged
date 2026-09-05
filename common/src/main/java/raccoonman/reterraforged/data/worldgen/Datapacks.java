package raccoonman.reterraforged.data.worldgen;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

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
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.data.worldgen.preset.PresetConfiguredFeatures;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.tags.RTFBlockTagsProvider;
import raccoonman.reterraforged.data.worldgen.tags.RTFDensityFunctionTagsProvider;
import raccoonman.reterraforged.platform.DataGenUtil;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.feature.RTFFeatures;
import raccoonman.reterraforged.world.worldgen.feature.SwampSurfaceFeature;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifier;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.structure.rule.StructureRule;

public class Datapacks {

	public static DataGenerator makeMudSwamps(RegistryAccess registryAccess, Path dataGenPath, Path dataGenOutputPath) {
		DataGenerator dataGenerator = new DataGenerator(dataGenPath, SharedConstants.getCurrentVersion(), true);
		PackGenerator packGenerator = dataGenerator.new PackGenerator(true, "Mud Swamps", new PackOutput(dataGenOutputPath));
		CompletableFuture<HolderLookup.Provider> lookup = CompletableFuture.supplyAsync(() -> {
			RegistrySetBuilder builder = new RegistrySetBuilder();
			builder.add(Registries.CONFIGURED_FEATURE, (ctx) -> {
				FeatureUtils.register(ctx, PresetConfiguredFeatures.SWAMP_SURFACE, RTFFeatures.SWAMP_SURFACE, new SwampSurfaceFeature.Config(Blocks.CLAY.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.MUD.defaultBlockState()));
			});
			Cloner.Factory factory = new Cloner.Factory();
			RegistryDataLoader.WORLDGEN_REGISTRIES.forEach(registryData -> registryData.runWithArguments(factory::addCodec));
			factory.addCodec(RTFRegistries.NOISE, Noise.DIRECT_CODEC);
			factory.addCodec(RTFRegistries.BIOME_MODIFIER, BiomeModifier.DIRECT_CODEC);
			factory.addCodec(RTFRegistries.STRUCTURE_RULE, StructureRule.DIRECT_CODEC);
			factory.addCodec(RTFRegistries.PRESET, Preset.DIRECT_CODEC);
			return builder.buildPatch(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), registryAccess,factory).patches();
		});
		packGenerator.addProvider((output) -> {
			return DataGenUtil.createRegistryProvider(output, lookup);
		});
		packGenerator.addProvider((output) -> {
			return PackMetadataGenerator.forFeaturePack(output, Component.translatable(RTFTranslationKeys.MUD_SWAMPS_METADATA_DESCRIPTION));
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
			return new RTFDensityFunctionTagsProvider(output, lookup);
		});
		packGenerator.addProvider((output) -> {
			return new RTFBlockTagsProvider(preset, output, lookup);
		});
		packGenerator.addProvider((output) -> {
			return PackMetadataGenerator.forFeaturePack(output, Component.translatable(RTFTranslationKeys.PRESET_METADATA_DESCRIPTION));
		});
		return dataGenerator;
	}
}
