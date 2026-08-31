package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter.StepFeatureData;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;

/** FTF-owned generator root. Stage behavior is initially inherited exactly from vanilla noise generation. */
public final class TerraForgedChunkGenerator extends NoiseBasedChunkGenerator implements AutoCloseable {
	public static final MapCodec<TerraForgedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(TerraForgedChunkGenerator::acquisitionBiomeSource),
		NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(TerraForgedChunkGenerator::generatorSettings)
	).apply(instance, instance.stable(TerraForgedChunkGenerator::new)));

	private final BiomeSource acquisitionBiomeSource;
	private volatile WorldgenRuntimeBinding runtime;

	public TerraForgedChunkGenerator(BiomeSource biomeSource, net.minecraft.core.Holder<NoiseGeneratorSettings> settings) {
		this(biomeSource, settings, new UnifiedBiomeSource(biomeSource));
	}

	private TerraForgedChunkGenerator(
		BiomeSource biomeSource,
		net.minecraft.core.Holder<NoiseGeneratorSettings> settings,
		UnifiedBiomeSource unifiedBiomeSource
	) {
		super(unifiedBiomeSource, settings);
		this.acquisitionBiomeSource = biomeSource;
		unifiedBiomeSource.bind(this);
	}

	public BiomeSource acquisitionBiomeSource() {
		return this.acquisitionBiomeSource;
	}

	Optional<Set<Holder<Biome>>> possibleRuntimeBiomes() {
		WorldgenRuntimeBinding current = this.runtime;
		return current == null ? Optional.empty() : Optional.of(current.current().possibleBiomes());
	}

	@Override
	protected MapCodec<? extends TerraForgedChunkGenerator> codec() {
		return CODEC;
	}

	public synchronized WorldgenPlan initializeEpoch(WorldgenEpoch epoch, RTFRandomState randomState) throws Exception {
		Objects.requireNonNull(epoch, "epoch");
		Objects.requireNonNull(randomState, "randomState");
		WorldgenRuntimeBinding current = this.runtime;
		if (current != null) {
			if (current.epoch().id().equals(epoch.id())) {
				return current.plan();
			}
			throw new IllegalStateException(
				"Generator root is already owned by worldgen epoch " + current.epoch().id()
			);
		}

		randomState.initialize(epoch);
		List<WorldgenCapabilityProvider> providers = WorldgenCapabilityDiscovery.discover(
			TerraForgedChunkGenerator.class.getClassLoader()
		);
		WorldgenPlan compiled = MinecraftWorldgenPlanCompiler.compile(epoch, providers);
		WorldgenBiomeSelection.requireExecutablePlan(compiled);
		WorldgenRuntimeBinding prepared = WorldgenRuntimeBinding.create(
			epoch, compiled, composeGenerationSettings(compiled)
		);
		randomState.bindPlan(prepared);
		this.runtime = prepared;
		logOrePlan(compiled);
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

	public synchronized WorldgenPlan refreshTags(TagEpoch replacement, RTFRandomState randomState) throws Exception {
		WorldgenRuntimeBinding binding = Objects.requireNonNull(this.runtime, "Generator root has no active epoch");
		WorldgenRuntimeBinding.State current = binding.current();
		WorldgenEpoch currentEpoch = current.epoch();
		if (replacement.sequence() <= currentEpoch.tagEpoch().sequence()) {
			throw new IllegalArgumentException("Tag epoch refresh must advance monotonically");
		}
		WorldgenEpoch refreshedEpoch = currentEpoch.withTagEpoch(replacement);
		List<WorldgenCapabilityProvider> providers = WorldgenCapabilityDiscovery.discover(
			TerraForgedChunkGenerator.class.getClassLoader()
		);
		WorldgenPlan refreshedPlan = MinecraftWorldgenPlanCompiler.compile(refreshedEpoch, providers);
		WorldgenBiomeSelection.requireExecutablePlan(refreshedPlan);
		randomState.preparePlanRebind(refreshedEpoch, refreshedPlan);
		binding.replace(current, refreshedEpoch, refreshedPlan, composeGenerationSettings(refreshedPlan));
		logOrePlan(refreshedPlan);
		return refreshedPlan;
	}

	public synchronized WorldgenPlan refreshContributions(RTFRandomState randomState) throws Exception {
		WorldgenRuntimeBinding binding = Objects.requireNonNull(this.runtime, "Generator root has no active epoch");
		WorldgenRuntimeBinding.State current = binding.current();
		WorldgenEpoch refreshedEpoch = current.epoch().nextContributionSequence();
		List<WorldgenCapabilityProvider> providers = WorldgenCapabilityDiscovery.discover(
			TerraForgedChunkGenerator.class.getClassLoader()
		);
		WorldgenPlan refreshedPlan = MinecraftWorldgenPlanCompiler.compile(refreshedEpoch, providers);
		WorldgenBiomeSelection.requireExecutablePlan(refreshedPlan);
		randomState.preparePlanRebind(refreshedEpoch, refreshedPlan);
		binding.replace(current, refreshedEpoch, refreshedPlan, composeGenerationSettings(refreshedPlan));
		logOrePlan(refreshedPlan);
		return refreshedPlan;
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
		WorldgenPlan current = this.requireBiomeSelection();
		return WorldgenBiomeSelection.resolve(current, quartX, quartY, quartZ, sampler);
	}

	@Override
	public BiomeGenerationSettings getBiomeGenerationSettings(Holder<Biome> biome) {
		WorldgenRuntimeBinding current = this.runtime;
		if (current == null) {
			// Compilation itself queries the realized pre-plan graph.
			return super.getBiomeGenerationSettings(biome);
		}
		WorldgenRuntimeBinding.State state = current.current();
		return biome.unwrapKey()
			.map(state.generationSettings()::get)
			.filter(Objects::nonNull)
			.orElseGet(() -> super.getBiomeGenerationSettings(biome));
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
		return super.fillFromNoise(blender, randomState, structureManager, chunk);
	}

	@Override
	public void buildSurface(
		WorldGenRegion region,
		StructureManager structureManager,
		RandomState randomState,
		ChunkAccess chunk
	) {
		WorldgenPlan current = this.requireStage(WorldgenFacet.SURFACE);
		if (SharedConstants.debugVoidTerrain(chunk.getPos())) {
			return;
		}
		NoiseGeneratorSettings settings = current.densitySettings().settings().orElseThrow(
			() -> new IllegalStateException("FTF surface stage has no coupled noise-settings root")
		).value();
		var surfaceRule = current.surface().root().orElseThrow(
			() -> new IllegalStateException("FTF surface stage has no typed surface-rule root")
		);
		WorldGenerationContext context = new WorldGenerationContext(this, region);
		NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
			value -> this.createNoiseChunk(value, structureManager, Blender.of(region), randomState)
		);
		randomState.surfaceSystem().buildSurface(
			randomState,
			region.getBiomeManager(),
			region.registryAccess().registryOrThrow(Registries.BIOME),
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
		WorldgenPlan current = this.requireStage(WorldgenFacet.CARVERS);
		this.requireStage(WorldgenFacet.SURFACE);
		WorldgenPlans.Carvers plan = current.carvers();
		BiomeManager stageBiomes = biomeManager.withDifferentSource(
			(x, y, z) -> this.resolveBiome(x, y, z, randomState.sampler())
		);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		ChunkPos center = chunk.getPos();
		NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
			value -> this.createNoiseChunk(value, structureManager, Blender.of(region), randomState)
		);
		Aquifer aquifer = noiseChunk.aquifer();
		CarvingContext context = new CarvingContext(
			this,
			region.registryAccess(),
			chunk.getHeightAccessorForGeneration(),
			noiseChunk,
			randomState,
			current.surface().root().orElseThrow(
				() -> new IllegalStateException("FTF carver stage has no coupled surface-rule root")
			)
		);
		CarvingMask mask = ((ProtoChunk) chunk).getOrCreateCarvingMask(step);

		for (int offsetX = -8; offsetX <= 8; offsetX++) {
			for (int offsetZ = -8; offsetZ <= 8; offsetZ++) {
				ChunkPos sourcePos = new ChunkPos(center.x + offsetX, center.z + offsetZ);
				ChunkAccess sourceChunk = region.getChunk(sourcePos.x, sourcePos.z);
				Holder<Biome> sourceBiome = this.resolveBiome(
					QuartPos.fromBlock(sourcePos.getMinBlockX()),
					0,
					QuartPos.fromBlock(sourcePos.getMinBlockZ()),
					randomState.sampler()
				);
				List<Holder<ConfiguredWorldCarver<?>>> carvers = plan.forBiome(sourceBiome, step);
				// Preserve vanilla's carver-biome cache side effect while the plan remains execution authority.
				sourceChunk.carverBiome(() -> this.getBiomeGenerationSettings(sourceBiome));
				for (int index = 0; index < carvers.size(); index++) {
					ConfiguredWorldCarver<?> carver = carvers.get(index).value();
					random.setLargeFeatureSeed(seed + index, sourcePos.x, sourcePos.z);
					if (carver.isStartChunk(random)) {
						carver.carve(context, chunk, stageBiomes::getBiome, random, aquifer, sourcePos, mask);
					}
				}
			}
		}
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		WorldgenPlan current = this.requireStage(WorldgenFacet.PLACED_FEATURES);
		WorldgenPlans.PlacedFeatures plan = current.placedFeatures();
		WorldgenPlans.Structures structurePlan = this.requireStage(WorldgenFacet.STRUCTURES).structures();
		ChunkPos chunkPos = chunk.getPos();
		if (SharedConstants.debugVoidTerrain(chunkPos)) {
			return;
		}
		SectionPos sectionPos = SectionPos.of(chunkPos, level.getMinSection());
		BlockPos origin = sectionPos.origin();
		Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
		Map<Integer, List<Structure>> structuresByStep = structurePlan.structures().stream()
			.map(Holder.Reference::value)
			.collect(Collectors.groupingBy(structure -> structure.step().ordinal()));
		List<StepFeatureData> steps = plan.steps();
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
		long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
		Set<Holder<Biome>> biomes = new ObjectArraySet<>();
		ChunkPos.rangeClosed(sectionPos.chunk(), 1).forEach(pos -> {
			ChunkAccess nearby = level.getChunk(pos.x, pos.z);
			for (LevelChunkSection section : nearby.getSections()) {
				section.getBiomes().getAll(biomes::add);
			}
		});
		biomes.retainAll(this.getBiomeSource().possibleBiomes());

		try {
			Registry<PlacedFeature> placedFeatures = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
			int stepCount = Math.max(Decoration.values().length, steps.size());
			for (int step = 0; step < stepCount; step++) {
				int structureIndex = 0;
				if (structureManager.shouldGenerateStructures()) {
					for (Structure structure : structuresByStep.getOrDefault(step, Collections.emptyList())) {
						random.setFeatureSeed(decorationSeed, structureIndex, step);
						Supplier<String> description = () -> structures.getResourceKey(structure)
							.map(Object::toString).orElseGet(structure::toString);
						try {
							level.setCurrentlyGenerating(description);
							structureManager.startsForStructure(sectionPos, structure).forEach(start ->
								start.placeInChunk(
									level, structureManager, this, random, writableArea(chunk), chunkPos
								)
							);
						} catch (Exception failure) {
							CrashReport report = CrashReport.forThrowable(failure, "Feature placement");
							report.addCategory("Feature").setDetail("Description", description::get);
							throw new ReportedException(report);
						}
						structureIndex++;
					}
				}

				if (step < steps.size()) {
					IntSet indices = new IntArraySet();
					StepFeatureData schedule = steps.get(step);
					for (Holder<Biome> biome : biomes) {
						for (Holder<PlacedFeature> feature : plan.forBiome(biome, step)) {
							indices.add(schedule.indexMapping().applyAsInt(feature.value()));
						}
					}
					int[] ordered = indices.toIntArray();
					Arrays.sort(ordered);
					for (int featureIndex : ordered) {
						PlacedFeature feature = schedule.features().get(featureIndex);
						Supplier<String> description = () -> placedFeatures.getResourceKey(feature)
							.map(Object::toString).orElseGet(feature::toString);
						random.setFeatureSeed(decorationSeed, featureIndex, step);
						try {
							level.setCurrentlyGenerating(description);
							feature.placeWithBiomeCheck(level, this, random, origin);
						} catch (Exception failure) {
							CrashReport report = CrashReport.forThrowable(failure, "Feature placement");
							report.addCategory("Feature").setDetail("Description", description::get);
							throw new ReportedException(report);
						}
					}
				}
			}
			level.setCurrentlyGenerating(null);
		} catch (Exception failure) {
			CrashReport report = CrashReport.forThrowable(failure, "Biome decoration");
			report.addCategory("Generation")
				.setDetail("CenterX", chunkPos.x)
				.setDetail("CenterZ", chunkPos.z)
				.setDetail("Decoration Seed", decorationSeed);
			throw new ReportedException(report);
		}
	}

	private static BoundingBox writableArea(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int minY = chunk.getHeightAccessorForGeneration().getMinBuildHeight() + 1;
		int maxY = chunk.getHeightAccessorForGeneration().getMaxBuildHeight() - 1;
		return new BoundingBox(
			pos.getMinBlockX(), minY, pos.getMinBlockZ(),
			pos.getMinBlockX() + 15, maxY, pos.getMinBlockZ() + 15
		);
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
		ChunkPos chunkPos = chunk.getPos();
		SectionPos sectionPos = SectionPos.bottomOf(chunk);
		RandomState randomState = structureState.randomState();
		structureState.possibleStructureSets().forEach(holder -> {
			if (plan.sets().stream().noneMatch(candidate -> candidate.key().equals(holder.unwrapKey().orElse(null)))) {
				throw new IllegalStateException("Structure state selected a set outside the compiled plan: " + holder);
			}
			StructurePlacement placement = holder.value().placement();
			List<StructureSelectionEntry> entries = holder.value().structures();
			for (StructureSelectionEntry entry : entries) {
				StructureStart start = structureManager.getStartForStructure(
					sectionPos, entry.structure().value(), chunk
				);
				if (start != null && start.isValid()) {
					return;
				}
			}
			if (!placement.isStructureChunk(structureState, chunkPos.x, chunkPos.z)) {
				return;
			}
			if (entries.size() == 1) {
				this.generateStructure(
					entries.getFirst(), structureManager, registries, randomState, templates,
					structureState.getLevelSeed(), chunk, chunkPos, sectionPos
				);
				return;
			}

			ArrayList<StructureSelectionEntry> remaining = new ArrayList<>(entries);
			WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
			random.setLargeFeatureSeed(structureState.getLevelSeed(), chunkPos.x, chunkPos.z);
			int totalWeight = remaining.stream().mapToInt(StructureSelectionEntry::weight).sum();
			while (!remaining.isEmpty()) {
				int draw = random.nextInt(totalWeight);
				int selectedIndex = 0;
				for (StructureSelectionEntry entry : remaining) {
					draw -= entry.weight();
					if (draw < 0) {
						break;
					}
					selectedIndex++;
				}
				StructureSelectionEntry selected = remaining.get(selectedIndex);
				if (this.generateStructure(
					selected, structureManager, registries, randomState, templates,
					structureState.getLevelSeed(), chunk, chunkPos, sectionPos
				)) {
					return;
				}
				remaining.remove(selectedIndex);
				totalWeight -= selected.weight();
			}
		});
	}

	private boolean generateStructure(
		StructureSelectionEntry selection,
		StructureManager structureManager,
		RegistryAccess registries,
		RandomState randomState,
		StructureTemplateManager templates,
		long seed,
		ChunkAccess chunk,
		ChunkPos chunkPos,
		SectionPos sectionPos
	) {
		Structure structure = selection.structure().value();
		StructureStart previous = structureManager.getStartForStructure(sectionPos, structure, chunk);
		int references = previous != null ? previous.getReferences() : 0;
		HolderSet<Biome> allowedBiomes = structure.biomes();
		Predicate<Holder<Biome>> biomePredicate = allowedBiomes::contains;
		StructureStart start = structure.generate(
			registries,
			this,
			this.getBiomeSource(),
			randomState,
			templates,
			seed,
			chunkPos,
			references,
			chunk,
			biomePredicate
		);
		if (!start.isValid()) {
			return false;
		}
		structureManager.setStartForStructure(sectionPos, structure, start, chunk);
		return true;
	}

	private WorldgenPlan requireStage(WorldgenFacet facet) {
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
		return current;
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
	public synchronized void close() throws Exception {
		WorldgenRuntimeBinding current = this.runtime;
		this.runtime = null;
		if (current != null) {
			current.close();
		}
	}

	private static Map<net.minecraft.resources.ResourceKey<Biome>, BiomeGenerationSettings>
	composeGenerationSettings(WorldgenPlan plan) {
			TreeSet<net.minecraft.resources.ResourceKey<Biome>> biomes = new TreeSet<>(
				java.util.Comparator.comparing(key -> key.location().toString())
			);
			plan.carvers().pipelines().forEach(pipeline -> biomes.add(pipeline.biome()));
			plan.placedFeatures().pipelines().forEach(pipeline -> biomes.add(pipeline.biome()));
			Map<net.minecraft.resources.ResourceKey<Biome>, BiomeGenerationSettings> settings =
				new java.util.LinkedHashMap<>();
			for (net.minecraft.resources.ResourceKey<Biome> biome : biomes) {
				BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
				for (GenerationStep.Carving step : GenerationStep.Carving.values()) {
					plan.carvers().forBiome(biome, step).forEach(carver -> builder.addCarver(step, carver));
				}
				int featureSteps = plan.placedFeatures().pipelines().stream()
					.filter(pipeline -> pipeline.biome().equals(biome))
					.mapToInt(WorldgenPlans.PlacedFeaturePipeline::generationStep)
					.max()
					.orElse(-1);
				for (int step = 0; step <= featureSteps; step++) {
					for (Holder<PlacedFeature> feature : plan.placedFeatures().forBiome(biome, step)) {
						builder.addFeature(step, feature);
					}
				}
				settings.put(biome, builder.build());
			}
			return settings;
	}
}
