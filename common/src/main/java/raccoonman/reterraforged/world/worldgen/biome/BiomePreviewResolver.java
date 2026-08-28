package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

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
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.compat.biolith.BiolithCompat;
import raccoonman.reterraforged.compat.biolith.BiolithPreviewContext;
import raccoonman.reterraforged.world.worldgen.terrablender.TBCompat;
import raccoonman.reterraforged.world.worldgen.terrablender.TBClimateSampler;
import raccoonman.reterraforged.world.worldgen.terrablender.TerraBlenderParameterList;
import terrablender.util.LevelUtils;

/**
 * Reconstructs the active Overworld surface biome-selection stack for preset previews.
 */
public final class BiomePreviewResolver {
	private final Climate.Sampler sampler;
	private final TerraBlenderParameterList<Holder<Biome>> terraBlenderParameters;
	private final Climate.ParameterList<Holder<Biome>> baseParameters;
	private final Holder<Biome> finalFallback;
	private final BiomePreviewIntegration.Context integrationContext;
	private final AtomicBoolean positionalSelectionEnabled = new AtomicBoolean(true);
	private final AtomicReference<String> warning = new AtomicReference<>();
	private final Set<String> activeIntegrations = ConcurrentHashMap.newKeySet();

	private BiomePreviewResolver(
			Climate.Sampler sampler,
			TerraBlenderParameterList<Holder<Biome>> terraBlenderParameters,
			Climate.ParameterList<Holder<Biome>> baseParameters,
			Holder<Biome> finalFallback,
			BiomePreviewIntegration.Context integrationContext
	) {
		this.sampler = sampler;
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

		if (BiolithCompat.isEnabled()) {
			BiolithPreviewContext.preInitializeBiomeLookup(registries);
		}

		if (TBCompat.isEnabled()) {
			initializeTerraBlender(registries, dimensionType, previewGenerator, biomeSource, preset, seed);
		}

		LevelStem previewStem = new LevelStem(dimensionType, previewGenerator);
		Climate.Sampler sampler = surfaceClimateSampler(noiseSettings.value(), generatorContext);
		TerraBlenderParameterList<Holder<Biome>> terraBlenderParameters = terraBlenderParameters(biomeSource);
		Climate.ParameterList<Holder<Biome>> baseParameters = parameters(biomeSource);
		Holder<Biome> plains = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);

		BiomePreviewIntegration.Context integrationContext = new BiomePreviewIntegration.Context(
				seed, registries, provider, biomeSource, previewGenerator, previewStem, preset, generatorContext
		);

		return new BiomePreviewResolver(
				sampler,
				terraBlenderParameters,
				baseParameters,
				plains,
				integrationContext
		);
	}

	private static Climate.Sampler surfaceClimateSampler(
			NoiseGeneratorSettings noiseSettings,
			GeneratorContext generatorContext
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
			Holder<DimensionType> dimensionType,
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
				dimensionType,
				LevelStem.OVERWORLD,
				previewGenerator,
				seed
		);
	}

	public Holder<Biome> resolveQuart(int quartX, int quartY, int quartZ) {
		return this.resolveQuart(quartX, quartY, quartZ, this.sampler);
	}

	public Holder<Biome> resolveQuart(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
		if (this.positionalSelectionEnabled.get()) {
			try {
				Holder<Biome> selected = this.integrationContext.generator().getBiomeSource()
						.getNoiseBiome(quartX, quartY, quartZ, sampler);
				if (selected != null) {
					return selected;
				}
			} catch (RuntimeException | LinkageError error) {
				this.disablePositionalSelection(error);
			}
		}
		return this.resolveFallback(quartX, quartY, quartZ, sampler);
	}

	public Climate.Sampler tileClimateSampler(Tile tile, int centerX, int centerZ, int zoom) {
		float originX = centerX - tile.getBlockSize().size() * zoom / 2.0F;
		float originZ = centerZ - tile.getBlockSize().size() * zoom / 2.0F;
		return this.tileClimateSamplerAtOrigin(tile, originX, originZ, zoom);
	}

	public Climate.Sampler tileClimateSamplerAtOrigin(Tile tile, int originX, int originZ, int zoom) {
		return this.tileClimateSamplerAtOrigin(tile, (float) originX, (float) originZ, zoom);
	}

	private Climate.Sampler tileClimateSamplerAtOrigin(Tile tile, float originX, float originZ, int zoom) {
		NoiseBasedChunkGenerator previewGenerator = (NoiseBasedChunkGenerator) this.integrationContext.generator();
		var heightmap = this.integrationContext.generatorContext().lookup.getHeightmap();
		Climate.Sampler sampler = new Climate.Sampler(
				new PreviewTileClimateSampler(tile, heightmap, originX, originZ, zoom, CellSampler.Field.TEMPERATURE),
				new PreviewTileClimateSampler(tile, heightmap, originX, originZ, zoom, CellSampler.Field.MOISTURE),
				new PreviewTileClimateSampler(tile, heightmap, originX, originZ, zoom, CellSampler.Field.CONTINENT),
				new PreviewTileClimateSampler(tile, heightmap, originX, originZ, zoom, CellSampler.Field.EROSION),
				DensityFunctions.constant(0.0D),
				new PreviewTileClimateSampler(tile, heightmap, originX, originZ, zoom, CellSampler.Field.WEIRDNESS),
				previewGenerator.generatorSettings().value().spawnTarget()
		);
		if (TBCompat.isEnabled() && (Object) sampler instanceof TBClimateSampler terraBlenderSampler) {
			terraBlenderSampler.setUniqueness(new PreviewTileClimateSampler(
					tile, heightmap, originX, originZ, zoom, CellSampler.Field.BIOME_REGION
			));
		}
		return sampler;
	}

	public BiomePreviewIntegration.Session openIntegrationSession() {
		return BiomePreviewIntegrations.open(this.integrationContext, error -> {}, this.activeIntegrations::add);
	}

	public List<String> activeIntegrations() {
		List<String> list = new ArrayList<>(this.activeIntegrations);
		list.sort(null);
		return list;
	}

	public TerraBlenderParameterList.SelectionDiagnostics<Holder<Biome>> inspectTerraBlenderSelection(
			int quartX,
			int quartY,
			int quartZ
	) {
		return this.inspectTerraBlenderSelection(quartX, quartY, quartZ, this.sampler);
	}

	public TerraBlenderParameterList.SelectionDiagnostics<Holder<Biome>> inspectTerraBlenderSelection(
			int quartX,
			int quartY,
			int quartZ,
			Climate.Sampler sampler
	) {
		if (this.terraBlenderParameters == null) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(
					-1, null, null, "terra_blender_parameter_list_unavailable"
			);
		}
		return this.terraBlenderParameters.reterraforged$inspectSelection(
				sampler.sample(quartX, quartY, quartZ), quartX, quartY, quartZ
		);
	}

	public String warning() {
		return this.warning.get();
	}

	private Holder<Biome> resolveFallback(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
		Climate.TargetPoint target = sampler.sample(quartX, quartY, quartZ);
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