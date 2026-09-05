package raccoonman.reterraforged.world.worldgen.biome;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
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
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.runtime.CapabilityState;
import raccoonman.reterraforged.world.worldgen.runtime.MinecraftWorldgenPlanCompiler;
import raccoonman.reterraforged.world.worldgen.runtime.MinecraftBiomeSourceGraphs;
import raccoonman.reterraforged.world.worldgen.runtime.PreviewSourceContext;
import raccoonman.reterraforged.world.worldgen.runtime.PreviewSourceNegotiator;
import raccoonman.reterraforged.world.worldgen.runtime.PreviewRequest;
import raccoonman.reterraforged.world.worldgen.runtime.RequestOwnedBiomeSource;
import raccoonman.reterraforged.world.worldgen.runtime.TagEpoch;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenCompilationPurpose;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenBiomeSelection;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenContributionRevision;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenFingerprints;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenFacet;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlan;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlans;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenProviderCatalog;

public final class BiomePreviewResolver implements AutoCloseable {
	private final GeneratorContext generatorContext;
	private final Preset preset;
	private final NoiseBasedChunkGenerator generator;
	private final Climate.Sampler sampler;
	private final WorldgenPlan plan;
	private final WorldgenBiomeSelection.Executable biomeSelection;
	private final RequestOwnedBiomeSource sourceLifecycle;

	private BiomePreviewResolver(
		GeneratorContext generatorContext,
		Preset preset,
		NoiseBasedChunkGenerator generator,
		Climate.Sampler sampler,
		WorldgenPlan plan,
		WorldgenBiomeSelection.Executable biomeSelection,
		RequestOwnedBiomeSource sourceLifecycle
	) {
		this.generatorContext = generatorContext;
		this.preset = preset;
		this.generator = generator;
		this.sampler = sampler;
		this.plan = plan;
		this.biomeSelection = biomeSelection;
		this.sourceLifecycle = sourceLifecycle;
	}

	public static BiomePreviewResolver create(
		RegistryAccess.Frozen registries,
		HolderLookup.Provider provider,
		ResourceKey<LevelStem> dimension,
		Holder<DimensionType> dimensionType,
		ChunkGenerator activeGenerator,
		Preset preset,
		GeneratorContext generatorContext,
		long seed,
		String settingsIdentity,
		String resourceLayerFingerprint,
		String tagFingerprint,
		WorldgenContributionRevision.Snapshot contributionRevision,
		WorldgenProviderCatalog providers
	) {
		return create(
			registries, provider, dimension, dimensionType, activeGenerator, preset,
			generatorContext, seed, settingsIdentity, resourceLayerFingerprint,
			tagFingerprint, contributionRevision, providers, () -> false
		);
	}

	public static BiomePreviewResolver create(
		RegistryAccess.Frozen registries,
		HolderLookup.Provider provider,
		ResourceKey<LevelStem> dimension,
		Holder<DimensionType> dimensionType,
		ChunkGenerator activeGenerator,
		Preset preset,
		GeneratorContext generatorContext,
		long seed,
		String settingsIdentity,
		String resourceLayerFingerprint,
		String tagFingerprint,
		WorldgenContributionRevision.Snapshot contributionRevision,
		WorldgenProviderCatalog providers,
		BooleanSupplier cancelled
	) {
		Objects.requireNonNull(providers, "providers");
		Objects.requireNonNull(cancelled, "cancelled");
		return providers.inAcquisitionSession(cancelled, () -> createAcquired(
			registries, provider, dimension, dimensionType, activeGenerator, preset,
			generatorContext, seed, settingsIdentity, resourceLayerFingerprint,
			tagFingerprint, contributionRevision, providers, cancelled
		));
	}

	private static BiomePreviewResolver createAcquired(
		RegistryAccess.Frozen registries,
		HolderLookup.Provider provider,
		ResourceKey<LevelStem> dimension,
		Holder<DimensionType> dimensionType,
		ChunkGenerator activeGenerator,
		Preset preset,
		GeneratorContext generatorContext,
		long seed,
		String settingsIdentity,
		String resourceLayerFingerprint,
		String tagFingerprint,
		WorldgenContributionRevision.Snapshot contributionRevision,
		WorldgenProviderCatalog providers,
		BooleanSupplier cancelled
	) {
		checkCancellation(cancelled);
		if (!(activeGenerator instanceof NoiseBasedChunkGenerator activeNoise)) {
			throw new IllegalStateException(
				"The selected custom generator is an opaque root and exposes no request-owned preview factory: "
					+ activeGenerator.getClass().getName()
			);
		}

		Holder<NoiseGeneratorSettings> noiseSettings = activeNoise.generatorSettings();
		requireCurrentContributions(
			dimension, contributionRevision, providers, "before custom-source acquisition"
		);
		PreviewSourceNegotiator.Result sourceResult = PreviewSourceNegotiator.resolve(
			new PreviewSourceContext(
				seed,
				registries,
				provider,
				MinecraftBiomeSourceGraphs.acquisitionSource(activeGenerator),
				noiseSettings,
				settingsIdentity,
				resourceLayerFingerprint,
				new TagEpoch(0L, tagFingerprint),
				cancelled
			),
			providers
		);
		try {
			checkCancellation(cancelled);
			requireCurrentContributions(
				dimension, contributionRevision, providers, "after custom-source acquisition"
			);
		} catch (RuntimeException | Error failure) {
			try {
				sourceResult.owned().close();
			} catch (Throwable cleanup) {
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
		try {
			BiomeSource biomeSource = sourceResult.owned().source();
			TerraForgedChunkGenerator previewGenerator = new TerraForgedChunkGenerator(
				biomeSource, noiseSettings, sourceResult.owned().planInput(),
				sourceResult.owned().candidateRoot()
			);
			LevelStem previewStem = new LevelStem(dimensionType, previewGenerator);
			PreviewRequest request = PreviewRequest.create(
				dimension,
				seed,
				registries,
				provider,
				previewStem,
				settingsIdentity,
				resourceLayerFingerprint,
				new TagEpoch(0L, tagFingerprint),
				contributionRevision
			);
			WorldgenPlan plan = MinecraftWorldgenPlanCompiler.compile(
				request, providers, WorldgenCompilationPurpose.BIOME_PREVIEW, cancelled
			);
			checkCancellation(cancelled);
			WorldgenBiomeSelection.Executable biomeSelection = WorldgenBiomeSelection.prepare(plan);
			checkCancellation(cancelled);
			requireCurrentContributions(
				dimension, contributionRevision, providers, "during plan compilation"
			);
			Climate.Sampler sampler = decorateSampler(
				new Climate.Sampler(
					cell(generatorContext, CellSampler.Field.TEMPERATURE),
					cell(generatorContext, CellSampler.Field.MOISTURE),
					cell(generatorContext, CellSampler.Field.CONTINENT),
					cell(generatorContext, CellSampler.Field.EROSION),
					DensityFunctions.constant(0.0D),
					cell(generatorContext, CellSampler.Field.WEIRDNESS),
					noiseSettings.value().spawnTarget()
				),
				preset,
				generatorContext,
				plan
			);
			return new BiomePreviewResolver(
				generatorContext, preset, previewGenerator, sampler, plan,
				biomeSelection, sourceResult.owned()
			);
		} catch (Throwable failure) {
			try {
				sourceResult.owned().close();
			} catch (Throwable cleanup) {
				failure.addSuppressed(cleanup);
			}
			if (failure instanceof RuntimeException runtimeFailure) {
				throw runtimeFailure;
			}
			if (failure instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException("Failed preparing request-owned biome preview state", failure);
		}
	}

	private static void requireCurrentContributions(
		ResourceKey<LevelStem> dimension,
		WorldgenContributionRevision.Snapshot expected,
		WorldgenProviderCatalog providers,
		String boundary
	) {
		WorldgenContributionRevision.Snapshot current = WorldgenContributionRevision.snapshot(
			dimension, providers
		);
		if (!current.equals(expected)) {
			throw new IllegalStateException(
				"Worldgen contributions changed " + boundary + "; expected " + expected
					+ " but acquired " + current
			);
		}
	}

	private static DensityFunction cell(GeneratorContext context, CellSampler.Field field) {
		return new CellSampler(() -> context.lookup, field);
	}

	private static Climate.Sampler decorateSampler(
		Climate.Sampler sampler,
		Preset preset,
		GeneratorContext generatorContext,
		WorldgenPlan plan
	) {
		plan.samplerDecoration().initialize(
			plan,
			new WorldgenPlans.SamplerInputs(preset, generatorContext),
			sampler
		);
		((RTFClimateSampler) (Object) sampler).setWorldgenPlan(plan);
		return sampler;
	}

	public Holder<Biome> resolveQuart(int quartX, int quartY, int quartZ) {
		return this.resolveQuart(quartX, quartY, quartZ, this.sampler);
	}

	public Holder<Biome> resolveQuart(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
		return this.resolveQuartInCell(quartX, quartY, quartZ, sampler, null);
	}

	private Holder<Biome> resolveQuartInCell(
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler,
		Cell preparedCell
	) {
		Holder<Biome> selected = preparedCell == null
			? this.biomeSelection.resolve(quartX, quartY, quartZ, sampler)
			: this.biomeSelection.resolveInCell(
				quartX, quartY, quartZ, sampler,
				preparedCell.biomeRegionX, preparedCell.biomeRegionZ
			)
		;
		if (selected == null) {
			throw new IllegalStateException("Selected biome source returned null for request-owned preview plan");
		}
		return selected;
	}

	public Climate.Sampler tileClimateSampler(Tile tile, int centerX, int centerZ, int zoom) {
		return this.tileRequest(tile, centerX, centerZ, zoom).climateSampler();
	}

	public Climate.Sampler tileClimateSamplerAtOrigin(Tile tile, int originX, int originZ, int zoom) {
		return this.tileRequestAtOrigin(tile, originX, originZ, zoom).climateSampler();
	}

	public TileBiomeRequest tileRequest(Tile tile, int centerX, int centerZ, int zoom) {
		if (zoom <= 0) {
			throw new IllegalArgumentException("Preview zoom must be positive");
		}
		float originX = centerX - tile.getBlockSize().size() * (float) zoom / 2.0F;
		float originZ = centerZ - tile.getBlockSize().size() * (float) zoom / 2.0F;
		return this.tileRequestAtOrigin(tile, originX, originZ, zoom);
	}

	public TileBiomeRequest tileRequestAtOrigin(Tile tile, int originX, int originZ, int zoom) {
		if (zoom <= 0) {
			throw new IllegalArgumentException("Preview zoom must be positive");
		}
		return this.tileRequestAtOrigin(tile, (float) originX, (float) originZ, zoom);
	}

	public ResolvedTile resolveSurfaceTile(
		Tile tile,
		int centerX,
		int centerZ,
		int zoom,
		Levels levels,
		BooleanSupplier cancelled
	) {
		Objects.requireNonNull(tile, "tile");
		Objects.requireNonNull(levels, "levels");
		Objects.requireNonNull(cancelled, "cancelled");
		if (zoom <= 0) {
			throw new IllegalArgumentException("Preview zoom must be positive");
		}
		int size = tile.getBlockSize().size();
		int border = tile.getBlockSize().border();
		int halfSize = size / 2;
		int[] quartXs = new int[size];
		int[] quartZs = new int[size];
		for (int x = 0; x < size; x++) {
			quartXs[x] = QuartPos.fromBlock(Math.addExact(
				centerX, Math.multiplyExact(x - halfSize, zoom)
			));
		}
		for (int z = 0; z < size; z++) {
			checkCancellation(cancelled);
			quartZs[z] = QuartPos.fromBlock(Math.addExact(
				centerZ, Math.multiplyExact(z - halfSize, zoom)
			));
		}

		@SuppressWarnings("unchecked")
		Holder<Biome>[] biomes = new Holder[Math.multiplyExact(size, size)];
		PreviewQueryExecutor.resolve(
			biomes,
			size,
			size,
			this.supportsParallelTileQueries(),
			ThreadPools.previewParallelism(),
			() -> {
				TileBiomeRequest request = this.tileRequest(tile, centerX, centerZ, zoom);
				PreviewQuartCache quartCache = zoom < QuartPos.SIZE ? new PreviewQuartCache() : null;
				return (x, z) -> {
					int quartX = quartXs[x];
					int quartZ = quartZs[z];
					Cell cell = tile.getCellRaw(border + x, border + z);
					int quartY = QuartPos.fromBlock(surfaceY(cell, levels));
					if (quartCache == null) {
						return request.resolveQuartInCell(quartX, quartY, quartZ, cell);
					}
					Holder<Biome> biome = quartCache.get(quartX, quartY, quartZ);
					if (biome == null) {
						biome = request.resolveQuartInCell(quartX, quartY, quartZ, cell);
						quartCache.put(quartX, quartY, quartZ, biome);
					}
					return biome;
				};
			},
			cancelled,
			ThreadPools.WORLD_GEN
		);
		return new ResolvedTile(size, biomes);
	}

	private TileBiomeRequest tileRequestAtOrigin(Tile tile, float originX, float originZ, int zoom) {
		var heightmap = this.generatorContext.lookup.getHeightmap();
		PreviewTileClimateSampler.TileLookup tileLookup = new PreviewTileClimateSampler.TileLookup(
			tile, originX, originZ, zoom
		);
		Climate.Sampler sampler = new Climate.Sampler(
			new PreviewTileClimateSampler(tileLookup, heightmap, CellSampler.Field.TEMPERATURE),
			new PreviewTileClimateSampler(tileLookup, heightmap, CellSampler.Field.MOISTURE),
			new PreviewTileClimateSampler(tileLookup, heightmap, CellSampler.Field.CONTINENT),
			new PreviewTileClimateSampler(tileLookup, heightmap, CellSampler.Field.EROSION),
			DensityFunctions.constant(0.0D),
			new PreviewTileClimateSampler(tileLookup, heightmap, CellSampler.Field.WEIRDNESS),
			this.generator.generatorSettings().value().spawnTarget()
		);
		return new TileBiomeRequest(
			tileLookup,
			decorateSampler(sampler, this.preset, this.generatorContext, this.plan)
		);
	}

	public final class TileBiomeRequest {
		private final PreviewTileClimateSampler.TileLookup tileLookup;
		private final Climate.Sampler sampler;

		private TileBiomeRequest(
			PreviewTileClimateSampler.TileLookup tileLookup,
			Climate.Sampler sampler
		) {
			this.tileLookup = tileLookup;
			this.sampler = sampler;
		}

		public Holder<Biome> resolveQuart(int quartX, int quartY, int quartZ) {
			Cell cell = this.tileLookup.lookupBlock(
				QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ)
			);
			return this.resolveQuartInCell(quartX, quartY, quartZ, cell);
		}

		private Holder<Biome> resolveQuartInCell(int quartX, int quartY, int quartZ, Cell cell) {
			return BiomePreviewResolver.this.resolveQuartInCell(
				quartX, quartY, quartZ, this.sampler, cell
			);
		}

		public WorldgenPlans.ProviderResult inspectProviderSelection(
			int quartX,
			int quartY,
			int quartZ
		) {
			Cell cell = this.tileLookup.lookupBlock(
				QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ)
			);
			return BiomePreviewResolver.this.inspectProviderSelectionInCell(
				quartX, quartY, quartZ, this.sampler, cell.biomeRegionX, cell.biomeRegionZ
			);
		}

		public Climate.Sampler climateSampler() {
			return this.sampler;
		}
	}

	public WorldgenPlan plan() {
		return this.plan;
	}

	public boolean supportsParallelTileQueries() {
		return this.biomeSelection.supportsIsolatedParallelRead();
	}

	public Set<WorldgenFacet> tileQueryFacets() {
		return Set.of(
			WorldgenFacet.PROVIDER_SELECTION,
			WorldgenFacet.SELECTION_DECORATION,
			WorldgenFacet.SPATIAL_OWNERSHIP,
			WorldgenFacet.SAMPLER_DECORATION
		);
	}

	private static int surfaceY(Cell cell, Levels levels) {
		int minY = -levels.worldDepth;
		int maxY = Math.max(minY, levels.terrainScaleFactor - 1);
		return Math.max(minY, Math.min(maxY, levels.scale(cell.height)));
	}

	private static void checkCancellation(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
			throw new java.util.concurrent.CancellationException("Preview request superseded");
		}
	}

	public static final class ResolvedTile {
		private final int size;
		private final Holder<Biome>[] biomes;

		private ResolvedTile(int size, Holder<Biome>[] biomes) {
			this.size = size;
			this.biomes = biomes;
		}

		public int size() {
			return this.size;
		}

		public Holder<Biome> biomeAt(int x, int z) {
			Objects.checkIndex(x, this.size);
			Objects.checkIndex(z, this.size);
			return this.biomes[z * this.size + x];
		}
	}

	public WorldgenPlans.ProviderResult inspectProviderSelection(
		int quartX,
		int quartY,
		int quartZ
	) {
		return this.inspectProviderSelection(quartX, quartY, quartZ, this.sampler);
	}

	public WorldgenPlans.ProviderResult inspectProviderSelection(
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler
	) {
		Cell cell = new Cell();
		this.generatorContext.lookup.applyCell(
			cell, QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ), false, true
		);
		return this.inspectProviderSelectionInCell(
			quartX, quartY, quartZ, sampler, cell.biomeRegionX, cell.biomeRegionZ
		);
	}

	private WorldgenPlans.ProviderResult inspectProviderSelectionInCell(
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler,
		long biomeCellX,
		long biomeCellZ
	) {
		return this.plan.execution().execute(
			Set.of(
				WorldgenFacet.PROVIDER_SELECTION,
				WorldgenFacet.SPATIAL_OWNERSHIP,
				WorldgenFacet.SAMPLER_DECORATION
			),
			() -> {
				Climate.TargetPoint target = this.plan.samplerDecoration().sample(
					sampler, quartX, quartY, quartZ
				);
				WorldgenPlans.SpatialResult spatial = this.plan.spatialOwnership().resolver()
					.orElseThrow(() -> new IllegalStateException(
						"Preview plan has no spatial ownership contract"
					))
					.resolve(biomeCellX, biomeCellZ);
				return this.plan.providerSelection().resolve(spatial.domain(), target)
					.orElseThrow(() -> new IllegalStateException(
						"Preview spatial plan selected unknown provider domain " + spatial.domain()
					));
			}
		);
	}

	public boolean isUnderground(Holder<Biome> biome) {
		return UndergroundBiomeTags.isCave(biome);
	}

	@Override
	public void close() {
		try {
			this.sourceLifecycle.close();
		} catch (Exception sourceFailure) {
			throw new IllegalStateException("Failed closing request-owned biome preview state", sourceFailure);
		}
	}
}
