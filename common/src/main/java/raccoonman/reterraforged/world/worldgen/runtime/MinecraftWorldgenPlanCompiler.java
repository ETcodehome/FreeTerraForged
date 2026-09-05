package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import java.util.LinkedHashMap;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection;
import raccoonman.reterraforged.world.worldgen.biome.SurfaceBiomeFilter;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeTags;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.feature.placement.ChunkLocalPlacementClassifier;
import raccoonman.reterraforged.world.worldgen.feature.placement.SurfacePlacementClassifier;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlanner;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifier;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.ClimateQueryPolicy;

public final class MinecraftWorldgenPlanCompiler {
	private static final String REGISTRY_GRAPH = "minecraft_registry_graph";

	private MinecraftWorldgenPlanCompiler() {
	}

	public static WorldgenPlan compile(
		WorldgenOwner owner,
		List<? extends WorldgenCapabilityProvider> providers
	) {
		return compile(owner, providers, WorldgenCompilationPurpose.WORLDGEN);
	}

	public static WorldgenPlan compile(
		WorldgenOwner owner,
		List<? extends WorldgenCapabilityProvider> providers,
		WorldgenCompilationPurpose purpose
	) {
		return compile(owner, new WorldgenPlanCompiler(providers), purpose, () -> false);
	}

	public static WorldgenPlan compile(
		WorldgenOwner owner,
		WorldgenProviderCatalog providers
	) {
		return compile(owner, providers, WorldgenCompilationPurpose.WORLDGEN);
	}

	public static WorldgenPlan compile(
		WorldgenOwner owner,
		WorldgenProviderCatalog providers,
		WorldgenCompilationPurpose purpose
	) {
		return compile(owner, providers, purpose, () -> false);
	}

	public static WorldgenPlan compile(
		WorldgenOwner owner,
		WorldgenProviderCatalog providers,
		WorldgenCompilationPurpose purpose,
		BooleanSupplier cancelled
	) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(providers, "providers");
		Objects.requireNonNull(purpose, "purpose");
		Objects.requireNonNull(cancelled, "cancelled");
		return providers.inAcquisitionSession(
			cancelled, () -> compileAcquired(owner, providers, purpose, cancelled)
		);
	}

	private static WorldgenPlan compileAcquired(
		WorldgenOwner owner,
		WorldgenProviderCatalog providers,
		WorldgenCompilationPurpose purpose,
		BooleanSupplier cancelled
	) {
		checkCancelled(cancelled);
		requireContributionRevision(owner, providers, "before plan compilation");
		WorldgenPlan plan = compile(owner, new WorldgenPlanCompiler(providers), purpose, cancelled);
		checkCancelled(cancelled);
		requireContributionRevision(owner, providers, "after plan compilation");
		return plan;
	}

	private static void requireContributionRevision(
		WorldgenOwner owner,
		WorldgenProviderCatalog providers,
		String boundary
	) {
		WorldgenContributionRevision.Snapshot current = WorldgenContributionRevision.snapshot(
			owner.dimension(), providers
		);
		if (!current.equals(owner.contributionRevision())) {
			throw new IllegalStateException(
				"Worldgen contributions changed " + boundary + "; expected "
					+ owner.contributionRevision() + " but acquired " + current
			);
		}
	}

	private static WorldgenPlan compile(
		WorldgenOwner owner,
		WorldgenPlanCompiler compiler,
		WorldgenCompilationPurpose purpose,
		BooleanSupplier cancelled
	) {
		long started = System.nanoTime();
		checkCancelled(cancelled);
		WorldgenPlan base = compileBase(owner, purpose);
		checkCancelled(cancelled);
		WorldgenPlan negotiated = compiler.compile(base, purpose, cancelled);
		checkCancelled(cancelled);
		WorldgenPlan selected = finalizeSelectionPipeline(
			materializeCandidateOwnership(negotiated), purpose
		);
		checkCancelled(cancelled);
		WorldgenPlan plan = materializeGraphDependentFacets(base, selected, purpose);
		checkCancelled(cancelled);
		if (RTFCommon.LOGGER.isDebugEnabled()) {
			Map<CapabilityState, Long> states = plan.report().nodes().stream().collect(
				java.util.stream.Collectors.groupingBy(
					CapabilityNodeReport::state,
					() -> new java.util.EnumMap<>(CapabilityState.class),
					java.util.stream.Collectors.counting()
				)
			);
			RTFCommon.LOGGER.debug(
				"Compiled worldgen plan owner={} type={} purpose={} tag_epoch={} contribution_epoch={} elapsed_ms={} capability_nodes={} states={}",
				owner.id(),
				owner.type(),
				purpose,
				owner.tagEpoch().sequence(),
				owner.contributionRevision().revisions(),
				String.format(java.util.Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1_000_000.0D),
				plan.report().nodes().size(),
				states
			);
		}
		return plan;
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Worldgen plan acquisition was superseded");
		}
	}

	private static WorldgenPlan compileBase(WorldgenOwner owner, WorldgenCompilationPurpose purpose) {
		ChunkGenerator generator = owner.selectedStem().generator();
		BiomeSource source = MinecraftBiomeSourceGraphs.acquisitionSource(generator);
		Optional<BiomeSourcePlanInput> directInput = Optional.empty();
		Optional<BiomeCandidateRoot> candidateRoot = Optional.empty();
		Optional<CapabilityFailure> directInputFailure = Optional.empty();
		if (generator instanceof TerraForgedChunkGenerator terraForged) {
			candidateRoot = terraForged.acquisitionBiomeCandidateRoot();
			try {
				directInput = terraForged.acquireBiomePlanInput(owner);
				} catch (CancellationException failure) {
					throw failure;
				} catch (RuntimeException | LinkageError failure) {
				directInputFailure = Optional.of(CapabilityFailure.of(
					"custom_source_plan_acquisition_failed", failure
				));
			}
		}
		Optional<BiomeCandidateRoot> retainedCandidateRoot = candidateRoot;
		List<CapabilityNodeReport> reports = new ArrayList<>();

		WorldgenPlans.BiomeComposition biomePlan = directInputFailure.isPresent()
			? new WorldgenPlans.BiomeComposition(
				new PlanDescriptor(
					RTFCommon.location("runtime/custom_source_acquisition"),
					WorldgenFacet.BIOME_COMPOSITION, CapabilityState.UNAVAILABLE,
					"request_owned_custom_source_root",
					"The custom source failed to produce its immutable plan input",
					directInputFailure
				),
				List.of()
			)
			: directInput.isPresent()
			? new WorldgenPlans.BiomeComposition(
				descriptor(WorldgenFacet.BIOME_COMPOSITION, CapabilityState.PROVIDER_CONTRACT,
					"The request-owned custom source supplies a complete executable root instead of a candidate table"),
				List.of()
			)
			: compileFacet(
				WorldgenFacet.BIOME_COMPOSITION,
				() -> compileBiomes(owner, source, retainedCandidateRoot),
				failure -> new WorldgenPlans.BiomeComposition(failure, List.of())
			);
		WorldgenPlans.ProviderSelection providerPlan = directInputFailure.isPresent()
			? new WorldgenPlans.ProviderSelection(
				new PlanDescriptor(
					RTFCommon.location("runtime/custom_source_acquisition"),
					WorldgenFacet.PROVIDER_SELECTION, CapabilityState.UNAVAILABLE,
					"request_owned_custom_source_root",
					"The custom source failed to produce its immutable plan input",
					directInputFailure
				),
				0L, List.of(), Optional.empty(), Optional.empty(), Optional.empty()
			)
			: directInput.isPresent()
			? new WorldgenPlans.ProviderSelection(
				new PlanDescriptor(
					directInput.orElseThrow().id(), WorldgenFacet.PROVIDER_SELECTION,
					CapabilityState.PROVIDER_CONTRACT, "request_owned_custom_source_root",
					"A complete owner-scoped custom source query and output closure were acquired before execution",
					Optional.empty()
				),
				0L, List.of(), Optional.empty(), Optional.empty(), Optional.empty(), directInput
			)
			: new WorldgenPlans.ProviderSelection(
				unavailable(WorldgenFacet.PROVIDER_SELECTION, "no_provider_contract",
					"No public provider snapshot was supplied for the selected root"),
				0L, List.of(), Optional.empty(), Optional.empty(), Optional.empty()
			);
		WorldgenPlans.SelectionDecoration decorationPlan = new WorldgenPlans.SelectionDecoration(
			new PlanDescriptor(
				RTFCommon.location("runtime/selection_mechanisms"),
				WorldgenFacet.SELECTION_DECORATION,
				CapabilityState.NORMALIZED,
				"ordered_selection_pipeline",
				"No mechanism-specific selection decorators were discovered in the selected declarative graph",
				Optional.empty()
			),
			List.of()
		);
		WorldgenPlans.SpatialOwnership spatialPlan = directInput.isPresent()
			? new WorldgenPlans.SpatialOwnership(
				descriptor(WorldgenFacet.SPATIAL_OWNERSHIP, CapabilityState.NORMALIZED,
					"The direct custom source root owns positional selection and requires no FTF provider-domain resolver"),
				Optional.empty()
			)
			: new WorldgenPlans.SpatialOwnership(
				unavailable(WorldgenFacet.SPATIAL_OWNERSHIP, "no_spatial_contract",
					"The selected biome source does not expose provider-domain ownership"),
				Optional.empty()
			);
		WorldgenPlans.SamplerDecoration samplerPlan = new WorldgenPlans.SamplerDecoration(
			descriptor(WorldgenFacet.SAMPLER_DECORATION, CapabilityState.NORMALIZED,
				"Realized and request-owned samplers receive purpose-scoped owner-local plan and FTF state"),
			purpose == WorldgenCompilationPurpose.BIOME_PREVIEW
				? ClimateQueryPolicy.SURFACE_PREVIEW
				: ClimateQueryPolicy.WORLDGEN,
			Optional.empty()
		);

		WorldgenPlans.DensitySettings densityPlan;
		WorldgenPlans.Surface surfacePlan;
		if (!purpose.includes(WorldgenFacet.DENSITY_SETTINGS)) {
			densityPlan = new WorldgenPlans.DensitySettings(
				notMaterialized(WorldgenFacet.DENSITY_SETTINGS, purpose), Optional.empty()
			);
		} else if (generator instanceof NoiseBasedChunkGenerator noiseGenerator) {
			densityPlan = new WorldgenPlans.DensitySettings(
				descriptor(WorldgenFacet.DENSITY_SETTINGS, CapabilityState.NORMALIZED,
					"Selected noise settings and density router are retained as one typed root"),
				Optional.of(noiseGenerator.generatorSettings())
			);
		} else {
			densityPlan = new WorldgenPlans.DensitySettings(
				descriptor(WorldgenFacet.DENSITY_SETTINGS, CapabilityState.OPAQUE_ROOT,
					"Custom generator density behavior remains in the realized generator root"),
				Optional.empty()
			);
		}
		if (!purpose.includes(WorldgenFacet.SURFACE)) {
			surfacePlan = new WorldgenPlans.Surface(
				notMaterialized(WorldgenFacet.SURFACE, purpose), Optional.empty()
			);
		} else if (generator instanceof NoiseBasedChunkGenerator noiseGenerator) {
			surfacePlan = new WorldgenPlans.Surface(
				descriptor(WorldgenFacet.SURFACE, CapabilityState.NORMALIZED,
					"Active surface-rule graph is retained from the selected noise settings"),
				Optional.of(noiseGenerator.generatorSettings().value().surfaceRule())
			);
		} else {
			surfacePlan = new WorldgenPlans.Surface(
				descriptor(WorldgenFacet.SURFACE, CapabilityState.OPAQUE_ROOT,
					"Custom generator surface behavior remains in the realized generator root"),
				Optional.empty()
			);
		}

		WorldgenPlans.Carvers carverPlan = !purpose.includes(WorldgenFacet.CARVERS)
			? new WorldgenPlans.Carvers(notMaterialized(WorldgenFacet.CARVERS, purpose), List.of())
			: new WorldgenPlans.Carvers(pendingGraphMaterialization(WorldgenFacet.CARVERS), List.of());
		WorldgenPlans.PlacedFeatures featurePlan = !purpose.includes(WorldgenFacet.PLACED_FEATURES)
			? new WorldgenPlans.PlacedFeatures(
				notMaterialized(WorldgenFacet.PLACED_FEATURES, purpose), List.of(), List.of(), Map.of(), DynamicOrePlan.empty()
			)
			: new WorldgenPlans.PlacedFeatures(
				pendingGraphMaterialization(WorldgenFacet.PLACED_FEATURES),
				List.of(), List.of(), Map.of(), DynamicOrePlan.empty()
			);
		WorldgenPlans.Structures structurePlan = !purpose.includes(WorldgenFacet.STRUCTURES)
			? new WorldgenPlans.Structures(
				notMaterialized(WorldgenFacet.STRUCTURES, purpose),
				List.of(), List.of(), List.of(), List.of(), List.of()
			)
			: compileFacet(
			WorldgenFacet.STRUCTURES,
			() -> compileStructures(owner),
			failure -> new WorldgenPlans.Structures(
				failure, List.of(), List.of(), List.of(), List.of(), List.of()
			)
		);

		List<WorldgenPlans.DomainPlan> plans = List.of(
			biomePlan, providerPlan, decorationPlan, spatialPlan, samplerPlan,
			densityPlan, surfacePlan, carverPlan, featurePlan, structurePlan
		);
		plans.forEach(plan -> reports.add(plan.descriptor().report(owner)));
		addCarverReports(owner, carverPlan, reports);
		addFeatureReports(owner, featurePlan, reports);
		addStructureReports(owner, structurePlan, reports);
		WorldgenExecution execution = WorldgenExecution.serial().withQueryMode(
			WorldgenFacet.SAMPLER_DECORATION, WorldgenQueryMode.ISOLATED_PARALLEL_READ
		);
		if (directInput.isPresent()) {
			execution = execution
				.withQueryMode(WorldgenFacet.PROVIDER_SELECTION, directInput.orElseThrow().queryMode())
				.withQueryMode(WorldgenFacet.SELECTION_DECORATION, WorldgenQueryMode.ISOLATED_PARALLEL_READ)
				.withQueryMode(WorldgenFacet.SPATIAL_OWNERSHIP, WorldgenQueryMode.ISOLATED_PARALLEL_READ);
		}
		return new WorldgenPlan(
			owner, biomePlan, providerPlan, decorationPlan, spatialPlan, samplerPlan,
			densityPlan, surfacePlan, carverPlan, featurePlan, structurePlan,
			execution, new WorldgenCapabilityReport(reports, execution)
		);
	}

	private static WorldgenPlans.BiomeComposition compileBiomes(
		WorldgenOwner owner,
		BiomeSource source,
		Optional<BiomeCandidateRoot> retainedRoot
	) {
		if (source instanceof MultiNoiseBiomeSource) {
			BiomeCandidateRoot root = retainedRoot.orElseGet(
				() -> MinecraftBiomeSourceGraphs.multiNoiseRoot(source, owner.registries())
			);
			return new WorldgenPlans.BiomeComposition(
				descriptor(WorldgenFacet.BIOME_COMPOSITION, CapabilityState.NORMALIZED,
					"Public multi-noise candidates and their immutable search index were retained as plan data"),
				root.entries(), List.of(), Optional.of(root)
			);
		}
		List<Holder<Biome>> possible = source.possibleBiomes().stream().toList();
		if (possible.size() == 1) {
			return new WorldgenPlans.BiomeComposition(
				descriptor(WorldgenFacet.BIOME_COMPOSITION, CapabilityState.NORMALIZED,
					"The public source output closure contains one biome and is normalized as a constant candidate root"),
				List.of(Pair.of(Climate.parameters(0, 0, 0, 0, 0, 0, 0), possible.getFirst()))
			);
		}
		return new WorldgenPlans.BiomeComposition(
			unavailable(WorldgenFacet.BIOME_COMPOSITION, "source_composition_opaque",
				"The selected multi-output source exposes only a positional query boundary; it requires a "
					+ "metadata-discovered provider that compiles an immutable candidate/provider plan"),
			List.of()
		);
	}

	private static WorldgenPlans.Carvers compileCarvers(
		WorldgenOwner owner,
		ChunkGenerator generator,
		List<Holder<Biome>> biomes
	) {
		List<WorldgenPlans.CarverPipeline> pipelines = new ArrayList<>();
		for (Holder<Biome> biome : biomes) {
			ResourceKey<Biome> biomeKey = biome.unwrapKey().orElseThrow(
				() -> new IllegalStateException("Selected biome is not registry-keyed")
			);
			BiomeGenerationSettings settings = realizedGenerationSettings(generator, biome);
			for (GenerationStep.Carving step : GenerationStep.Carving.values()) {
				int index = 0;
				for (Holder<ConfiguredWorldCarver<?>> carver : settings.getCarvers(step)) {
					pipelines.add(new WorldgenPlans.CarverPipeline(biomeKey, step, index++, carver));
				}
			}
		}
		return new WorldgenPlans.Carvers(
			descriptor(WorldgenFacet.CARVERS, CapabilityState.NORMALIZED,
				"Final biome carver steps are ordered typed pipelines with public executable leaves"),
			pipelines
		);
	}

	static WorldgenPlan materializeGraphDependentFacets(
		WorldgenPlan base,
		WorldgenPlan selected,
		WorldgenCompilationPurpose purpose
	) {
		boolean compileCarvers = purpose.includes(WorldgenFacet.CARVERS)
			&& selected.carvers() == base.carvers();
		boolean compileFeatures = purpose.includes(WorldgenFacet.PLACED_FEATURES)
			&& selected.placedFeatures() == base.placedFeatures();
		if (!compileCarvers && !compileFeatures) {
			return selected;
		}

		WorldgenOwner owner = selected.owner();
		ChunkGenerator generator = owner.selectedStem().generator();
		List<Holder<Biome>> graphBiomes = sortedBiomes(
			owner, biomesRequiredByGraphFacets(selected)
		);

		WorldgenPlans.Carvers carvers = selected.carvers();
		WorldgenPlans.PlacedFeatures features = selected.placedFeatures();
		List<CapabilityNodeReport> reports = new ArrayList<>(selected.report().nodes());
		if (compileCarvers) {
			carvers = compileFacet(
				WorldgenFacet.CARVERS,
				() -> compileCarvers(owner, generator, graphBiomes),
				failure -> new WorldgenPlans.Carvers(failure, List.of())
			);
			reports.removeIf(node -> node.facet() == WorldgenFacet.CARVERS);
			reports.add(carvers.descriptor().report(owner));
			addCarverReports(owner, carvers, reports);
		}
		if (compileFeatures) {
			features = compileFacet(
				WorldgenFacet.PLACED_FEATURES,
				() -> compilePlacedFeatures(owner, generator, graphBiomes),
				failure -> new WorldgenPlans.PlacedFeatures(
					failure, List.of(), List.of(), Map.of(), DynamicOrePlan.empty()
				)
			);
			reports.removeIf(node -> node.facet() == WorldgenFacet.PLACED_FEATURES);
			reports.add(features.descriptor().report(owner));
			addFeatureReports(owner, features, reports);
		}
		return new WorldgenPlan(
			owner, selected.biomeComposition(), selected.providerSelection(),
			selected.selectionDecoration(), selected.spatialOwnership(), selected.samplerDecoration(),
			selected.densitySettings(), selected.surface(), carvers, features, selected.structures(),
			selected.execution(), new WorldgenCapabilityReport(
				reports, selected.execution(), selected.report().providerDiagnostics()
			)
		);
	}

	static WorldgenPlan materializeCandidateOwnership(WorldgenPlan plan) {
		WorldgenPlans.BiomeComposition composition = plan.biomeComposition();
		WorldgenPlans.BiomeComposition candidateOperations = composition;
		WorldgenPlans.ProviderSelection providers = plan.providerSelection();
		WorldgenPlans.SpatialOwnership spatial = plan.spatialOwnership();
		if (providers.directInput().isPresent()) {
			return plan;
		}
		boolean compositionChanged = false;
		boolean providerChanged = false;
		boolean spatialChanged = false;

		if (!composition.entries().isEmpty() && !candidateOperations.stages().isEmpty()
			&& composition.descriptor().state() != CapabilityState.UNAVAILABLE) {
			WorldgenPlans.BiomeComposition sourceComposition = composition;
			BiomeCandidateRoot finalizedRoot = sourceComposition.candidateRoot()
				.map(candidateOperations::applyTo)
				.orElseGet(() -> BiomeCandidateRoot.fromEntries(
					candidateOperations.applyTo(sourceComposition.entries())
				));
			composition = new WorldgenPlans.BiomeComposition(
				sourceComposition.descriptor(), finalizedRoot.entries(), List.of(), Optional.of(finalizedRoot)
			);
			compositionChanged = true;
		}

		if (providers.providers().isEmpty() && !composition.entries().isEmpty()
			&& composition.descriptor().state() != CapabilityState.UNAVAILABLE) {
			ResourceLocation id = RTFCommon.location("selected_source_root");
			Climate.ParameterList<Holder<Biome>> candidateTable = composition.candidateRoot().isPresent()
				? composition.candidateRoot().orElseThrow().candidates()
				: new Climate.ParameterList<>(composition.entries());
			WorldgenPlans.ProviderDomain domain = new WorldgenPlans.ProviderDomain(
				id, 1.0D, candidateTable, 0
			);
			providers = new WorldgenPlans.ProviderSelection(
				new PlanDescriptor(
					RTFCommon.location("runtime/candidate_provider"),
					WorldgenFacet.PROVIDER_SELECTION,
					CapabilityState.NORMALIZED,
					"ftf_candidate_provider",
					"The normalized selected candidate table is the single active provider",
					Optional.empty()
				),
				plan.owner().seed(), List.of(domain), Optional.of(id), Optional.empty(), Optional.empty()
			);
			providerChanged = true;
		} else if (!providers.providers().isEmpty() && !candidateOperations.stages().isEmpty()) {
			ResourceLocation rootDomain = providers.rootCompositionDomain().orElse(null);
			if (rootDomain == null || composition.entries().isEmpty()
				|| composition.descriptor().state() == CapabilityState.UNAVAILABLE) {
				composition = new WorldgenPlans.BiomeComposition(
					unavailable(
						WorldgenFacet.BIOME_COMPOSITION,
						"candidate_root_missing",
						"Candidate operations require an explicit provider domain for the selected root composition"
					),
					List.of()
				);
				compositionChanged = true;
				return replaceSelectionMaterialization(plan, composition, providers, spatial,
					compositionChanged, false, false);
			}
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> rootEntries = composition.entries();
			Climate.ParameterList<Holder<Biome>> retainedTable = composition.candidateRoot()
				.map(BiomeCandidateRoot::candidates)
				.orElse(null);
			List<WorldgenPlans.ProviderDomain> transformed = providers.providers().stream()
				.map(domain -> new WorldgenPlans.ProviderDomain(
					domain.id(), domain.weight(),
					domain.id().equals(rootDomain)
						? (retainedTable != null
							? retainedTable
							: new Climate.ParameterList<>(rootEntries))
						: domain.candidates(),
					domain.registrationOrder()
				))
				.toList();
			Climate.ParameterList<Holder<Biome>> rootTable = transformed.stream()
				.filter(domain -> domain.id().equals(rootDomain))
				.findFirst().orElseThrow().candidates();
			Optional<Climate.ParameterList<Holder<Biome>>> fallback = providers.fallback().isPresent()
				? Optional.of(rootTable)
				: Optional.empty();
			providers = new WorldgenPlans.ProviderSelection(
				providers.descriptor(), providers.salt(), transformed, Optional.of(rootDomain), fallback,
				providers.deferredPlaceholder()
			);
			if (!composition.stages().isEmpty()) {
				composition = new WorldgenPlans.BiomeComposition(
					composition.descriptor(), composition.entries()
				);
				compositionChanged = true;
			}
			providerChanged = true;
		} else if (!candidateOperations.stages().isEmpty()
			&& composition.descriptor().state() != CapabilityState.UNAVAILABLE) {
			composition = new WorldgenPlans.BiomeComposition(
				unavailable(
					WorldgenFacet.BIOME_COMPOSITION,
					"candidate_root_missing",
					"Candidate operations were captured, but no immutable candidate root or provider domain exists"
				),
				List.of()
			);
			compositionChanged = true;
		}

		if (spatial.resolver().isEmpty() && providers.providers().size() == 1) {
			ResourceLocation domain = providers.providers().getFirst().id();
			spatial = new WorldgenPlans.SpatialOwnership(
				new PlanDescriptor(
					RTFCommon.location("runtime/candidate_spatial_ownership"),
					WorldgenFacet.SPATIAL_OWNERSHIP,
					CapabilityState.NORMALIZED,
					"ftf_cell_ownership",
					"Every FTF cell belongs to the single normalized candidate provider",
					Optional.empty()
				),
				Optional.of((cellX, cellZ) -> new WorldgenPlans.SpatialResult(
					domain, cellX, cellZ
				))
			);
			spatialChanged = true;
		}
		return replaceSelectionMaterialization(
			plan, composition, providers, spatial,
			compositionChanged, providerChanged, spatialChanged
		);
	}

	private static WorldgenPlan replaceSelectionMaterialization(
		WorldgenPlan plan,
		WorldgenPlans.BiomeComposition composition,
		WorldgenPlans.ProviderSelection providers,
		WorldgenPlans.SpatialOwnership spatial,
		boolean compositionChanged,
		boolean providerChanged,
		boolean spatialChanged
	) {
		if (!compositionChanged && !providerChanged && !spatialChanged) {
			return plan;
		}
		List<CapabilityNodeReport> reports = new ArrayList<>(plan.report().nodes());
		if (providerChanged && plan.providerSelection().providers().isEmpty()) {
			reports.removeIf(node -> node.facet() == WorldgenFacet.PROVIDER_SELECTION);
			reports.add(providers.descriptor().report(plan.owner()));
		}
		if (spatialChanged) {
			reports.removeIf(node -> node.facet() == WorldgenFacet.SPATIAL_OWNERSHIP);
			reports.add(spatial.descriptor().report(plan.owner()));
		}
		WorldgenExecution execution = plan.execution();
		if (providerChanged && (plan.providerSelection().providers().isEmpty()
			|| plan.providerSelection().descriptor().state() == CapabilityState.NORMALIZED)) {
			execution = execution.withQueryMode(
				WorldgenFacet.PROVIDER_SELECTION, WorldgenQueryMode.ISOLATED_PARALLEL_READ
			);
		}
		if (spatialChanged) {
			execution = execution.withQueryMode(
				WorldgenFacet.SPATIAL_OWNERSHIP, WorldgenQueryMode.ISOLATED_PARALLEL_READ
			);
		}
		return new WorldgenPlan(
			plan.owner(), composition, providers,
			plan.selectionDecoration(), spatial, plan.samplerDecoration(), plan.densitySettings(),
			plan.surface(), plan.carvers(), plan.placedFeatures(), plan.structures(), execution,
			new WorldgenCapabilityReport(reports, execution, plan.report().providerDiagnostics())
		);
	}

	private static WorldgenPlan finalizeSelectionPipeline(
		WorldgenPlan plan,
		WorldgenCompilationPurpose purpose
	) {
		WorldgenPlans.SelectionDecoration mechanisms = plan.selectionDecoration();
		if (mechanisms.descriptor().state() == CapabilityState.UNAVAILABLE) {
			return plan;
		}
		if (plan.providerSelection().directInput().isPresent()) {
			boolean unsupportedSelection = !mechanisms.stages().isEmpty()
				|| !plan.biomeComposition().stages().isEmpty();
			boolean unsupportedSampler = !plan.samplerDecoration().stages().isEmpty();
			if (!unsupportedSelection && !unsupportedSampler) {
				return plan;
			}
			WorldgenPlans.SelectionDecoration selection = unsupportedSelection
				? new WorldgenPlans.SelectionDecoration(
				unavailable(
					WorldgenFacet.SELECTION_DECORATION,
					"custom_source_composition_unsupported",
					"The direct custom source root does not expose candidate-table semantics required by active composition or selection transforms"
				),
				List.of()
			) : mechanisms;
			WorldgenPlans.SamplerDecoration sampler = unsupportedSampler
				? new WorldgenPlans.SamplerDecoration(
					unavailable(
						WorldgenFacet.SAMPLER_DECORATION,
						"custom_source_sampler_transform_unsupported",
						"The direct custom source query cannot consume plan-owned climate-target transforms"
					),
					plan.samplerDecoration().queryPolicy(),
					List.of()
				) : plan.samplerDecoration();
			List<CapabilityNodeReport> reports = new ArrayList<>(plan.report().nodes());
			if (unsupportedSelection) {
				reports.add(selection.descriptor().report(plan.owner()));
			}
			if (unsupportedSampler) {
				reports.add(sampler.descriptor().report(plan.owner()));
			}
			WorldgenExecution execution = plan.execution();
			if (unsupportedSelection) {
				execution = execution.withQueryMode(
					WorldgenFacet.SELECTION_DECORATION, WorldgenQueryMode.OWNER_SERIAL
				);
			}
			if (unsupportedSampler) {
				execution = execution.withQueryMode(
					WorldgenFacet.SAMPLER_DECORATION, WorldgenQueryMode.OWNER_SERIAL
				);
			}
			return new WorldgenPlan(
				plan.owner(), plan.biomeComposition(), plan.providerSelection(), selection,
				plan.spatialOwnership(), sampler, plan.densitySettings(),
				plan.surface(), plan.carvers(), plan.placedFeatures(), plan.structures(), execution,
				new WorldgenCapabilityReport(reports, execution, plan.report().providerDiagnostics())
			);
		}
		WorldgenPlans.SelectionDecoration policy = compileFacet(
			WorldgenFacet.SELECTION_DECORATION,
			() -> compileFtfSelectionPolicy(
				plan.owner(), plan.providerSelection(), purpose
			),
			failure -> new WorldgenPlans.SelectionDecoration(failure, List.of())
		);
		WorldgenPlans.SelectionDecoration finalized;
		if (policy.descriptor().state() == CapabilityState.UNAVAILABLE) {
			finalized = policy;
		} else if (mechanisms.stages().isEmpty()) {
			finalized = policy;
		} else {
			CapabilityState state = mechanisms.descriptor().state() == CapabilityState.PROVIDER_CONTRACT
				? CapabilityState.PROVIDER_CONTRACT
				: CapabilityState.NORMALIZED;
			PlanDescriptor descriptor = new PlanDescriptor(
				RTFCommon.location("runtime/selection_pipeline"),
				WorldgenFacet.SELECTION_DECORATION,
				state,
				"ordered_selection_pipeline",
				"Mechanism decorators execute in declared order before FTF's final selection policy",
				Optional.empty()
			);
			finalized = mechanisms.append(policy, descriptor);
		}

		WorldgenQueryMode queryMode = mechanisms.stages().isEmpty()
			? WorldgenQueryMode.ISOLATED_PARALLEL_READ
			: restrict(
				plan.execution().queryMode(WorldgenFacet.SELECTION_DECORATION),
				WorldgenQueryMode.ISOLATED_PARALLEL_READ
			);
		if (finalized.descriptor().state() == CapabilityState.UNAVAILABLE) {
			queryMode = WorldgenQueryMode.OWNER_SERIAL;
		}
		WorldgenExecution execution = plan.execution().withQueryMode(
			WorldgenFacet.SELECTION_DECORATION, queryMode
		);
		List<CapabilityNodeReport> reports = new ArrayList<>(plan.report().nodes());
		reports.removeIf(node -> node.facet() == WorldgenFacet.SELECTION_DECORATION
			&& node.id().equals(mechanisms.descriptor().id())
			&& mechanisms.stages().isEmpty());
		reports.add(policy.descriptor().report(plan.owner()));
		if (!finalized.descriptor().equals(policy.descriptor())) {
			reports.add(finalized.descriptor().report(plan.owner()));
		}
		return new WorldgenPlan(
			plan.owner(), plan.biomeComposition(), plan.providerSelection(),
			finalized, plan.spatialOwnership(), plan.samplerDecoration(), plan.densitySettings(),
			plan.surface(), plan.carvers(), plan.placedFeatures(), plan.structures(), execution,
			new WorldgenCapabilityReport(reports, execution, plan.report().providerDiagnostics())
		);
	}

	private static WorldgenPlans.SelectionDecoration compileFtfSelectionPolicy(
		WorldgenOwner owner,
		WorldgenPlans.ProviderSelection providers,
		WorldgenCompilationPurpose purpose
	) {
		if (providers.providers().isEmpty()) {
			return new WorldgenPlans.SelectionDecoration(
				unavailable(WorldgenFacet.SELECTION_DECORATION, "candidate_provider_missing",
					"The selected graph did not materialize exactly one FTF candidate-provider plan"),
				List.of()
			);
		}
		return compileProviderSelectionPolicy(owner, providers, purpose);
	}

	private static WorldgenPlans.SelectionDecoration compileProviderSelectionPolicy(
		WorldgenOwner owner,
		WorldgenPlans.ProviderSelection providers,
		WorldgenCompilationPurpose purpose
	) {
		ResourceLocation fallback = providers.rootCompositionDomain().orElseThrow(
			() -> new IllegalStateException("The executable candidate-provider plan has no root composition domain")
		);
		if (purpose == WorldgenCompilationPurpose.BIOME_PREVIEW) {
			Map<ResourceLocation, SurfaceBiomeFilter<Holder<Biome>>> filters = providers.providers().stream()
				.collect(java.util.stream.Collectors.toUnmodifiableMap(
					WorldgenPlans.ProviderDomain::id,
					domain -> surfaceFilter(domain.candidates())
				));
			return selectionPolicy(
				"Provider-neutral surface filtering executes after all mechanism decorators",
				(result, spatial, target, quartX, quartY, quartZ, sampler, surfaceContext) -> {
					ResourceLocation domain = result.usedFallback() ? fallback : result.domain();
					SurfaceBiomeFilter<Holder<Biome>> filter = filters.get(domain);
					if (filter == null) {
						throw new IllegalStateException("No surface policy for candidate domain " + domain);
					}
					return filter.resolve(target, result.biome());
				}
			);
		}
		Preset preset = selectionPreset(owner);
		Map<ResourceLocation, UndergroundBiomeBanding.Layout<Holder<Biome>>> layouts = providers.providers().stream()
			.collect(java.util.stream.Collectors.toUnmodifiableMap(
				WorldgenPlans.ProviderDomain::id,
				domain -> UndergroundBiomeBanding.apply(
					preset, domain.candidates(), owner.seed()
				)
			));
		return selectionPolicy(
			"Provider-neutral FTF underground policy executes after all mechanism decorators",
			(result, spatial, target, quartX, quartY, quartZ, sampler, surfaceContext) -> {
				Holder<Biome> selected = result.biome();
				if (!selected.equals(result.baseBiome())) {
					return selected;
				}
				ResourceLocation domain = result.usedFallback() ? fallback : result.domain();
				UndergroundBiomeBanding.Layout<Holder<Biome>> layout = layouts.get(domain);
				if (layout == null) {
					throw new IllegalStateException("No underground policy for candidate domain " + domain);
				}
				return applyUndergroundPolicy(
					preset, layout, selected, target, quartX, quartY, quartZ, sampler, surfaceContext
				);
			}
		);
	}

	private static WorldgenPlans.SelectionDecoration selectionPolicy(
		String detail,
		WorldgenPlans.BiomeSelectionDecorator decorator
	) {
		return new WorldgenPlans.SelectionDecoration(
			new PlanDescriptor(
				RTFCommon.location("runtime/ftf_selection_policy"),
				WorldgenFacet.SELECTION_DECORATION,
				CapabilityState.NORMALIZED,
				"ftf_selection_policy",
				detail,
				Optional.empty()
			),
			List.of(new WorldgenPlans.SelectionDecoratorStage(
				RTFCommon.location("ftf_selection_policy"), 0, decorator
			))
		);
	}

	private static Preset selectionPreset(WorldgenOwner owner) {
		return owner.lookups().lookupOrThrow(RTFRegistries.PRESET)
			.get(Preset.KEY)
			.orElseThrow(() -> new IllegalStateException("FTF selection decoration requires the selected preset"))
			.value();
	}

	private static SurfaceBiomeFilter<Holder<Biome>> surfaceFilter(
		Climate.ParameterList<Holder<Biome>> candidates
	) {
		SurfaceBiomeFilter<Holder<Biome>> filter = SurfaceBiomeFilter.create(
			candidates,
			(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value)),
			UndergroundBiomeTags::isCave,
			List.of(),
			candidates.values().getFirst().getSecond()
		);
		if (!filter.hasSurfaceCandidate()) {
			throw new IllegalStateException("Selected candidate domain exposes no sound surface biome");
		}
		return filter;
	}

	private static Holder<Biome> applyUndergroundPolicy(
		Preset preset,
		UndergroundBiomeBanding.Layout<Holder<Biome>> layout,
		Holder<Biome> selected,
		Climate.TargetPoint target,
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler,
		GeneratorContext surfaceContext
	) {
		float coverage = UndergroundBiomeSurfaceProtection.coverageFactor(
			sampler, target, quartX, quartY, quartZ, surfaceContext
		);
		if (layout.appliesAt(target)) {
			return layout.findValue(target, quartX, quartY, quartZ, coverage);
		}
		if ((coverage <= 0.0F || preset.climate().biomeShape.undergroundBiomeCoverage() <= 0.0F)
			&& layout.isCaveCandidate(selected)) {
			return layout.backgroundValue(target);
		}
		if (coverage < 1.0F && layout.isCaveCandidate(selected)) {
			return layout.backgroundValue(target);
		}
		return selected;
	}

	private static WorldgenQueryMode restrict(WorldgenQueryMode first, WorldgenQueryMode second) {
		return first.supportsIsolatedParallelRead() && second.supportsIsolatedParallelRead()
			? WorldgenQueryMode.ISOLATED_PARALLEL_READ
			: WorldgenQueryMode.OWNER_SERIAL;
	}

	private static WorldgenPlans.PlacedFeatures compilePlacedFeatures(
		WorldgenOwner owner,
		ChunkGenerator generator,
		List<Holder<Biome>> biomes
	) {
		List<WorldgenPlans.PlacedFeaturePipeline> pipelines = new ArrayList<>();
		Map<PlacedFeature, SurfacePlacementClassifier.Classification> surfaceClassifications =
			new IdentityHashMap<>();
		Map<PlacedFeature, ChunkLocalPlacementClassifier.Classification> chunkLocalClassifications =
			new IdentityHashMap<>();
		Map<ResourceKey<Biome>, List<net.minecraft.core.HolderSet<PlacedFeature>>> featuresByBiome =
			new LinkedHashMap<>();
		List<Holder.Reference<BiomeModifier>> modifiers = owner.lookups()
			.lookup(RTFRegistries.BIOME_MODIFIER)
			.map(lookup -> lookup.listElements()
				.sorted(Comparator.comparing(holder -> holder.key().location().toString()))
				.toList())
			.orElse(List.of());
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings =
			compileRegisteredBiomeGenerationSettings(owner, generator, modifiers);
		for (Holder<Biome> biome : biomes) {
			ResourceKey<Biome> biomeKey = biome.unwrapKey().orElseThrow(
				() -> new IllegalStateException("Selected biome is not registry-keyed")
			);
			List<net.minecraft.core.HolderSet<PlacedFeature>> steps = generationSettings.get(biomeKey)
				.features()
				.stream()
				.map(step -> net.minecraft.core.HolderSet.direct(step.stream().toList()))
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
			featuresByBiome.put(biomeKey, List.copyOf(steps));
			for (int step = 0; step < steps.size(); step++) {
				int index = 0;
				for (Holder<PlacedFeature> placed : steps.get(step)) {
					PlacedFeature value = placed.value();
					SurfacePlacementClassifier.Classification classification =
						SurfacePlacementClassifier.classify(
							value,
							placed.unwrapKey().map(ResourceKey::location),
							owner.lookups()
						);
					surfaceClassifications.merge(
						value, classification, MinecraftWorldgenPlanCompiler::mergeSurfaceClassification
					);
					ChunkLocalPlacementClassifier.Classification chunkLocal =
						ChunkLocalPlacementClassifier.classify(
							value,
							placed.unwrapKey().map(ResourceKey::location),
							owner.lookups()
						);
					chunkLocalClassifications.merge(
						value, chunkLocal, MinecraftWorldgenPlanCompiler::mergeChunkLocalClassification
					);
					pipelines.add(new WorldgenPlans.PlacedFeaturePipeline(
						biomeKey, step, index++, placed
					));
				}
			}
		}
		List<FeatureSorter.StepFeatureData> steps = FeatureSorter.buildFeaturesPerStep(
			biomes,
			biome -> featuresByBiome.get(biome.unwrapKey().orElseThrow(
				() -> new IllegalStateException("Feature sort selected an unkeyed biome")
			)),
			true
		);
		DynamicOrePlan ores = new DynamicOrePlanner().build(
			owner.lookups(),
			pipelines.stream().map(WorldgenPlans.PlacedFeaturePipeline::placedFeature)::iterator,
			new DynamicOrePlan.VerticalFrame(
				owner.selectedStem().type().value().minY(),
				owner.selectedStem().type().value().minY() + owner.selectedStem().type().value().height() - 1,
				generator.getSeaLevel()
			)
		);
		return new WorldgenPlans.PlacedFeatures(
			descriptor(WorldgenFacet.PLACED_FEATURES, CapabilityState.NORMALIZED,
				"Each occurrence and the exact cross-biome feature-sort schedule are immutable typed plan data"),
			pipelines,
			steps,
			surfaceClassifications,
			chunkLocalClassifications,
			ores,
			generationSettings
		);
	}

	static Map<ResourceKey<Biome>, BiomeGenerationSettings> compileRegisteredBiomeGenerationSettings(
		WorldgenOwner owner,
		ChunkGenerator generator,
		List<Holder.Reference<BiomeModifier>> modifiers
	) {
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings = new LinkedHashMap<>();
		for (Holder<Biome> biome : sortedBiomes(
			owner, registryHolders(owner.registries().registryOrThrow(Registries.BIOME))
		)) {
			ResourceKey<Biome> biomeKey = biome.unwrapKey().orElseThrow(
				() -> new IllegalStateException("Registered biome is not registry-keyed")
			);
			generationSettings.put(
				biomeKey, compileGenerationSettings(owner, generator, biome, modifiers)
			);
		}
		return Map.copyOf(generationSettings);
	}

	private static BiomeGenerationSettings compileGenerationSettings(
		WorldgenOwner owner,
		ChunkGenerator generator,
		Holder<Biome> biome,
		List<Holder.Reference<BiomeModifier>> modifiers
	) {
		BiomeGenerationSettings realized = realizedGenerationSettings(generator, biome);
		List<net.minecraft.core.HolderSet<PlacedFeature>> features = realized.features()
			.stream()
			.map(step -> net.minecraft.core.HolderSet.direct(step.stream().toList()))
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		for (Holder.Reference<BiomeModifier> modifier : modifiers) {
			int step = modifier.value().step().ordinal();
			while (features.size() <= step) {
				features.add(net.minecraft.core.HolderSet.direct());
			}
			List<Holder<PlacedFeature>> updated = modifier.value().apply(
				biome, features.get(step).stream().toList(), owner.lookups()
			);
			features.set(step, net.minecraft.core.HolderSet.direct(updated));
		}
		BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
		for (GenerationStep.Carving step : GenerationStep.Carving.values()) {
			realized.getCarvers(step).forEach(carver -> builder.addCarver(step, carver));
		}
		for (int step = 0; step < features.size(); step++) {
			for (Holder<PlacedFeature> feature : features.get(step)) {
				builder.addFeature(step, feature);
			}
		}
		return builder.build();
	}

	private static SurfacePlacementClassifier.Classification mergeSurfaceClassification(
		SurfacePlacementClassifier.Classification first,
		SurfacePlacementClassifier.Classification second
	) {
		if (first.eligible() && second.eligible()
			&& !first.pipeline().featureId().equals(second.pipeline().featureId())) {
			return SurfacePlacementClassifier.Classification.rejected(
				"CONFLICTING_PLACED_FEATURE_IDENTITIES"
			);
		}
		return first;
	}

	private static ChunkLocalPlacementClassifier.Classification mergeChunkLocalClassification(
		ChunkLocalPlacementClassifier.Classification first,
		ChunkLocalPlacementClassifier.Classification second
	) {
		if (first.eligible() && second.eligible()
			&& first.confinement().featureId().equals(second.confinement().featureId())) {
			return first;
		}
		if (!first.eligible() && !second.eligible()) {
			return first;
		}
		return ChunkLocalPlacementClassifier.Classification.rejected(
			"CONFLICTING_CHUNK_LOCAL_PLACEMENT_IDENTITIES"
		);
	}

	private static BiomeGenerationSettings realizedGenerationSettings(
		ChunkGenerator generator,
		Holder<Biome> biome
	) {
		if (generator instanceof TerraForgedChunkGenerator terraForged) {
			return terraForged.realizedBiomeGenerationSettings(biome);
		}
		return generator.getBiomeGenerationSettings(biome);
	}

	private static WorldgenPlans.Structures compileStructures(WorldgenOwner owner) {
		return new WorldgenPlans.Structures(
			descriptor(WorldgenFacet.STRUCTURES, CapabilityState.NORMALIZED,
				"Structure sets, structures, pools, processors, and FTF rules retain selected-registry execution order"),
			registryHolders(owner.registries().registryOrThrow(Registries.STRUCTURE)),
			registryHolders(owner.registries().registryOrThrow(Registries.STRUCTURE_SET)),
			registryHolders(owner.registries().registryOrThrow(Registries.TEMPLATE_POOL)),
			registryHolders(owner.registries().registryOrThrow(Registries.PROCESSOR_LIST)),
			registryHolders(owner.registries().registryOrThrow(RTFRegistries.STRUCTURE_RULE))
		);
	}

	private static List<Holder<Biome>> sortedBiomes(
		WorldgenOwner owner,
		Collection<? extends Holder<Biome>> biomes
	) {
		Registry<Biome> registry = owner.registries().registryOrThrow(Registries.BIOME);
		return biomes.stream()
			.<Holder<Biome>>map(biome -> biome)
			.sorted(Comparator.comparing(holder -> holder.unwrapKey()
				.or(() -> registry.getResourceKey(holder.value()))
				.map(key -> key.location().toString())
				.orElseThrow(() -> new IllegalStateException("Selected biome has no registry identity"))))
			.toList();
	}

	static Collection<Holder<Biome>> biomesRequiredByGraphFacets(WorldgenPlan plan) {
		return WorldgenBiomeSelection.possibleBiomes(plan);
	}

	private static <T> List<Holder.Reference<T>> registryHolders(Registry<T> registry) {
		return registry.holders().toList();
	}

	private static void addCarverReports(
		WorldgenOwner owner,
		WorldgenPlans.Carvers carvers,
		List<CapabilityNodeReport> reports
	) {
		int occurrences = carvers.pipelines().size();
		if (occurrences > 0) {
			reports.add(new CapabilityNodeReport(
				RTFCommon.location("runtime/carver_leaves"), WorldgenFacet.CARVERS,
				CapabilityState.OPAQUE_LEAF, "minecraft_executable_interface", owner.type(),
				occurrences + " ordered ConfiguredWorldCarver occurrences execute through the public carve boundary",
				Optional.empty()
			));
		}
	}

	private static void addFeatureReports(
		WorldgenOwner owner,
		WorldgenPlans.PlacedFeatures features,
		List<CapabilityNodeReport> reports
	) {
		int occurrences = features.pipelines().size();
		if (occurrences == 0) {
			return;
		}
		int surfaceEligible = 0;
		int chunkLocalEligible = 0;
		for (WorldgenPlans.PlacedFeaturePipeline pipeline : features.pipelines()) {
			PlacedFeature feature = pipeline.placedFeature().value();
			SurfacePlacementClassifier.Classification surface = features.surfaceClassification(feature);
			if (surface.eligible()) {
				surfaceEligible++;
			}
			if (features.chunkLocalClassification(feature).eligible()) {
				chunkLocalEligible++;
			}
		}
		int passthrough = occurrences - surfaceEligible;
		reports.add(new CapabilityNodeReport(
			RTFCommon.location("runtime/placed_feature_leaves"), WorldgenFacet.PLACED_FEATURES,
			CapabilityState.OPAQUE_LEAF, "minecraft_executable_interface", owner.type(),
			occurrences + " ordered placed-feature occurrences execute through the public place boundary; "
				+ "surface_rescue supported=" + surfaceEligible + ", passthrough=" + passthrough
				+ "; chunk_local_placement supported=" + chunkLocalEligible,
			Optional.empty()
		));
	}

	private static void addStructureReports(
		WorldgenOwner owner,
		WorldgenPlans.Structures structures,
		List<CapabilityNodeReport> reports
	) {
		int registered = structures.structures().size();
		int rules = structures.rules().size();
		if (registered > 0 || rules > 0) {
			reports.add(new CapabilityNodeReport(
				RTFCommon.location("runtime/structure_leaves"), WorldgenFacet.STRUCTURES,
				CapabilityState.OPAQUE_LEAF, "minecraft_executable_interface", owner.type(),
				registered + " registered structures execute through the public generate boundary; "
					+ rules + " FTF structure rules execute from the immutable owner plan",
				Optional.empty()
			));
		}
	}

	private static PlanDescriptor descriptor(WorldgenFacet facet, CapabilityState state, String detail) {
		return new PlanDescriptor(
			RTFCommon.location("runtime/" + facet.name().toLowerCase()), facet, state,
			REGISTRY_GRAPH, detail, Optional.empty()
		);
	}

	private static PlanDescriptor notMaterialized(
		WorldgenFacet facet,
		WorldgenCompilationPurpose purpose
	) {
		return new PlanDescriptor(
			RTFCommon.location("runtime/" + facet.name().toLowerCase()), facet, CapabilityState.OPAQUE_ROOT,
			"selected_generator_root",
			"Facet remains in the selected generator because compilation purpose " + purpose + " cannot execute it",
			Optional.empty()
		);
	}

	private static PlanDescriptor pendingGraphMaterialization(WorldgenFacet facet) {
		return new PlanDescriptor(
			RTFCommon.location("runtime/" + facet.name().toLowerCase()), facet, CapabilityState.OPAQUE_ROOT,
			REGISTRY_GRAPH,
			"Facet awaits the finalized unified biome graph",
			Optional.empty()
		);
	}

	private static PlanDescriptor unavailable(WorldgenFacet facet, String code, String message) {
		return new PlanDescriptor(
			RTFCommon.location("runtime/" + facet.name().toLowerCase()), facet, CapabilityState.UNAVAILABLE,
			REGISTRY_GRAPH, message, Optional.of(CapabilityFailure.unavailable(code, message))
		);
	}

	private static <T extends WorldgenPlans.DomainPlan> T compileFacet(
		WorldgenFacet facet,
		Supplier<T> compiler,
		Function<PlanDescriptor, T> unavailableFactory
	) {
		try {
			return compiler.get();
		} catch (CancellationException failure) {
			throw failure;
		} catch (RuntimeException | LinkageError failure) {
			PlanDescriptor descriptor = new PlanDescriptor(
				RTFCommon.location("runtime/" + facet.name().toLowerCase()), facet, CapabilityState.UNAVAILABLE,
				REGISTRY_GRAPH, "Facet compilation failed without changing independent facets",
				Optional.of(CapabilityFailure.of("registry_graph_compile_failed", failure))
			);
			return unavailableFactory.apply(descriptor);
		}
	}
}
