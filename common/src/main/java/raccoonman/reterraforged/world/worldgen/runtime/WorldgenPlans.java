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
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import raccoonman.reterraforged.world.worldgen.feature.placement.SurfacePlacementClassifier.Classification;
import raccoonman.reterraforged.world.worldgen.structure.rule.StructureRule;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.tags.StructureTags;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.ClimateQueryPolicy;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;

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
		List<CandidateCompositionStage> stages,
		Optional<BiomeCandidateRoot> candidateRoot
	) implements DomainPlan {
		public BiomeComposition(
			PlanDescriptor descriptor,
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries
		) {
			this(descriptor, entries, List.of(), Optional.empty());
		}

		public BiomeComposition(
			PlanDescriptor descriptor,
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries,
			List<CandidateCompositionStage> stages
		) {
			this(descriptor, entries, stages, Optional.empty());
		}

		public BiomeComposition {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.BIOME_COMPOSITION);
			entries = List.copyOf(entries);
			candidateRoot = Objects.requireNonNull(candidateRoot, "candidateRoot");
			if (candidateRoot.isPresent()
				&& !candidateRoot.orElseThrow().entries().equals(entries)) {
				throw new IllegalArgumentException(
					"Biome composition entries and retained candidate root disagree"
				);
			}
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

		public BiomeCandidateRoot applyTo(BiomeCandidateRoot input) {
			Objects.requireNonNull(input, "input");
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> transformed = this.applyTo(input.entries());
			return transformed.equals(input.entries())
				? input
				: BiomeCandidateRoot.fromEntries(transformed);
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

	public static final class ProviderSelection implements DomainPlan {
		private final PlanDescriptor descriptor;
		private final long salt;
		private final List<ProviderDomain> providers;
		private final Optional<ResourceLocation> rootCompositionDomain;
		private final Optional<Climate.ParameterList<Holder<Biome>>> fallback;
		private final Optional<Holder<Biome>> deferredPlaceholder;
		private final Optional<BiomeSourcePlanInput> directInput;
		private final Map<ResourceLocation, ProviderDomain> providersById;
		private final WeightedRendezvous.Selector selector;

		public ProviderSelection(
			PlanDescriptor descriptor,
			long salt,
			List<ProviderDomain> providers,
			Optional<ResourceLocation> rootCompositionDomain,
			Optional<Climate.ParameterList<Holder<Biome>>> fallback,
			Optional<Holder<Biome>> deferredPlaceholder
		) {
			this(
				descriptor, salt, providers, rootCompositionDomain, fallback, deferredPlaceholder,
				Optional.empty()
			);
		}

		public ProviderSelection(
			PlanDescriptor descriptor,
			long salt,
			List<ProviderDomain> providers,
			Optional<ResourceLocation> rootCompositionDomain,
			Optional<Climate.ParameterList<Holder<Biome>>> fallback,
			Optional<Holder<Biome>> deferredPlaceholder,
			Optional<BiomeSourcePlanInput> directInput
		) {
			this.descriptor = validateDescriptor(descriptor, WorldgenFacet.PROVIDER_SELECTION);
			this.salt = salt;
			this.providers = providers.stream()
				.map(provider -> Objects.requireNonNull(provider, "provider"))
				.sorted(Comparator.comparingInt(ProviderDomain::registrationOrder))
				.toList();
			this.rootCompositionDomain = Objects.requireNonNull(rootCompositionDomain, "rootCompositionDomain");
			this.fallback = Objects.requireNonNull(fallback, "fallback");
			this.deferredPlaceholder = Objects.requireNonNull(deferredPlaceholder, "deferredPlaceholder");
			this.directInput = Objects.requireNonNull(directInput, "directInput");
			LinkedHashMap<ResourceLocation, ProviderDomain> byId = new LinkedHashMap<>();
			for (ProviderDomain provider : this.providers) {
				if (byId.putIfAbsent(provider.id(), provider) != null) {
					throw new IllegalArgumentException("Provider domain IDs must be unique");
				}
			}
			this.providersById = Collections.unmodifiableMap(byId);
			Set<Integer> orders = new HashSet<>();
			if (this.providers.stream().anyMatch(provider -> !orders.add(provider.registrationOrder()))) {
				throw new IllegalArgumentException("Provider registration orders must be unique");
			}
			if (this.providers.isEmpty() && (this.rootCompositionDomain.isPresent()
				|| this.fallback.isPresent() || this.deferredPlaceholder.isPresent())) {
				throw new IllegalArgumentException("An empty provider plan cannot declare root or fallback state");
			}
			if (this.directInput.isPresent() && (!this.providers.isEmpty()
				|| this.rootCompositionDomain.isPresent() || this.fallback.isPresent()
				|| this.deferredPlaceholder.isPresent())) {
				throw new IllegalArgumentException(
					"A direct custom source root cannot share candidate-provider state"
				);
			}
			ResourceLocation rootDomain = this.rootCompositionDomain.orElse(null);
			if (rootDomain != null && !this.providersById.containsKey(rootDomain)) {
				throw new IllegalArgumentException("Root composition domain is not a provider domain");
			}
			if (this.deferredPlaceholder.isPresent() && this.fallback.isEmpty()) {
				throw new IllegalArgumentException("A deferred provider value requires an explicit fallback table");
			}
			if (this.fallback.isPresent() && this.fallback.get().values().isEmpty()) {
				throw new IllegalArgumentException("A provider fallback table must contain candidates");
			}
			Holder<Biome> deferred = this.deferredPlaceholder.orElse(null);
			if (deferred != null && this.fallback.orElseThrow().values().stream()
				.anyMatch(entry -> deferred.equals(entry.getSecond()))) {
				throw new IllegalArgumentException("The provider fallback table cannot contain its deferred placeholder");
			}
			this.selector = this.providers.isEmpty() ? null : new WeightedRendezvous.Selector(salt, this.providers);
		}

		@Override
		public PlanDescriptor descriptor() {
			return this.descriptor;
		}

		public long salt() {
			return this.salt;
		}

		public List<ProviderDomain> providers() {
			return this.providers;
		}

		public Optional<ResourceLocation> rootCompositionDomain() {
			return this.rootCompositionDomain;
		}

		public Optional<Climate.ParameterList<Holder<Biome>>> fallback() {
			return this.fallback;
		}

		public Optional<Holder<Biome>> deferredPlaceholder() {
			return this.deferredPlaceholder;
		}

		public Optional<BiomeSourcePlanInput> directInput() {
			return this.directInput;
		}

		public Optional<ProviderResult> resolve(long cellX, long cellZ, Climate.TargetPoint target) {
			if (this.providers.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(this.resolveProvider(this.selector.select(cellX, cellZ), target));
		}

		public Optional<ProviderResult> resolve(ResourceLocation domain, Climate.TargetPoint target) {
			ProviderDomain provider = this.providersById.get(domain);
			return provider == null ? Optional.empty() : Optional.of(this.resolveProvider(provider, target));
		}

		public ProviderResult resolveRequired(ResourceLocation domain, Climate.TargetPoint target) {
			ProviderDomain provider = this.providersById.get(domain);
			if (provider == null) {
				throw new IllegalArgumentException("Unknown provider domain " + domain);
			}
			return this.resolveProvider(provider, target);
		}

		public ResourceLocation selectDomain(long cellX, long cellZ) {
			if (this.selector == null) {
				throw new IllegalStateException("Cannot assign a provider from an empty plan");
			}
			return this.selector.select(cellX, cellZ).id();
		}

		private ProviderResult resolveProvider(ProviderDomain provider, Climate.TargetPoint target) {
			Climate.ParameterList<Holder<Biome>> candidates = provider.candidates();
			Holder<Biome> selected = candidates.findValue(target);
			boolean deferred = this.deferredPlaceholder.isPresent()
				&& this.deferredPlaceholder.get().equals(selected);
			if (deferred) {
				candidates = this.fallback.orElseThrow();
				selected = candidates.findValue(target);
			}
			return new ProviderResult(provider.id(), selected, selected, deferred, candidates, target);
		}

		@Override
		public String toString() {
			return "ProviderSelection[descriptor=" + this.descriptor + ", salt=" + this.salt
				+ ", providers=" + this.providers + ", rootCompositionDomain=" + this.rootCompositionDomain
				+ ", fallback=" + this.fallback + ", deferredPlaceholder=" + this.deferredPlaceholder
				+ ", directInput=" + this.directInput.map(BiomeSourcePlanInput::id) + "]";
		}

		@Override
		public boolean equals(Object value) {
			if (this == value) {
				return true;
			}
			if (!(value instanceof ProviderSelection other)) {
				return false;
			}
			return this.salt == other.salt
				&& this.descriptor.equals(other.descriptor)
				&& this.providers.equals(other.providers)
				&& this.rootCompositionDomain.equals(other.rootCompositionDomain)
				&& this.fallback.equals(other.fallback)
				&& this.deferredPlaceholder.equals(other.deferredPlaceholder)
				&& this.directInput.equals(other.directInput);
		}

		@Override
		public int hashCode() {
			return Objects.hash(
				this.descriptor, this.salt, this.providers, this.rootCompositionDomain,
				this.fallback, this.deferredPlaceholder, this.directInput
			);
		}
	}

	public record ProviderResult(
		ResourceLocation domain,
		Holder<Biome> baseBiome,
		Holder<Biome> biome,
		boolean usedFallback,
		Climate.ParameterList<Holder<Biome>> candidates,
		Climate.TargetPoint target
	) {
		public ProviderResult {
			domain = Objects.requireNonNull(domain, "domain");
			baseBiome = Objects.requireNonNull(baseBiome, "baseBiome");
			biome = Objects.requireNonNull(biome, "biome");
			candidates = Objects.requireNonNull(candidates, "candidates");
			target = Objects.requireNonNull(target, "target");
		}

		public ProviderResult(ResourceLocation domain, Holder<Biome> biome, boolean usedFallback) {
			this(
				domain, biome, biome, usedFallback,
				new Climate.ParameterList<>(List.of(Pair.of(
					Climate.parameters(0, 0, 0, 0, 0, 0, 0), biome
				))),
				Climate.target(0, 0, 0, 0, 0, 0)
			);
		}

		public ProviderResult withBiome(Holder<Biome> biome) {
			if (this.biome == biome) {
				return this;
			}
			return new ProviderResult(
				this.domain, this.baseBiome, biome, this.usedFallback, this.candidates, this.target
			);
		}

		/** Computes extended candidate metadata only for decorators that explicitly need it. */
		public CandidateFit candidateFit() {
			return CandidateFit.find(this.candidates.values(), this.target, this.baseBiome);
		}
	}

	/** FTF-owned climate candidate metadata used by normalized selection decorators. */
	public record CandidateMatch(
		Climate.ParameterPoint point,
		Holder<Biome> biome,
		long distance
	) {
		public CandidateMatch {
			point = Objects.requireNonNull(point, "point");
			biome = Objects.requireNonNull(biome, "biome");
			if (distance < 0L) {
				throw new IllegalArgumentException("Candidate distance must be non-negative");
			}
		}
	}

	public record CandidateFit(CandidateMatch ultimate, Optional<CandidateMatch> penultimate) {
		public CandidateFit {
			ultimate = Objects.requireNonNull(ultimate, "ultimate");
			penultimate = Objects.requireNonNull(penultimate, "penultimate");
		}

		private static CandidateFit find(
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries,
			Climate.TargetPoint target,
			Holder<Biome> selected
		) {
			CandidateMatch ultimate = null;
			CandidateMatch penultimate = null;
			for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : entries) {
				long distance = fitness(entry.getFirst(), target);
				if (entry.getSecond().equals(selected)) {
					if (ultimate == null || distance < ultimate.distance()) {
						ultimate = new CandidateMatch(entry.getFirst(), entry.getSecond(), distance);
					}
				} else if (penultimate == null || distance < penultimate.distance()) {
					penultimate = new CandidateMatch(entry.getFirst(), entry.getSecond(), distance);
				}
			}
			if (ultimate == null) {
				throw new IllegalStateException("Selected candidate is absent from its provider table");
			}
			return new CandidateFit(ultimate, Optional.ofNullable(penultimate));
		}

		private static long fitness(Climate.ParameterPoint point, Climate.TargetPoint target) {
			return Mth.square(point.temperature().distance(target.temperature()))
				+ Mth.square(point.humidity().distance(target.humidity()))
				+ Mth.square(point.continentalness().distance(target.continentalness()))
				+ Mth.square(point.erosion().distance(target.erosion()))
				+ Mth.square(point.depth().distance(target.depth()))
				+ Mth.square(point.weirdness().distance(target.weirdness()))
				+ Mth.square(point.offset());
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
			stages = stages.stream()
				.map(stage -> Objects.requireNonNull(stage, "stage"))
				.sorted(Comparator.comparingInt(SelectionDecoratorStage::order)
					.thenComparing(stage -> stage.id().toString()))
				.toList();
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
			Climate.Sampler sampler,
			GeneratorContext surfaceContext
		) {
			if (!this.executable()) {
				throw new IllegalStateException("Worldgen selection-decoration pipeline is unavailable");
			}
			ProviderResult current = Objects.requireNonNull(selection, "selection");
			Objects.requireNonNull(spatial, "spatial");
			for (SelectionDecoratorStage stage : this.stages) {
				Holder<Biome> decorated = stage.decorator().apply(
					current, spatial, target, quartX, quartY, quartZ, sampler, surfaceContext
				);
				if (decorated == null) {
					throw new IllegalStateException("Selection decorator " + stage.id() + " returned null");
				}
				current = current.withBiome(decorated);
			}
			return current.biome();
		}
	}

	public record SelectionDecoratorStage(
		ResourceLocation id,
		int order,
		BiomeSelectionDecorator decorator
	) {
		public SelectionDecoratorStage {
			id = Objects.requireNonNull(id, "id");
			if (order < 0) {
				throw new IllegalArgumentException("Selection decorator order must be non-negative");
			}
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
			Climate.Sampler sampler,
			GeneratorContext surfaceContext
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
		ClimateQueryPolicy queryPolicy,
		List<SamplerDecoratorStage> stages
	) implements DomainPlan {
		public SamplerDecoration(PlanDescriptor descriptor, Optional<SamplerDecorator> decorator) {
			this(descriptor, ClimateQueryPolicy.WORLDGEN, decorator.stream()
				.map(value -> new SamplerDecoratorStage(descriptor.id(), 0, value))
				.toList());
		}

		public SamplerDecoration(
			PlanDescriptor descriptor,
			ClimateQueryPolicy queryPolicy,
			Optional<SamplerDecorator> decorator
		) {
			this(descriptor, queryPolicy, decorator.stream()
				.map(value -> new SamplerDecoratorStage(descriptor.id(), 0, value))
				.toList());
		}

		public SamplerDecoration {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.SAMPLER_DECORATION);
			queryPolicy = Objects.requireNonNull(queryPolicy, "queryPolicy");
			stages = stages.stream()
				.map(stage -> Objects.requireNonNull(stage, "stage"))
				.sorted(Comparator.comparingInt(SamplerDecoratorStage::order)
					.thenComparing(stage -> stage.id().toString()))
				.toList();
			Set<ResourceLocation> ids = new HashSet<>();
			if (stages.stream().anyMatch(stage -> !ids.add(stage.id()))) {
				throw new IllegalArgumentException("Sampler decorator stage IDs must be unique");
			}
		}

		public Optional<SamplerDecorator> decorator() {
			if (this.stages.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of((target, quartX, quartY, quartZ) -> {
				Climate.TargetPoint current = target;
				for (SamplerDecoratorStage stage : this.stages) {
					current = Objects.requireNonNull(
						stage.decorator().apply(current, quartX, quartY, quartZ),
						() -> "Sampler decorator stage " + stage.id() + " returned null"
					);
				}
				return current;
			});
		}

		public SamplerDecoration append(SamplerDecoration contribution, PlanDescriptor descriptor) {
			List<SamplerDecoratorStage> combined = new java.util.ArrayList<>(this.stages);
			combined.addAll(contribution.stages());
			return new SamplerDecoration(descriptor, this.queryPolicy, combined);
		}

		public void initialize(
			WorldgenPlan plan,
			SamplerInputs inputs,
			Climate.Sampler sampler
		) {
			Objects.requireNonNull(plan, "plan");
			Objects.requireNonNull(inputs, "inputs");
			if (!((Object) sampler instanceof RTFClimateSampler rtfSampler)) {
				throw new IllegalArgumentException("Climate sampler does not expose the FTF sampler contract");
			}
			rtfSampler.setClimateQuerySemantics(
				this.queryPolicy, inputs.preset(), plan.owner().seed(), inputs.generatorContext()
			);
		}

		public Climate.TargetPoint sample(
			Climate.Sampler sampler,
			int quartX,
			int quartY,
			int quartZ
		) {
			Climate.TargetPoint current = sampler.sample(quartX, quartY, quartZ);
			for (SamplerDecoratorStage stage : this.stages) {
				Climate.TargetPoint decorated = stage.decorator().apply(current, quartX, quartY, quartZ);
				if (decorated == null) {
					throw new IllegalStateException("Sampler decorator stage " + stage.id() + " returned null");
				}
				current = decorated;
			}
			return current;
		}
	}

	public record SamplerDecoratorStage(
		ResourceLocation id,
		int order,
		SamplerDecorator decorator
	) {
		public SamplerDecoratorStage {
			id = Objects.requireNonNull(id, "id");
			if (order < 0) {
				throw new IllegalArgumentException("Sampler decorator order must be non-negative");
			}
			decorator = Objects.requireNonNull(decorator, "decorator");
		}
	}

	@FunctionalInterface
	public interface SamplerDecorator {
		Climate.TargetPoint apply(
			Climate.TargetPoint target,
			int quartX,
			int quartY,
			int quartZ
		);
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
		Optional<net.minecraft.world.level.levelgen.SurfaceRules.RuleSource> root,
		List<SurfaceRuleTransformStage> transforms,
		List<ResourceLocation> appliedTransforms
	) implements DomainPlan {
		public Surface(
			PlanDescriptor descriptor,
			Optional<net.minecraft.world.level.levelgen.SurfaceRules.RuleSource> root
		) {
			this(descriptor, root, List.of(), List.of());
		}

		public Surface {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.SURFACE);
			root = Objects.requireNonNull(root, "root");
			transforms = transforms.stream()
				.map(stage -> Objects.requireNonNull(stage, "surface transform"))
				.sorted(Comparator.comparingInt(SurfaceRuleTransformStage::order)
					.thenComparing(stage -> stage.id().toString()))
				.toList();
			appliedTransforms = List.copyOf(appliedTransforms);
			Set<ResourceLocation> ids = new HashSet<>(appliedTransforms);
			if (ids.size() != appliedTransforms.size()
				|| transforms.stream().anyMatch(stage -> !ids.add(stage.id()))) {
				throw new IllegalArgumentException("Surface transform IDs must be unique across pending and applied stages");
			}
		}

		public Surface append(Surface contribution, PlanDescriptor combinedDescriptor) {
			List<SurfaceRuleTransformStage> combined = new java.util.ArrayList<>(this.transforms);
			combined.addAll(contribution.transforms);
			return new Surface(combinedDescriptor, this.root, combined, this.appliedTransforms);
		}

		public Surface withRoot(Surface contribution) {
			return new Surface(
				contribution.descriptor(), contribution.root(), this.transforms, this.appliedTransforms
			);
		}

		public Surface materialize(PlanDescriptor combinedDescriptor) {
			if (this.transforms.isEmpty()) {
				return this;
			}
			var current = this.root.orElseThrow(
				() -> new IllegalStateException("Surface transforms require one surface-rule root")
			);
			List<ResourceLocation> applied = new java.util.ArrayList<>(this.appliedTransforms);
			for (SurfaceRuleTransformStage stage : this.transforms) {
				current = Objects.requireNonNull(
					stage.transform().apply(current),
					() -> "Surface transform " + stage.id() + " returned null"
				);
				applied.add(stage.id());
			}
			return new Surface(combinedDescriptor, Optional.of(current), List.of(), applied);
		}
	}

	public record SurfaceRuleTransformStage(
		ResourceLocation id,
		int order,
		java.util.function.UnaryOperator<net.minecraft.world.level.levelgen.SurfaceRules.RuleSource> transform
	) {
		public SurfaceRuleTransformStage {
			id = Objects.requireNonNull(id, "id");
			if (order < 0) {
				throw new IllegalArgumentException("Surface transform order must be non-negative");
			}
			transform = Objects.requireNonNull(transform, "transform");
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
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings,
		Map<ResourceKey<Biome>, Map<Integer, List<Holder<PlacedFeature>>>> byBiome,
		Map<PlacedFeature, DynamicOrePlan.VerticalTransform> oreTransforms
	) implements DomainPlan {
		public PlacedFeatures(
			PlanDescriptor descriptor,
			List<PlacedFeaturePipeline> pipelines,
			List<FeatureSorter.StepFeatureData> steps,
			Map<PlacedFeature, Classification> surfaceClassifications,
			DynamicOrePlan ores
		) {
			this(
				descriptor, pipelines, steps, surfaceClassifications, ores,
				Map.of(), indexPlacedFeatures(pipelines), indexOreTransforms(pipelines, ores)
			);
		}

		public PlacedFeatures(
			PlanDescriptor descriptor,
			List<PlacedFeaturePipeline> pipelines,
			List<FeatureSorter.StepFeatureData> steps,
			Map<PlacedFeature, Classification> surfaceClassifications,
			DynamicOrePlan ores,
			Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings
		) {
			this(
				descriptor, pipelines, steps, surfaceClassifications, ores,
				generationSettings, indexPlacedFeatures(pipelines), indexOreTransforms(pipelines, ores)
			);
		}

		public PlacedFeatures {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.PLACED_FEATURES);
			pipelines = List.copyOf(pipelines);
			steps = List.copyOf(steps);
			surfaceClassifications = Collections.unmodifiableMap(
				new IdentityHashMap<>(surfaceClassifications)
			);
			ores = Objects.requireNonNull(ores, "ores");
			generationSettings = Map.copyOf(generationSettings);
			byBiome = immutableNestedMap(byBiome);
			oreTransforms = Collections.unmodifiableMap(new IdentityHashMap<>(oreTransforms));
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

		public Optional<DynamicOrePlan.VerticalTransform> oreTransform(PlacedFeature feature) {
			return Optional.ofNullable(this.oreTransforms.get(feature));
		}
	}

	public record PlacedFeaturePipeline(
		ResourceKey<Biome> biome,
		int generationStep,
		int index,
		Holder<PlacedFeature> placedFeature
	) {
		public PlacedFeaturePipeline {
			biome = Objects.requireNonNull(biome, "biome");
			if (generationStep < 0 || index < 0) {
				throw new IllegalArgumentException("Feature step and index must be non-negative");
			}
			placedFeature = Objects.requireNonNull(placedFeature, "placedFeature");
		}
	}

	public record Structures(
		PlanDescriptor descriptor,
		List<Holder.Reference<Structure>> structures,
		List<Holder.Reference<StructureSet>> sets,
		List<Holder.Reference<StructureTemplatePool>> pools,
		List<Holder.Reference<StructureProcessorList>> processors,
		List<Holder.Reference<StructureRule>> rules,
		Map<Structure, StructureAdaptation> adaptations
	) implements DomainPlan {
		public Structures(
			PlanDescriptor descriptor,
			List<Holder.Reference<Structure>> structures,
			List<Holder.Reference<StructureSet>> sets,
			List<Holder.Reference<StructureTemplatePool>> pools,
			List<Holder.Reference<StructureProcessorList>> processors,
			List<Holder.Reference<StructureRule>> rules
		) {
			this(
				descriptor, structures, sets, pools, processors, rules,
				indexStructureAdaptations(structures)
			);
		}

		public Structures {
			descriptor = validateDescriptor(descriptor, WorldgenFacet.STRUCTURES);
			structures = List.copyOf(structures);
			sets = List.copyOf(sets);
			pools = List.copyOf(pools);
			processors = List.copyOf(processors);
			rules = List.copyOf(rules);
			adaptations = Collections.unmodifiableMap(new IdentityHashMap<>(adaptations));
		}

		public StructureAdaptation adaptation(Structure structure) {
			return this.adaptations.getOrDefault(structure, StructureAdaptation.NONE);
		}
	}

	public enum StructureAdaptation {
		NONE,
		SUBTERRANEAN,
		VILLAGE
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

	private static Map<PlacedFeature, DynamicOrePlan.VerticalTransform> indexOreTransforms(
		List<PlacedFeaturePipeline> pipelines,
		DynamicOrePlan ores
	) {
		IdentityHashMap<PlacedFeature, DynamicOrePlan.VerticalTransform> indexed = new IdentityHashMap<>();
		IdentityHashMap<PlacedFeature, String> identities = new IdentityHashMap<>();
		Set<PlacedFeature> conflicted = Collections.newSetFromMap(new IdentityHashMap<>());
		for (PlacedFeaturePipeline pipeline : pipelines) {
			Holder<PlacedFeature> holder = pipeline.placedFeature();
			PlacedFeature feature = holder.value();
			Optional<ResourceKey<PlacedFeature>> key = holder.unwrapKey();
			if (key.isEmpty()) {
				conflicted.add(feature);
				indexed.remove(feature);
				continue;
			}
			if (conflicted.contains(feature)) {
				continue;
			}
			String id = key.orElseThrow().location().toString();
			String previous = identities.putIfAbsent(feature, id);
			if (previous != null && !previous.equals(id)) {
				conflicted.add(feature);
				indexed.remove(feature);
				continue;
			}
			if (!conflicted.contains(feature)) {
				DynamicOrePlan.VerticalTransform transform = ores.verticalTransforms().get(id);
				if (transform != null) {
					indexed.put(feature, transform);
				}
			}
		}
		return indexed;
	}

	private static Map<Structure, StructureAdaptation> indexStructureAdaptations(
		List<Holder.Reference<Structure>> structures
	) {
		IdentityHashMap<Structure, StructureAdaptation> indexed = new IdentityHashMap<>();
		IdentityHashMap<Structure, StructureAdaptation> seen = new IdentityHashMap<>();
		for (Holder.Reference<Structure> holder : structures) {
			StructureAdaptation adaptation = holder.is(BuiltinStructures.TRIAL_CHAMBERS)
				|| holder.is(BuiltinStructures.ANCIENT_CITY)
				? StructureAdaptation.SUBTERRANEAN
				: holder.is(StructureTags.VILLAGE)
				? StructureAdaptation.VILLAGE
				: StructureAdaptation.NONE;
			StructureAdaptation previous = seen.putIfAbsent(holder.value(), adaptation);
			if (previous != null && previous != adaptation) {
				throw new IllegalArgumentException(
					"One structure value has conflicting compiled adaptation identities"
				);
			}
			if (adaptation != StructureAdaptation.NONE) {
				indexed.put(holder.value(), adaptation);
			}
		}
		return indexed;
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
