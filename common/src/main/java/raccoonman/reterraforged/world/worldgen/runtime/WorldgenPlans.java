package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import raccoonman.reterraforged.world.worldgen.feature.placement.SurfacePlacementClassifier.Classification;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;

/** Separately typed immutable plans consumed by runtime stages. */
public final class WorldgenPlans {
	private WorldgenPlans() {
	}

	public sealed interface DomainPlan permits BiomeComposition, ProviderSelection,
		SelectionDecoration, SpatialOwnership, SamplerDecoration, DensitySettings, Surface,
		Carvers, PlacedFeatures, Structures {
		PlanDescriptor descriptor();

		default WorldgenFacet facet() {
			return this.descriptor().facet();
		}
	}

	public record BiomeComposition(
		PlanDescriptor descriptor,
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries,
		List<CandidateCompositionStage> stages
	) implements DomainPlan {
		public BiomeComposition(
			PlanDescriptor descriptor,
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries
		) {
			this(descriptor, entries, List.of());
		}

		public BiomeComposition {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.BIOME_COMPOSITION);
			entries = List.copyOf(entries);
			stages = stages.stream()
				.map(stage -> Objects.requireNonNull(stage, "stage"))
				.sorted(Comparator.comparingInt(CandidateCompositionStage::order)
					.thenComparing(stage -> stage.id().toString()))
				.toList();
			Set<ResourceLocation> ids = new HashSet<>();
			if (stages.stream().anyMatch(stage -> !ids.add(stage.id()))) {
				throw new IllegalArgumentException("Candidate-composition stage IDs must be unique");
			}
		}

		public List<Pair<Climate.ParameterPoint, Holder<Biome>>> applyTo(
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> input
		) {
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> current = List.copyOf(input);
			for (CandidateCompositionStage stage : this.stages) {
				current = List.copyOf(Objects.requireNonNull(
					stage.composer().apply(current),
					() -> "Candidate-composition stage " + stage.id() + " returned null"
				));
				if (current.isEmpty()) {
					throw new IllegalStateException(
						"Candidate-composition stage " + stage.id() + " removed every candidate"
					);
				}
			}
			return current;
		}
	}

	public record CandidateCompositionStage(
		ResourceLocation id,
		int order,
		BiomeCandidateComposer composer
	) {
		public CandidateCompositionStage {
			id = Objects.requireNonNull(id, "id");
			if (order < 0) {
				throw new IllegalArgumentException("Candidate-composition order must be non-negative");
			}
			composer = Objects.requireNonNull(composer, "composer");
		}
	}

	@FunctionalInterface
	public interface BiomeCandidateComposer {
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> apply(
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> candidates
		);
	}

	public record ProviderSelection(
		PlanDescriptor descriptor,
		long salt,
		List<ProviderDomain> providers,
		Optional<ResourceLocation> rootCompositionDomain,
		Optional<Climate.ParameterList<Holder<Biome>>> fallback,
		Optional<Holder<Biome>> deferredPlaceholder
	) implements DomainPlan {
		public ProviderSelection {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.PROVIDER_SELECTION);
			providers = providers.stream()
				.map(provider -> Objects.requireNonNull(provider, "provider"))
				.sorted(Comparator.comparingInt(ProviderDomain::registrationOrder))
				.toList();
			rootCompositionDomain = Objects.requireNonNull(rootCompositionDomain, "rootCompositionDomain");
			fallback = Objects.requireNonNull(fallback, "fallback");
			deferredPlaceholder = Objects.requireNonNull(deferredPlaceholder, "deferredPlaceholder");
			long distinctIds = providers.stream().map(ProviderDomain::id).distinct().count();
			if (distinctIds != providers.size()) {
				throw new IllegalArgumentException("Provider domain IDs must be unique");
			}
			Set<Integer> orders = new HashSet<>();
			if (providers.stream().anyMatch(provider -> !orders.add(provider.registrationOrder()))) {
				throw new IllegalArgumentException("Provider registration orders must be unique");
			}
			if (providers.isEmpty() && (rootCompositionDomain.isPresent()
				|| fallback.isPresent() || deferredPlaceholder.isPresent())) {
				throw new IllegalArgumentException("An empty provider plan cannot declare root or fallback state");
			}
			ResourceLocation rootDomain = rootCompositionDomain.orElse(null);
			if (rootDomain != null && providers.stream()
				.noneMatch(provider -> provider.id().equals(rootDomain))) {
				throw new IllegalArgumentException("Root composition domain is not a provider domain");
			}
			if (deferredPlaceholder.isPresent() && fallback.isEmpty()) {
				throw new IllegalArgumentException("A deferred provider value requires an explicit fallback table");
			}
			if (fallback.isPresent() && fallback.get().values().isEmpty()) {
				throw new IllegalArgumentException("A provider fallback table must contain candidates");
			}
			Holder<Biome> deferred = deferredPlaceholder.orElse(null);
			if (deferred != null && fallback.orElseThrow().values().stream()
				.anyMatch(entry -> deferred.equals(entry.getSecond()))) {
				throw new IllegalArgumentException("The provider fallback table cannot contain its deferred placeholder");
			}
		}

		public Optional<ProviderResult> resolve(long cellX, long cellZ, Climate.TargetPoint target) {
			if (this.providers.isEmpty()) {
				return Optional.empty();
			}
			ProviderDomain provider = WeightedRendezvous.select(this.salt, cellX, cellZ, this.providers);
			return Optional.of(this.resolve(provider, target));
		}

		public Optional<ProviderResult> resolve(ResourceLocation domain, Climate.TargetPoint target) {
			ProviderDomain provider = this.providers.stream()
				.filter(candidate -> candidate.id().equals(domain))
				.findFirst()
				.orElse(null);
			return provider == null ? Optional.empty() : Optional.of(this.resolve(provider, target));
		}

		private ProviderResult resolve(ProviderDomain provider, Climate.TargetPoint target) {
			Holder<Biome> selected = provider.candidates().findValue(target);
			boolean deferred = this.deferredPlaceholder.isPresent()
				&& this.deferredPlaceholder.get().equals(selected);
			if (deferred) {
				selected = this.fallback.orElseThrow().findValue(target);
			}
			return new ProviderResult(provider.id(), selected, selected, deferred);
		}
	}

	public record ProviderResult(
		ResourceLocation domain,
		Holder<Biome> baseBiome,
		Holder<Biome> biome,
		boolean usedFallback
	) {
		public ProviderResult {
			domain = Objects.requireNonNull(domain, "domain");
			baseBiome = Objects.requireNonNull(baseBiome, "baseBiome");
			biome = Objects.requireNonNull(biome, "biome");
		}

		public ProviderResult(ResourceLocation domain, Holder<Biome> biome, boolean usedFallback) {
			this(domain, biome, biome, usedFallback);
		}

		public ProviderResult withBiome(Holder<Biome> biome) {
			return new ProviderResult(this.domain, this.baseBiome, biome, this.usedFallback);
		}
	}

	public record ProviderDomain(
		ResourceLocation id,
		double weight,
		Climate.ParameterList<Holder<Biome>> candidates,
		int registrationOrder
	) {
		public ProviderDomain {
			id = Objects.requireNonNull(id, "id");
			if (!Double.isFinite(weight) || weight <= 0.0D) {
				throw new IllegalArgumentException("Provider weight must be finite and positive: " + weight);
			}
			candidates = Objects.requireNonNull(candidates, "candidates");
			if (candidates.values().isEmpty()) {
				throw new IllegalArgumentException("Provider candidate table must not be empty: " + id);
			}
			if (registrationOrder < 0) {
				throw new IllegalArgumentException("Provider registration order must be non-negative");
			}
		}
	}

	public record SelectionDecoration(
		PlanDescriptor descriptor,
		List<SelectionDecoratorStage> stages,
		Set<Holder<Biome>> possibleOutputs
	) implements DomainPlan {
		public SelectionDecoration(
			PlanDescriptor descriptor,
			List<SelectionDecoratorStage> stages
		) {
			this(descriptor, stages, Set.of());
		}

		public SelectionDecoration {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.SELECTION_DECORATION);
			stages = stages.stream().map(stage -> Objects.requireNonNull(stage, "stage")).toList();
			possibleOutputs = Collections.unmodifiableSet(new java.util.LinkedHashSet<>(possibleOutputs));
			Set<ResourceLocation> ids = new HashSet<>();
			if (stages.stream().anyMatch(stage -> !ids.add(stage.id()))) {
				throw new IllegalArgumentException("Selection decorator stage IDs must be unique");
			}
		}

		public List<ResourceLocation> orderedDecorators() {
			return this.stages.stream().map(SelectionDecoratorStage::id).toList();
		}

		public boolean executable() {
			return !this.stages.isEmpty() && this.descriptor.state() != CapabilityState.UNAVAILABLE;
		}

		public SelectionDecoration append(SelectionDecoration next, PlanDescriptor descriptor) {
			Objects.requireNonNull(next, "next");
			List<SelectionDecoratorStage> combined = new java.util.ArrayList<>(this.stages);
			combined.addAll(next.stages);
			Set<Holder<Biome>> outputs = new java.util.LinkedHashSet<>(this.possibleOutputs);
			outputs.addAll(next.possibleOutputs);
			return new SelectionDecoration(descriptor, combined, outputs);
		}

		public Holder<Biome> apply(
			ProviderResult selection,
			SpatialResult spatial,
			Climate.TargetPoint target,
			int quartX,
			int quartY,
			int quartZ,
			Climate.Sampler sampler
		) {
			if (!this.executable()) {
				throw new IllegalStateException("Worldgen selection-decoration pipeline is unavailable");
			}
			ProviderResult current = Objects.requireNonNull(selection, "selection");
			Objects.requireNonNull(spatial, "spatial");
			for (SelectionDecoratorStage stage : this.stages) {
				Holder<Biome> decorated = Objects.requireNonNull(
					stage.decorator().apply(current, spatial, target, quartX, quartY, quartZ, sampler),
					() -> "Selection decorator " + stage.id() + " returned null"
				);
				current = current.withBiome(decorated);
			}
			return current.biome();
		}
	}

	public record SelectionDecoratorStage(
		ResourceLocation id,
		BiomeSelectionDecorator decorator
	) {
		public SelectionDecoratorStage {
			id = Objects.requireNonNull(id, "id");
			decorator = Objects.requireNonNull(decorator, "decorator");
		}
	}

	@FunctionalInterface
	public interface BiomeSelectionDecorator {
		Holder<Biome> apply(
			ProviderResult selection,
			SpatialResult spatial,
			Climate.TargetPoint target,
			int quartX,
			int quartY,
			int quartZ,
			Climate.Sampler sampler
		);
	}

	public record SpatialOwnership(
		PlanDescriptor descriptor,
		Optional<SpatialResolver> resolver
	) implements DomainPlan {
		public SpatialOwnership {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.SPATIAL_OWNERSHIP);
			resolver = Objects.requireNonNull(resolver, "resolver");
		}
	}

	@FunctionalInterface
	public interface SpatialResolver {
		SpatialResult resolve(long cellX, long cellZ);
	}

	public record SpatialResult(ResourceLocation domain, long cellX, long cellZ) {
		public SpatialResult {
			domain = Objects.requireNonNull(domain, "domain");
		}
	}

	public record SamplerDecoration(
		PlanDescriptor descriptor,
		Optional<SamplerDecorator> decorator
	) implements DomainPlan {
		public SamplerDecoration {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.SAMPLER_DECORATION);
			decorator = Objects.requireNonNull(decorator, "decorator");
		}

		public Climate.Sampler decorate(
			WorldgenPlan plan,
			SamplerInputs inputs,
			Climate.Sampler sampler
		) {
			return this.decorator.orElseThrow(() -> new IllegalStateException(
				"Worldgen sampler decoration is unavailable"
			)).apply(plan, inputs, sampler);
		}
	}

	@FunctionalInterface
	public interface SamplerDecorator {
		Climate.Sampler apply(WorldgenPlan plan, SamplerInputs inputs, Climate.Sampler sampler);
	}

	public record SamplerInputs(Preset preset, GeneratorContext generatorContext) {
		public SamplerInputs {
			preset = Objects.requireNonNull(preset, "preset");
			generatorContext = Objects.requireNonNull(generatorContext, "generatorContext");
		}
	}

	public record DensitySettings(
		PlanDescriptor descriptor,
		Optional<Holder<NoiseGeneratorSettings>> settings
	) implements DomainPlan {
		public DensitySettings {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.DENSITY_SETTINGS);
			settings = Objects.requireNonNull(settings, "settings");
		}
	}

	public record Surface(
		PlanDescriptor descriptor,
		Optional<net.minecraft.world.level.levelgen.SurfaceRules.RuleSource> root
	) implements DomainPlan {
		public Surface {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.SURFACE);
			root = Objects.requireNonNull(root, "root");
		}
	}

	public record Carvers(
		PlanDescriptor descriptor,
		List<CarverPipeline> pipelines,
		Map<ResourceKey<Biome>, Map<GenerationStep.Carving, List<Holder<ConfiguredWorldCarver<?>>>>> byBiome
	) implements DomainPlan {
		public Carvers(PlanDescriptor descriptor, List<CarverPipeline> pipelines) {
			this(descriptor, pipelines, indexCarvers(pipelines));
		}

		public Carvers {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.CARVERS);
			pipelines = List.copyOf(pipelines);
			byBiome = immutableNestedMap(byBiome);
		}

		public List<Holder<ConfiguredWorldCarver<?>>> forBiome(
			Holder<Biome> biome,
			GenerationStep.Carving step
		) {
			ResourceKey<Biome> key = biome.unwrapKey().orElseThrow(
				() -> new IllegalStateException("Carver stage selected an unkeyed biome")
			);
			return this.forBiome(key, step);
		}

		public List<Holder<ConfiguredWorldCarver<?>>> forBiome(
			ResourceKey<Biome> biome,
			GenerationStep.Carving step
		) {
			return this.byBiome.getOrDefault(biome, Map.of()).getOrDefault(step, List.of());
		}
	}

	public record CarverPipeline(
		ResourceKey<Biome> biome,
		GenerationStep.Carving step,
		int index,
		Holder<ConfiguredWorldCarver<?>> carver
	) {
		public CarverPipeline {
			biome = Objects.requireNonNull(biome, "biome");
			step = Objects.requireNonNull(step, "step");
			if (index < 0) {
				throw new IllegalArgumentException("Carver index must be non-negative");
			}
			carver = Objects.requireNonNull(carver, "carver");
		}
	}

	public record PlacedFeatures(
		PlanDescriptor descriptor,
		List<PlacedFeaturePipeline> pipelines,
		List<FeatureSorter.StepFeatureData> steps,
		Map<PlacedFeature, Classification> surfaceClassifications,
		DynamicOrePlan ores,
		Map<ResourceKey<Biome>, Map<Integer, List<Holder<PlacedFeature>>>> byBiome
	) implements DomainPlan {
		public PlacedFeatures(
			PlanDescriptor descriptor,
			List<PlacedFeaturePipeline> pipelines,
			List<FeatureSorter.StepFeatureData> steps,
			Map<PlacedFeature, Classification> surfaceClassifications,
			DynamicOrePlan ores
		) {
			this(descriptor, pipelines, steps, surfaceClassifications, ores, indexPlacedFeatures(pipelines));
		}

		public PlacedFeatures {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.PLACED_FEATURES);
			pipelines = List.copyOf(pipelines);
			steps = List.copyOf(steps);
			surfaceClassifications = Collections.unmodifiableMap(
				new IdentityHashMap<>(surfaceClassifications)
			);
			ores = Objects.requireNonNull(ores, "ores");
			byBiome = immutableNestedMap(byBiome);
		}

		public List<Holder<PlacedFeature>> forBiome(Holder<Biome> biome, int generationStep) {
			ResourceKey<Biome> key = biome.unwrapKey().orElseThrow(
				() -> new IllegalStateException("Feature stage selected an unkeyed biome")
			);
			return this.forBiome(key, generationStep);
		}

		public List<Holder<PlacedFeature>> forBiome(ResourceKey<Biome> biome, int generationStep) {
			return this.byBiome.getOrDefault(biome, Map.of()).getOrDefault(generationStep, List.of());
		}

		public Classification surfaceClassification(PlacedFeature feature) {
			return this.surfaceClassifications.getOrDefault(feature, Classification.rejected());
		}
	}

	public record PlacedFeaturePipeline(
		ResourceKey<Biome> biome,
		int generationStep,
		int index,
		Holder<PlacedFeature> placedFeature,
		Holder<ConfiguredFeature<?, ?>> configuredFeature,
		List<PlacementModifier> modifiers,
		List<ConfiguredFeature<?, ?>> configuredGraph
	) {
		public PlacedFeaturePipeline {
			biome = Objects.requireNonNull(biome, "biome");
			if (generationStep < 0 || index < 0) {
				throw new IllegalArgumentException("Feature step and index must be non-negative");
			}
			placedFeature = Objects.requireNonNull(placedFeature, "placedFeature");
			configuredFeature = Objects.requireNonNull(configuredFeature, "configuredFeature");
			modifiers = List.copyOf(modifiers);
			configuredGraph = List.copyOf(configuredGraph);
		}
	}

	public record Structures(
		PlanDescriptor descriptor,
		List<Holder.Reference<Structure>> structures,
		List<Holder.Reference<StructureSet>> sets,
		List<Holder.Reference<StructureTemplatePool>> pools,
		List<Holder.Reference<StructureProcessorList>> processors
	) implements DomainPlan {
		public Structures {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.STRUCTURES);
			structures = List.copyOf(structures);
			sets = List.copyOf(sets);
			pools = List.copyOf(pools);
			processors = List.copyOf(processors);
		}
	}

	private static Map<ResourceKey<Biome>, Map<GenerationStep.Carving, List<Holder<ConfiguredWorldCarver<?>>>>> indexCarvers(
		List<CarverPipeline> pipelines
	) {
		Map<ResourceKey<Biome>, Map<GenerationStep.Carving, List<CarverPipeline>>> grouped = new LinkedHashMap<>();
		for (CarverPipeline pipeline : List.copyOf(pipelines)) {
			grouped.computeIfAbsent(pipeline.biome(), ignored -> new java.util.EnumMap<>(GenerationStep.Carving.class))
				.computeIfAbsent(pipeline.step(), ignored -> new java.util.ArrayList<>())
				.add(pipeline);
		}
		Map<ResourceKey<Biome>, Map<GenerationStep.Carving, List<Holder<ConfiguredWorldCarver<?>>>>> indexed =
			new LinkedHashMap<>();
		grouped.forEach((biome, steps) -> {
			Map<GenerationStep.Carving, List<Holder<ConfiguredWorldCarver<?>>>> values =
				new java.util.EnumMap<>(GenerationStep.Carving.class);
			steps.forEach((step, occurrences) -> {
				occurrences.sort(Comparator.comparingInt(CarverPipeline::index));
				validateContiguousIndices(
					"carver", biome, step.ordinal(), occurrences.stream().map(CarverPipeline::index).toList()
				);
				values.put(step, occurrences.stream().map(CarverPipeline::carver).toList());
			});
			indexed.put(biome, Map.copyOf(values));
		});
		return Map.copyOf(indexed);
	}

	private static Map<ResourceKey<Biome>, Map<Integer, List<Holder<PlacedFeature>>>> indexPlacedFeatures(
		List<PlacedFeaturePipeline> pipelines
	) {
		Map<ResourceKey<Biome>, Map<Integer, List<PlacedFeaturePipeline>>> grouped = new LinkedHashMap<>();
		for (PlacedFeaturePipeline pipeline : List.copyOf(pipelines)) {
			grouped.computeIfAbsent(pipeline.biome(), ignored -> new java.util.TreeMap<>())
				.computeIfAbsent(pipeline.generationStep(), ignored -> new java.util.ArrayList<>())
				.add(pipeline);
		}
		Map<ResourceKey<Biome>, Map<Integer, List<Holder<PlacedFeature>>>> indexed = new LinkedHashMap<>();
		grouped.forEach((biome, steps) -> {
			Map<Integer, List<Holder<PlacedFeature>>> values = new LinkedHashMap<>();
			steps.forEach((step, occurrences) -> {
				occurrences.sort(Comparator.comparingInt(PlacedFeaturePipeline::index));
				validateContiguousIndices(
					"placed feature", biome, step, occurrences.stream().map(PlacedFeaturePipeline::index).toList()
				);
				values.put(step, occurrences.stream().map(PlacedFeaturePipeline::placedFeature).toList());
			});
			indexed.put(biome, Map.copyOf(values));
		});
		return Map.copyOf(indexed);
	}

	private static void validateContiguousIndices(
		String domain,
		ResourceKey<Biome> biome,
		int step,
		List<Integer> indices
	) {
		for (int expected = 0; expected < indices.size(); expected++) {
			if (indices.get(expected) != expected) {
				throw new IllegalArgumentException(
					"Non-contiguous " + domain + " occurrence index for " + biome.location()
						+ " step " + step + ": expected " + expected + ", got " + indices.get(expected)
				);
			}
		}
	}

	private static <K1, K2, V> Map<K1, Map<K2, List<V>>> immutableNestedMap(
		Map<K1, Map<K2, List<V>>> input
	) {
		Map<K1, Map<K2, List<V>>> copied = new LinkedHashMap<>();
		Objects.requireNonNull(input, "indexed plan").forEach((outer, inner) -> {
			Map<K2, List<V>> values = new LinkedHashMap<>();
			Objects.requireNonNull(inner, "indexed plan domain").forEach((key, list) ->
				values.put(Objects.requireNonNull(key, "indexed plan key"), List.copyOf(list))
			);
			copied.put(Objects.requireNonNull(outer, "indexed plan biome"), Map.copyOf(values));
		});
		return Map.copyOf(copied);
	}

	private static PlanDescriptor validateDescriptor(PlanDescriptor descriptor, WorldgenFacet expected) {
		Objects.requireNonNull(descriptor, "descriptor");
		if (descriptor.facet() != expected) {
			throw new IllegalArgumentException("Expected plan facet " + expected + ", got " + descriptor.facet());
		}
		return descriptor;
	}
}
