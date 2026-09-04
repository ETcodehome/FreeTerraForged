package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;

/** FTF-owned generator root. Stage behavior is initially inherited exactly from vanilla noise generation. */
public final class TerraForgedChunkGenerator extends NoiseBasedChunkGenerator
	implements AutoCloseable, PlanBackedBiomeDecoration {
	public static final MapCodec<TerraForgedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(TerraForgedChunkGenerator::acquisitionBiomeSource),
		NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(TerraForgedChunkGenerator::generatorSettings)
	).apply(instance, instance.stable(TerraForgedChunkGenerator::new)));

	private final BiomeSource acquisitionBiomeSource;
	private final Optional<BiomeSourcePlanInput> acquisitionBiomePlanInput;
	private final Optional<BiomeCandidateRoot> acquisitionBiomeCandidateRoot;
	private final AtomicReference<Map<ResourceLocation, CapabilityFailure>> preServerFailures =
		new AtomicReference<>(Map.of());
	private WorldgenProviderCatalog providerCatalog;
	private volatile WorldgenRuntimeBinding runtime;
	private RTFRandomState randomState;
	private final ThreadLocal<BiomeDecorationPlan> activeBiomeDecoration = new ThreadLocal<>();
	private final ThreadLocal<WorldgenPlans.Structures> activeStructures = new ThreadLocal<>();

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
			new UnifiedBiomeSource(biomeSource, planInput)
		);
	}

	private TerraForgedChunkGenerator(
		BiomeSource biomeSource,
		net.minecraft.core.Holder<NoiseGeneratorSettings> settings,
		Optional<BiomeSourcePlanInput> planInput,
		Optional<BiomeCandidateRoot> candidateRoot,
		UnifiedBiomeSource unifiedBiomeSource
	) {
		super(unifiedBiomeSource, settings);
		this.acquisitionBiomeSource = biomeSource;
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
		RTFRandomState randomState,
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
		RTFRandomState randomState
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

	/** Biome resolver owned by this generator root, independent of third-party Mixin ordering. */
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
			// Compilation itself queries the realized pre-plan graph.
			return super.getBiomeGenerationSettings(biome);
		}
		return current.current().biomeDecorationPlan().generationSettings(biome);
	}

	/**
	 * Returns the realized selected-graph settings beneath the plan overlay.
	 *
	 * <p>Tag refresh recompiles modifier operations against this stable base. Reading through
	 * {@link #getBiomeGenerationSettings(Holder)} during a refresh would feed the previous plan
	 * back into the compiler and apply every modifier a second time.</p>
	 */
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
		this.requireStage(WorldgenFacet.SURFACE);
		super.buildSurface(region, structureManager, randomState, chunk);
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
		Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.StructureSet>> allowed =
			plan.sets().stream().map(Holder.Reference::key).collect(Collectors.toUnmodifiableSet());
		structureState.possibleStructureSets().forEach(holder -> {
			if (holder.unwrapKey().map(allowed::contains).orElse(false)) {
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
		raccoonman.reterraforged.RTFCommon.LOGGER.info("Dynamic ore contract inventory: {}", ores.summary());
		ores.failures().forEach(failure -> raccoonman.reterraforged.RTFCommon.LOGGER.warn(
			"Dynamic ore contract inspection failure: {}", failure
		));
	}

	@Override
	public synchronized void close() {
		WorldgenRuntimeBinding current = this.runtime;
		RTFRandomState closingRandomState = this.randomState;
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
				for (int step = 0; step <= featureSteps; step++) {
					for (Holder<PlacedFeature> feature : plan.placedFeatures().forBiome(biome, step)) {
						builder.addFeature(step, feature);
					}
				}
				settings.put(biome, builder.build());
			}
			return Map.copyOf(settings);
	}
}
