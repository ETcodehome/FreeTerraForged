package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.terrablender.TBCompat;
import raccoonman.reterraforged.world.worldgen.terrablender.TBClimateSampler;
import raccoonman.reterraforged.world.worldgen.terrablender.TerraBlenderParameterList;
import terrablender.util.LevelUtils;

/**
 * Reconstructs the active Overworld biome-selection stack for preset previews.
 * Queries deliberately use the positional biome-source path so companion-mod
 * replacements and sub-biomes remain authoritative.
 */
public final class BiomePreviewResolver {
	private final Climate.Sampler sampler;
	private final SurfaceBiomeFilter<Holder<Biome>> surfaceFilter;
	private final TerraBlenderParameterList<Holder<Biome>> terraBlenderParameters;
	private final Climate.ParameterList<Holder<Biome>> baseParameters;
	private final Holder<Biome> finalFallback;
	private final BiomePreviewIntegration.Context integrationContext;
	private final AtomicBoolean positionalSelectionEnabled = new AtomicBoolean(true);
	private final AtomicReference<String> warning = new AtomicReference<>();

	private BiomePreviewResolver(
		Climate.Sampler sampler,
		SurfaceBiomeFilter<Holder<Biome>> surfaceFilter,
		TerraBlenderParameterList<Holder<Biome>> terraBlenderParameters,
		Climate.ParameterList<Holder<Biome>> baseParameters,
		Holder<Biome> finalFallback,
		BiomePreviewIntegration.Context integrationContext
	) {
		this.sampler = sampler;
		this.surfaceFilter = surfaceFilter;
		this.terraBlenderParameters = terraBlenderParameters;
		this.baseParameters = baseParameters;
		this.finalFallback = finalFallback;
		this.integrationContext = integrationContext;
	}

	public static BiomePreviewResolver create(
		RegistryAccess registries,
		HolderLookup.Provider provider,
		Holder<DimensionType> dimensionType,
		ChunkGenerator activeGenerator,
		Preset preset,
		GeneratorContext generatorContext,
		long seed
	) {
		BiomeSource biomeSource = copyBiomeSource(activeGenerator.getBiomeSource());
		Holder<NoiseGeneratorSettings> noiseSettings = provider.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		NoiseBasedChunkGenerator previewGenerator = new NoiseBasedChunkGenerator(biomeSource, noiseSettings);
		LevelStem previewStem = new LevelStem(dimensionType, previewGenerator);

		if (TBCompat.isEnabled()) {
			initializeTerraBlender(registries, previewStem, previewGenerator, biomeSource, preset, seed);
		}

		Climate.Sampler sampler = surfaceClimateSampler(noiseSettings.value(), preset, generatorContext, seed);
		TerraBlenderParameterList<Holder<Biome>> terraBlenderParameters = terraBlenderParameters(biomeSource);
		List<Holder<Biome>> additionalUndergroundCandidates = new ArrayList<>();
		if (terraBlenderParameters != null) {
			TerraBlenderParameterList.CompositionDiagnostics<Holder<Biome>> diagnostics =
				terraBlenderParameters.reterraforged$getCompositionDiagnostics();
			additionalUndergroundCandidates.addAll(diagnostics.shallowCandidates());
			additionalUndergroundCandidates.addAll(diagnostics.deepCandidates());
		}
		Holder<Biome> plains = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		SurfaceBiomeFilter<Holder<Biome>> surfaceFilter = surfaceFilter(
			biomeSource, additionalUndergroundCandidates, plains
		);
		Climate.ParameterList<Holder<Biome>> baseParameters = parameters(biomeSource);
		BiomePreviewIntegration.Context integrationContext = new BiomePreviewIntegration.Context(
			seed, registries, provider, biomeSource, previewGenerator, previewStem, preset, generatorContext
		);
		return new BiomePreviewResolver(
			sampler,
			surfaceFilter,
			terraBlenderParameters,
			baseParameters,
			plains,
			integrationContext
		);
	}

	private static Climate.Sampler surfaceClimateSampler(
		NoiseGeneratorSettings noiseSettings,
		Preset preset,
		GeneratorContext generatorContext,
		long seed
	) {
		Climate.Sampler sampler = new Climate.Sampler(
			cell(generatorContext, CellSampler.Field.TEMPERATURE),
			cell(generatorContext, CellSampler.Field.MOISTURE),
			cell(generatorContext, CellSampler.Field.CONTINENT),
			cell(generatorContext, CellSampler.Field.EROSION),
			DensityFunctions.constant(0.0D),
			cell(generatorContext, CellSampler.Field.WEIRDNESS),
			noiseSettings.spawnTarget()
		);
		((RTFClimateSampler) (Object) sampler).setUndergroundBiomeBandingPreset(preset, seed);
		if (TBCompat.isEnabled() && (Object) sampler instanceof TBClimateSampler terraBlenderSampler) {
			terraBlenderSampler.setUniqueness(cell(generatorContext, CellSampler.Field.BIOME_REGION));
		}
		return sampler;
	}

	private static DensityFunction cell(GeneratorContext context, CellSampler.Field field) {
		return new CellSampler(() -> context.lookup, field);
	}

	private static void initializeTerraBlender(
		RegistryAccess registries,
		LevelStem previewStem,
		NoiseBasedChunkGenerator previewGenerator,
		BiomeSource biomeSource,
		Preset preset,
		long seed
	) {
		if (biomeSource instanceof MultiNoiseBiomeSource
			&& (Object) biomeSource instanceof RTFMultiNoiseBiomeSource source
			&& (Object) source.reterraforged$getParameters() instanceof TerraBlenderParameterList<?> parameters) {
			parameters.reterraforged$preparePreview(preset, seed);
		}
		LevelUtils.initializeBiomes(
			registries,
			previewStem.type(),
			LevelStem.OVERWORLD,
			previewGenerator,
			seed
		);
	}

	public Holder<Biome> resolveQuart(int quartX, int quartY, int quartZ) {
		Holder<Biome> selected;
		if (this.positionalSelectionEnabled.get()) {
			try {
				selected = this.integrationContext.generator().getBiomeSource()
					.getNoiseBiome(quartX, quartY, quartZ, this.sampler);
				if (selected == null) {
					throw new IllegalStateException("Biome source returned null during preview selection");
				}
			} catch (RuntimeException | LinkageError error) {
				this.disablePositionalSelection(error);
				selected = this.resolveFallback(quartX, quartY, quartZ);
			}
		} else {
			selected = this.resolveFallback(quartX, quartY, quartZ);
		}
		if (!this.surfaceFilter.isUnderground(selected)) {
			return selected;
		}
		Climate.TargetPoint target = this.sampler.sample(quartX, quartY, quartZ);
		if (this.terraBlenderParameters != null) {
			Holder<Biome> regionalSurface = this.terraBlenderParameters
				.reterraforged$inspectSelection(target, quartX, quartY, quartZ)
				.original();
			if (regionalSurface != null && !this.surfaceFilter.isUnderground(regionalSurface)) {
				return regionalSurface;
			}
		}
		return this.surfaceFilter.resolve(target, selected);
	}

	public BiomePreviewIntegration.Session openIntegrationSession() {
		return BiomePreviewIntegrations.open(this.integrationContext, this::disablePositionalSelection);
	}

	public String warning() {
		return this.warning.get();
	}

	private Holder<Biome> resolveFallback(int quartX, int quartY, int quartZ) {
		Climate.TargetPoint target = this.sampler.sample(quartX, quartY, quartZ);
		if (this.terraBlenderParameters != null) {
			Holder<Biome> selected = this.terraBlenderParameters
				.reterraforged$inspectSelection(target, quartX, quartY, quartZ)
				.banded();
			if (selected != null) {
				return selected;
			}
		}
		if (this.baseParameters != null) {
			Holder<Biome> selected = this.baseParameters.findValue(target);
			if (selected != null) {
				return selected;
			}
		}
		return this.finalFallback;
	}

	private void disablePositionalSelection(Throwable error) {
		this.positionalSelectionEnabled.set(false);
		String message = "Runtime biome replacements unavailable; showing composed biome registrations";
		if (this.warning.compareAndSet(null, message)) {
			RTFCommon.LOGGER.error(
				"A biome mod failed during positional preview selection; falling back to the composed parameter tree",
				error
			);
		}
	}

	public boolean isUnderground(Holder<Biome> biome) {
		return this.surfaceFilter.isUnderground(biome);
	}

	private static SurfaceBiomeFilter<Holder<Biome>> surfaceFilter(
		BiomeSource biomeSource,
		List<Holder<Biome>> additionalUndergroundCandidates,
		Holder<Biome> finalFallback
	) {
		if (biomeSource instanceof MultiNoiseBiomeSource
			&& (Object) biomeSource instanceof RTFMultiNoiseBiomeSource multiNoise) {
			return SurfaceBiomeFilter.create(
				multiNoise.reterraforged$getParameters().values(),
				(point, biome) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(biome)),
				UndergroundBiomeTags::isCave,
				additionalUndergroundCandidates,
				finalFallback
			);
		}
		return SurfaceBiomeFilter.create(
			List.of(),
			(point, biome) -> UndergroundBiomeBanding.CandidateRole.UNKNOWN,
			UndergroundBiomeTags::isCave,
			additionalUndergroundCandidates,
			finalFallback
		);
	}

	@SuppressWarnings("unchecked")
	private static TerraBlenderParameterList<Holder<Biome>> terraBlenderParameters(BiomeSource biomeSource) {
		if (biomeSource instanceof MultiNoiseBiomeSource
			&& (Object) ((RTFMultiNoiseBiomeSource) biomeSource).reterraforged$getParameters()
				instanceof TerraBlenderParameterList<?> parameters) {
			return (TerraBlenderParameterList<Holder<Biome>>) parameters;
		}
		return null;
	}

	private static Climate.ParameterList<Holder<Biome>> parameters(BiomeSource biomeSource) {
		if (biomeSource instanceof MultiNoiseBiomeSource
			&& (Object) biomeSource instanceof RTFMultiNoiseBiomeSource multiNoise) {
			return multiNoise.reterraforged$getParameters();
		}
		return null;
	}

	private static BiomeSource copyBiomeSource(BiomeSource source) {
		if (source instanceof MultiNoiseBiomeSource
			&& (Object) source instanceof RTFMultiNoiseBiomeSource multiNoise) {
			List<com.mojang.datafixers.util.Pair<Climate.ParameterPoint, Holder<Biome>>> values =
				List.copyOf(multiNoise.reterraforged$getParameters().values());
			return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(values));
		}
		return source;
	}
}
