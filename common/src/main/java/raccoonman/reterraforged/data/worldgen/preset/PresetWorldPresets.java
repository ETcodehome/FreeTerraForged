package raccoonman.reterraforged.data.worldgen.preset;

import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

/** Makes FTF's registered generator root the persisted authority for an exported FTF preset. */
public final class PresetWorldPresets {
	private PresetWorldPresets() {
	}

	public static void bootstrap(Preset preset, BootstrapContext<WorldPreset> context) {
		HolderGetter<DimensionType> dimensionTypes = context.lookup(Registries.DIMENSION_TYPE);
		HolderGetter<NoiseGeneratorSettings> noiseSettings = context.lookup(Registries.NOISE_SETTINGS);
		HolderGetter<MultiNoiseBiomeSourceParameterList> parameterLists = context.lookup(
			Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST
		);
		HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);

		Holder<DimensionType> overworldType = dimensionTypes.getOrThrow(BuiltinDimensionTypes.OVERWORLD);
		Holder<NoiseGeneratorSettings> overworldSettings = noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		Holder.Reference<MultiNoiseBiomeSourceParameterList> overworldParameters = parameterLists.getOrThrow(
			MultiNoiseBiomeSourceParameterLists.OVERWORLD
		);
		LevelStem overworld = new LevelStem(
			overworldType,
			new TerraForgedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(overworldParameters), overworldSettings)
		);

		Holder<DimensionType> netherType = dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER);
		Holder<NoiseGeneratorSettings> netherSettings = noiseSettings.getOrThrow(NoiseGeneratorSettings.NETHER);
		Holder.Reference<MultiNoiseBiomeSourceParameterList> netherParameters = parameterLists.getOrThrow(
			MultiNoiseBiomeSourceParameterLists.NETHER
		);
		LevelStem nether = new LevelStem(
			netherType,
			new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(netherParameters), netherSettings)
		);

		Holder<DimensionType> endType = dimensionTypes.getOrThrow(BuiltinDimensionTypes.END);
		Holder<NoiseGeneratorSettings> endSettings = noiseSettings.getOrThrow(NoiseGeneratorSettings.END);
		LevelStem end = new LevelStem(endType, new NoiseBasedChunkGenerator(TheEndBiomeSource.create(biomes), endSettings));

		context.register(WorldPresets.NORMAL, new WorldPreset(Map.of(
			LevelStem.OVERWORLD, overworld,
			LevelStem.NETHER, nether,
			LevelStem.END, end
		)));
	}
}
