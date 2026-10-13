package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;

public final class TerraForgedChunkGenerator extends NoiseBasedChunkGenerator
		implements AutoCloseable, PlanBackedBiomeDecoration {
	public static final MapCodec<TerraForgedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.selectedBiomeSource),
			NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(TerraForgedChunkGenerator::generatorSettings),
			BiomeSource.CODEC.optionalFieldOf("dimension_biome_source").forGetter(generator -> generator.dimensionBiomeSource)
	).apply(instance, instance.stable(TerraForgedChunkGenerator::fromDimensionInputs)));

	private static final net.minecraft.resources.ResourceKey<PlacedFeature> FREEZE_TOP_LAYER =
			net.minecraft.resources.ResourceKey.create(
					Registries.PLACED_FEATURE, ResourceLocation.withDefaultNamespace("freeze_top_layer")
			);

	private final BiomeSource selectedBiomeSource;
	private final Optional<BiomeSource> dimensionBiomeSource;
	private final BiomeSource acquisitionBiomeSource;
	private final Optional<BiomeSourcePlanInput> acquisitionBiomePlanInput;
	private final Optional<BiomeCandidateRoot> acquisitionBiomeCandidateRoot;
	private final AtomicReference<Map<ResourceLocation, CapabilityFailure>> preServerFailures =
			new AtomicReference<>(Map.of());
	private WorldgenProviderCatalog providerCatalog;
	private volatile WorldgenRuntimeBinding runtime;
	private FTFRandomState randomState;
	private final ThreadLocal<BiomeDecorationPlan> activeBiomeDecoration = new ThreadLocal<>();
	private final ThreadLocal<WorldgenPlans.Structures> activeStructures = new ThreadLocal<>();
	private static final ThreadLocal<Cell> SCRATCH_CELL = ThreadLocal.withInitial(Cell::new);

	public TerraForgedChunkGenerator(BiomeSource biomeSource, net.minecraft.core.Holder<NoiseGeneratorSettings> settings) {
		this(biomeSource, settings, Optional.empty(), Optional.empty());
	}

	public TerraForgedChunkGenerator(
			BiomeSource biomeSource,
			net.minecraft.core.Holder<NoiseGeneratorSettings> settings,
			Optional<BiomeSourcePlanInput> planInput
	) {
		this(biomeSource, settings, planInput, Optional.empty());
	}

	public TerraForgedChunkGenerator(
			BiomeSource biomeSource,
			net.minecraft.core.Holder<NoiseGeneratorSettings> settings,
			Optional<BiomeSourcePlanInput> planInput,
			Optional<BiomeCandidateRoot> candidateRoot
	) {
		this(
				biomeSource, settings, planInput, candidateRoot,
				Optional.empty(), new UnifiedBiomeSource(biomeSource, planInput)
		);
	}

	private static TerraForgedChunkGenerator fromDimensionInputs(
			BiomeSource selectedSource,
			Holder<NoiseGeneratorSettings> settings,
			Optional<BiomeSource> declaredSource
	) {
		return new TerraForgedChunkGenerator(selectedSource, settings, Optional.empty(), Optional.empty(),
				declaredSource, new UnifiedBiomeSource(declaredSource.orElse(selectedSource), Optional.empty()));
	}

	private TerraForgedChunkGenerator(
			BiomeSource biomeSource,
			net.minecraft.core.Holder<NoiseGeneratorSettings> settings,
			Optional<BiomeSourcePlanInput> planInput,
			Optional<BiomeCandidateRoot> candidateRoot,
			Optional<BiomeSource> declaredSource,
			UnifiedBiomeSource unifiedBiomeSource
	) {
		super(unifiedBiomeSource, settings);
		this.selectedBiomeSource = Objects.requireNonNull(biomeSource, "biomeSource");
		this.dimensionBiomeSource = Objects.requireNonNull(declaredSource, "declaredSource");
		this.acquisitionBiomeSource = declaredSource.orElse(biomeSource);
		this.acquisitionBiomePlanInput = Objects.requireNonNull(planInput, "planInput");
		this.acquisitionBiomeCandidateRoot = Objects.requireNonNull(candidateRoot, "candidateRoot");
		if (this.acquisitionBiomePlanInput.isPresent() && this.acquisitionBiomeCandidateRoot.isPresent()) {
			throw new IllegalArgumentException(
					"A direct custom-source plan and a candidate-table root are mutually exclusive"
			);
		}
		unifiedBiomeSource.bind(this);
	}

	public BiomeSource acquisitionBiomeSource() {
		return this.acquisitionBiomeSource;
	}

	TerraForgedChunkGenerator withDimensionBiomeSource(BiomeSource source) {
		Objects.requireNonNull(source, "source");
		if (this.acquisitionBiomeSource == source) {
			return this;
		}
		if (this.acquisitionBiomePlanInput.isPresent() || this.acquisitionBiomeCandidateRoot.isPresent()) {
			throw new IllegalStateException("Cannot replace an explicit custom biome plan with a dimension declaration");
		}
		return fromDimensionInputs(this.selectedBiomeSource, this.generatorSettings(), Optional.of(source));
	}

	TerraForgedChunkGenerator withoutDimensionBiomeSource() {
		return this.dimensionBiomeSource.isEmpty() ? this
				: new TerraForgedChunkGenerator(this.selectedBiomeSource, this.generatorSettings());
	}

	public Optional<BiomeSourcePlanInput> acquisitionBiomePlanInput() {
		return this.acquisitionBiomePlanInput;
	}

	public Optional<BiomeCandidateRoot> acquisitionBiomeCandidateRoot() {
		return this.acquisitionBiomeCandidateRoot;
	}

	Optional<BiomeSourcePlanInput> acquireBiomePlanInput(WorldgenOwner owner) {
		BiomeSourcePlanInput input;
		if (this.acquisitionBiomePlanInput.isPresent()) {
			input = this.acquisitionBiomePlanInput.orElseThrow();
		} else {
			if (!(this.acquisitionBiomeSource instanceof BiomeSourcePlanInputFactory factory)) {
				return Optional.empty();
			}
			input = Objects.requireNonNull(
					factory.createBiomeSourcePlanInput(owner), "custom biome-source plan input"
			);
			if (!factory.biomeSourcePlanFactoryId().equals(input.id())) {
				throw new IllegalStateException(
						"Custom biome-source plan ID " + input.id() + " does not match factory "
								+ factory.biomeSourcePlanFactoryId()
				);
			}
		}
		return Optional.of(input.canonicalize(
				owner.registries().registryOrThrow(Registries.BIOME)
		));
	}

	void publishPreServerFailures(Map<ResourceLocation, CapabilityFailure> failures) {
		this.preServerFailures.set(Map.copyOf(failures));
	}

	Optional<CapabilityFailure> preServerFailure(ResourceLocation provider) {
		return Optional.ofNullable(this.preServerFailures.get().get(provider));
	}

	public synchronized WorldgenProviderCatalog acquireProviderCatalog() {
		if (this.providerCatalog == null) {
			this.providerCatalog = WorldgenCapabilityDiscovery.discover(
					TerraForgedChunkGenerator.class.getClassLoader()
			);
		}
		return this.providerCatalog;
	}

	synchronized Optional<WorldgenProviderCatalog> existingProviderCatalog() {
		return Optional.ofNullable(this.providerCatalog);
	}

	synchronized void publishPreServerCatalog(
			WorldgenProviderCatalog catalog,
			Map<ResourceLocation, CapabilityFailure> failures
	) {
		if (this.runtime != null && this.providerCatalog != catalog) {
			throw new IllegalStateException("Cannot replace a live worldgen owner's provider catalog");
		}
		this.providerCatalog = Objects.requireNonNull(catalog, "catalog");
		this.preServerFailures.set(Map.copyOf(failures));
	}

	Optional<Set<Holder<Biome>>> possibleRuntimeBiomes() {
		WorldgenRuntimeBinding current = this.runtime;
		return current == null ? Optional.empty() : Optional.of(current.current().possibleBiomes());
	}

	@Override
	protected MapCodec<? extends TerraForgedChunkGenerator> codec() {
		return CODEC;
	}

	public synchronized WorldgenPlan initializeEpoch(
			WorldgenEpoch epoch,
			FTFRandomState randomState,
			WorldgenProviderCatalog providers
	) throws Exception {
		Objects.requireNonNull(epoch, "epoch");
		Objects.requireNonNull(randomState, "randomState");
		Objects.requireNonNull(providers, "providers");
		WorldgenRuntimeBinding current = this.runtime;
		if (current != null) {
			if (current.epoch().id().equals(epoch.id())) {
				return current.plan();
			}
			throw new IllegalStateException(
					"Generator root is already owned by worldgen epoch " + current.epoch().id()
			);
		}

		if (this.providerCatalog != null && this.providerCatalog != providers) {
			throw new IllegalStateException("Worldgen epoch uses a different provider acquisition session");
		}
		WorldgenPlan compiled = MinecraftWorldgenPlanCompiler.compile(epoch, providers);
		WorldgenBiomeSelection.requireExecutablePlan(compiled);
		WorldgenRuntimeBinding prepared = null;
		boolean acquiredRandomState = randomState.epoch() == null;
		try {
			randomState.initialize(epoch);
			prepared = WorldgenRuntimeBinding.create(
					epoch, compiled, composeGenerationSettings(compiled)
			);
			randomState.bindPlan(prepared);
			logOrePlan(compiled);
		} catch (Exception | Error failure) {
			if (prepared != null) {
				try {
					prepared.close();
				} catch (RuntimeException | Error cleanup) {
					failure.addSuppressed(cleanup);
				}
			}
			if (acquiredRandomState) {
				try {
					randomState.close();
				} catch (RuntimeException | Error cleanup) {
					failure.addSuppressed(cleanup);
				}
			}
			throw failure;
		}
		this.providerCatalog = providers;
		this.randomState = randomState;
		this.runtime = prepared;
		return compiled;
	}

	public Optional<WorldgenEpoch> epoch() {
		WorldgenRuntimeBinding current = this.runtime;
		return current == null ? Optional.empty() : Optional.of(current.epoch());
	}

	public Optional<WorldgenPlan> plan() {
		WorldgenRuntimeBinding current = this.runtime;
		return current == null ? Optional.empty() : Optional.of(current.plan());
	}

	WorldgenBiomeSelection.Executable currentBiomeSelection() {
		WorldgenRuntimeBinding current = this.runtime;
		return current == null ? null : current.current().biomeSelection();
	}

	public synchronized WorldgenPlan refreshInputs(
			long replacementResourceRevision,
			String replacementResourceLayerFingerprint,
			TagEpoch replacementTags,
			WorldgenContributionRevision.Snapshot replacementContributions,
			FTFRandomState randomState
	) throws Exception {
		WorldgenRuntimeBinding binding = Objects.requireNonNull(this.runtime, "Generator root has no active epoch");
		WorldgenRuntimeBinding.State current = binding.current();
		WorldgenEpoch refreshedEpoch = current.epoch().withInputs(
				replacementResourceRevision, replacementResourceLayerFingerprint,
				replacementTags, replacementContributions
		);
		try {
			WorldgenProviderCatalog providers = Objects.requireNonNull(
					this.providerCatalog, "Generator root has no provider acquisition session"
			);
			WorldgenPlan refreshedPlan = MinecraftWorldgenPlanCompiler.compile(refreshedEpoch, providers);
			WorldgenBiomeSelection.requireExecutablePlan(refreshedPlan);
			randomState.preparePlanRebind(refreshedEpoch, refreshedPlan);
			binding.replace(current, refreshedEpoch, refreshedPlan, composeGenerationSettings(refreshedPlan));
			logOrePlan(refreshedPlan);
			return refreshedPlan;
		} catch (Exception | Error failure) {
			if (binding.current() == current) {
				binding.reject(refreshedEpoch, failure);
			}
			throw failure;
		}
	}

	public Optional<WorldgenRuntimeBinding.RejectedPublication> rejectedPublication() {
		WorldgenRuntimeBinding current = this.runtime;
		return current == null ? Optional.empty() : current.rejection();
	}

	void rejectInputSnapshot(
			long resourceRevision,
			String resourceLayerFingerprint,
			TagEpoch tags,
			WorldgenContributionRevision.Snapshot attempted,
			Throwable failure
	) {
		WorldgenRuntimeBinding binding = Objects.requireNonNull(
				this.runtime, "Generator root has no active epoch"
		);
		WorldgenEpoch current = binding.epoch();
		binding.reject(
				current.id(), resourceRevision, resourceLayerFingerprint, tags, attempted, failure
		);
	}

	@Override
	public java.util.concurrent.CompletableFuture<ChunkAccess> createBiomes(
			RandomState randomState,
			Blender blender,
			StructureManager structureManager,
			ChunkAccess chunk
	) {
		this.requireBiomeSelection();
		return super.createBiomes(randomState, blender, structureManager, chunk);
	}

	private GeneratorContext getGeneratorContext(RandomState random) {
		if (this.randomState != null) {
			return this.randomState.generatorContext();
		}
		return null;
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
		GeneratorContext ctx = getGeneratorContext(random);

		// Safety fallback if generator context isn't bound yet
		if (ctx == null) {
			return super.getBaseHeight(x, z, type, level, random);
		}

		Cell cell = SCRATCH_CELL.get();

		// load = true -> triggers computeAccurate() -> TileCache.acquire() -> WorldFilters/Erosion
		ctx.lookup.applyCell(cell, x, z, true, false);

		// Convert normalized height (0.0F - 1.0F) to world Y block coordinate
		return ctx.levels.scale(cell.height);
	}

	public Holder<Biome> resolveBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
		WorldgenRuntimeBinding binding = Objects.requireNonNull(
				this.runtime, "Generator root has no active worldgen epoch"
		);
		WorldgenRuntimeBinding.State state = binding.current();
		return state.biomeSelection().resolve(quartX, quartY, quartZ, sampler);
	}

	public Holder<Biome> resolveBiomeInCell(
			int quartX,
			int quartY,
			int quartZ,
			Climate.Sampler sampler,
			long biomeCellX,
			long biomeCellZ
	) {
		WorldgenRuntimeBinding binding = Objects.requireNonNull(
				this.runtime, "Generator root has no active worldgen epoch"
		);
		WorldgenRuntimeBinding.State state = binding.current();
		return state.biomeSelection().resolveInCell(
				quartX, quartY, quartZ, sampler, biomeCellX, biomeCellZ
		);
	}

	@Override
	public BiomeGenerationSettings getBiomeGenerationSettings(Holder<Biome> biome) {
		WorldgenRuntimeBinding current = this.runtime;
		if (current == null) {
			return super.getBiomeGenerationSettings(biome);
		}
		return current.current().biomeDecorationPlan().generationSettings(biome);
	}

	BiomeGenerationSettings realizedBiomeGenerationSettings(Holder<Biome> biome) {
		return super.getBiomeGenerationSettings(biome);
	}

	@Override
	public java.util.concurrent.CompletableFuture<ChunkAccess> fillFromNoise(
			Blender blender,
			RandomState randomState,
			StructureManager structureManager,
			ChunkAccess chunk
	) {
		WorldgenPlan current = this.requireStage(WorldgenFacet.DENSITY_SETTINGS);
		Holder<NoiseGeneratorSettings> selectedSettings = current.densitySettings().settings().orElseThrow(
				() -> new IllegalStateException("FTF density stage has no executable settings root")
		);
		if (selectedSettings.value() != this.generatorSettings().value()) {
			throw new IllegalStateException(
					"FTF density plan is not coupled to the registered generator root"
			);
		}
		NoiseFillExtent extent = NoiseFillExtent.fullConfiguredHeight(
				selectedSettings.value().noiseSettings(),
				chunk.getHeightAccessorForGeneration()
		);
		if (extent.empty()) {
			return java.util.concurrent.CompletableFuture.completedFuture(chunk);
		}
		return super.fillFromNoise(blender, randomState, structureManager, chunk);
	}

	@Override
	public void buildSurface(
			WorldGenRegion region,
			StructureManager structureManager,
			RandomState randomState,
			ChunkAccess chunk
	) {
		WorldgenRuntimeBinding.State stage = this.requireState(WorldgenFacet.SURFACE);
		super.buildSurface(region, structureManager, randomState, chunk);
		stage.surfaceColumns().apply(region, this, chunk, stage.possibleBiomes());
	}

	@Override
	public void buildSurface(
			ChunkAccess chunk,
			WorldGenerationContext context,
			RandomState randomState,
			StructureManager structureManager,
			BiomeManager biomeManager,
			Registry<Biome> biomes,
			Blender blender
	) {
		WorldgenPlan current = this.requireStage(WorldgenFacet.SURFACE);
		NoiseGeneratorSettings settings = current.densitySettings().settings().orElseThrow(
				() -> new IllegalStateException("FTF surface stage has no coupled noise-settings root")
		).value();
		var surfaceRule = current.surface().root().orElseThrow(
				() -> new IllegalStateException("FTF surface stage has no typed surface-rule root")
		);
		NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
				value -> this.createNoiseChunk(value, structureManager, blender, randomState)
		);
		randomState.surfaceSystem().buildSurface(
				randomState,
				biomeManager,
				biomes,
				settings.useLegacyRandomSource(),
				context,
				chunk,
				noiseChunk,
				surfaceRule
		);
	}

	@Override
	public void applyCarvers(
			WorldGenRegion region,
			long seed,
			RandomState randomState,
			BiomeManager biomeManager,
			StructureManager structureManager,
			ChunkAccess chunk,
			GenerationStep.Carving step
	) {
		this.requireStage(WorldgenFacet.CARVERS);
		this.requireStage(WorldgenFacet.SURFACE);
		super.applyCarvers(region, seed, randomState, biomeManager, structureManager, chunk, step);
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		WorldgenRuntimeBinding.State stage = this.requireState(WorldgenFacet.PLACED_FEATURES);
		WorldgenPlan current = stage.plan();
		PlanDescriptor structures = current.structures().descriptor();
		if (structures.state() == CapabilityState.UNAVAILABLE) {
			CapabilityFailure cause = structures.firstCause().orElseThrow();
			throw new IllegalStateException(
					"FTF generator stage " + WorldgenFacet.STRUCTURES + " is unavailable [" +
							cause.code() + "]: " + cause.message()
			);
		}
		BiomeDecorationPlan stagePlan = stage.biomeDecorationPlan();
		BiomeDecorationPlan previous = this.activeBiomeDecoration.get();
		this.activeBiomeDecoration.set(stagePlan);
		try {
			super.applyBiomeDecoration(level, chunk, structureManager);
		} finally {
			if (previous == null) {
				this.activeBiomeDecoration.remove();
			} else {
				this.activeBiomeDecoration.set(previous);
			}
		}
	}

	@Override
	public BiomeDecorationPlan activeBiomeDecorationPlan() {
		return this.activeBiomeDecoration.get();
	}

	@Override
	public void createStructures(
			RegistryAccess registries,
			ChunkGeneratorStructureState structureState,
			StructureManager structureManager,
			ChunkAccess chunk,
			StructureTemplateManager templates
	) {
		WorldgenPlans.Structures plan = this.requireStage(WorldgenFacet.STRUCTURES).structures();
		Set<ResourceKey<StructureSet>> plannedSets =
				plan.sets().stream().map(Holder.Reference::key).collect(Collectors.toUnmodifiableSet());
		Supplier<Set<ResourceKey<Structure>>> plannedStructures = Suppliers.memoize(() ->
				plan.structures().stream().map(Holder.Reference::key).collect(Collectors.toUnmodifiableSet()));
		structureState.possibleStructureSets().forEach(holder -> {
			if (isPlanned(holder, plannedSets, plannedStructures)) {
				return;
			}
			throw new IllegalStateException("Structure state selected a set outside the compiled plan: " + holder);
		});
		WorldgenPlans.Structures previous = this.activeStructures.get();
		this.activeStructures.set(plan);
		try {
			super.createStructures(registries, structureState, structureManager, chunk, templates);
		} finally {
			if (previous == null) {
				this.activeStructures.remove();
			} else {
				this.activeStructures.set(previous);
			}
		}
	}

	/**
	 * Registered structure sets must come from the compiled plan. Some mods (Create: Structures Arise, for one) rebuild
	 * registered sets with their own placement and hand them over as direct holders without a registry key, so such a
	 * set is accepted when every structure it can place belongs to the plan.
	 */
	private static boolean isPlanned(
			Holder<StructureSet> holder,
			Set<ResourceKey<StructureSet>> plannedSets,
			Supplier<Set<ResourceKey<Structure>>> plannedStructures
	) {
		Optional<ResourceKey<StructureSet>> key = holder.unwrapKey();
		if (key.isPresent()) {
			return plannedSets.contains(key.get());
		}
		List<StructureSet.StructureSelectionEntry> entries = holder.value().structures();
		return !entries.isEmpty() && entries.stream().allMatch(entry ->
				entry.structure().unwrapKey().map(structure -> plannedStructures.get().contains(structure)).orElse(false));
	}

	public WorldgenPlans.Structures activeStructurePlan() {
		WorldgenPlans.Structures active = this.activeStructures.get();
		return active == null ? this.requireStage(WorldgenFacet.STRUCTURES).structures() : active;
	}

	private WorldgenPlan requireStage(WorldgenFacet facet) {
		return this.requireState(facet).plan();
	}

	private WorldgenRuntimeBinding.State requireState(WorldgenFacet facet) {
		WorldgenRuntimeBinding binding = this.runtime;
		if (binding == null) {
			throw new IllegalStateException("FTF generator stage " + facet + " has no active worldgen epoch");
		}
		WorldgenRuntimeBinding.State runtime = binding.current();
		if (!runtime.plan().owner().id().equals(runtime.epoch().id())) {
			throw new IllegalStateException("FTF generator stage " + facet + " has no active worldgen epoch");
		}
		WorldgenPlan current = runtime.plan();
		PlanDescriptor descriptor = current.facet(facet).descriptor();
		if (descriptor.state() == CapabilityState.UNAVAILABLE) {
			CapabilityFailure cause = descriptor.firstCause().orElseThrow();
			throw new IllegalStateException(
					"FTF generator stage " + facet + " is unavailable [" + cause.code() + "]: " + cause.message()
			);
		}
		return runtime;
	}

	private WorldgenPlan requireBiomeSelection() {
		WorldgenPlan current = this.requireStage(WorldgenFacet.PROVIDER_SELECTION);
		this.requireStage(WorldgenFacet.SELECTION_DECORATION);
		this.requireStage(WorldgenFacet.SPATIAL_OWNERSHIP);
		return current;
	}

	private static void logOrePlan(WorldgenPlan plan) {
		var ores = plan.placedFeatures().ores();
		etcodehome.freeterraforged.FTFCommon.LOGGER.info("Dynamic ore contract inventory: {}", ores.summary());
		ores.failures().forEach(failure -> etcodehome.freeterraforged.FTFCommon.LOGGER.warn(
				"Dynamic ore contract inspection failure: {}", failure
		));
	}

	@Override
	public synchronized void close() {
		WorldgenRuntimeBinding current = this.runtime;
		FTFRandomState closingRandomState = this.randomState;
		this.runtime = null;
		this.randomState = null;
		this.providerCatalog = null;
		this.preServerFailures.set(Map.of());
		Throwable failure = null;
		try {
			if (closingRandomState != null) {
				closingRandomState.close();
			}
		} catch (RuntimeException | Error randomStateFailure) {
			failure = randomStateFailure;
		}
		try {
			if (current != null) {
				current.close();
			}
		} catch (RuntimeException | Error bindingFailure) {
			if (failure == null) {
				failure = bindingFailure;
			} else if (bindingFailure instanceof Error && !(failure instanceof Error)) {
				bindingFailure.addSuppressed(failure);
				failure = bindingFailure;
			} else {
				failure.addSuppressed(bindingFailure);
			}
		}
		if (failure instanceof RuntimeException runtime) {
			throw runtime;
		}
		if (failure instanceof Error error) {
			throw error;
		}
	}

	private static Map<net.minecraft.resources.ResourceKey<Biome>, BiomeGenerationSettings>
	composeGenerationSettings(WorldgenPlan plan) {
		Map<net.minecraft.resources.ResourceKey<Biome>, BiomeGenerationSettings> settings =
				new java.util.LinkedHashMap<>(plan.placedFeatures().generationSettings());
		TreeSet<net.minecraft.resources.ResourceKey<Biome>> biomes = new TreeSet<>(
				java.util.Comparator.comparing(key -> key.location().toString())
		);
		for (Holder<Biome> biome : WorldgenBiomeSelection.possibleBiomes(plan)) {
			biomes.add(biome.unwrapKey().orElseThrow(
					() -> new IllegalStateException("Selected biome has no registry identity")
			));
		}
		// freeze_top_layer is gated by a BiomeFilter evaluated at the chunk's min corner, so
		// every possible biome must carry it or whole chunks lose snow.
		Holder<PlacedFeature> freezeTopLayer = findFeature(plan, biomes);
		for (net.minecraft.resources.ResourceKey<Biome> biome : biomes) {
			BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
			for (GenerationStep.Carving step : GenerationStep.Carving.values()) {
				plan.carvers().forBiome(biome, step).forEach(carver -> builder.addCarver(step, carver));
			}
			int featureSteps = plan.placedFeatures().byBiome().getOrDefault(biome, Map.of())
					.keySet().stream()
					.mapToInt(Integer::intValue)
					.max()
					.orElse(-1);
			boolean hasFreeze = false;
			for (int step = 0; step <= featureSteps; step++) {
				for (Holder<PlacedFeature> feature : plan.placedFeatures().forBiome(biome, step)) {
					builder.addFeature(step, feature);
					hasFreeze |= feature.is(FREEZE_TOP_LAYER);
				}
			}
			if (!hasFreeze && freezeTopLayer != null) {
				builder.addFeature(GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal(), freezeTopLayer);
			}
			settings.put(biome, builder.build());
		}
		return Map.copyOf(settings);
	}

	private static Holder<PlacedFeature> findFeature(
			WorldgenPlan plan,
			Set<net.minecraft.resources.ResourceKey<Biome>> biomes
	) {
		for (net.minecraft.resources.ResourceKey<Biome> biome : biomes) {
			for (int step : plan.placedFeatures().byBiome().getOrDefault(biome, Map.of()).keySet()) {
				for (Holder<PlacedFeature> feature : plan.placedFeatures().forBiome(biome, step)) {
					if (feature.is(FREEZE_TOP_LAYER)) {
						return feature;
					}
				}
			}
		}
		return null;
	}
}