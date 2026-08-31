package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
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
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection;
import raccoonman.reterraforged.world.worldgen.biome.SurfaceBiomeFilter;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeTags;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.feature.placement.SurfacePlacementClassifier;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlanner;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifier;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;

/** Compiles the final selected Minecraft graph without cloning roots or inspecting object fields. */
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
		long started = System.nanoTime();
		WorldgenPlan base = compileBase(owner, purpose);
		WorldgenPlan negotiated = new WorldgenPlanCompiler(providers).compile(
			base, purpose
		);
		WorldgenPlan selected = finalizeSelectionPipeline(
			materializeCandidateOwnership(negotiated), purpose
		);
		WorldgenPlan plan = materializeGraphDependentFacets(base, selected, purpose);
		Map<CapabilityState, Long> states = plan.report().nodes().stream().collect(
			java.util.stream.Collectors.groupingBy(
				CapabilityNodeReport::state,
				() -> new java.util.EnumMap<>(CapabilityState.class),
				java.util.stream.Collectors.counting()
			)
		);
		RTFCommon.LOGGER.info(
			"Compiled worldgen plan owner={} type={} purpose={} tag_epoch={} contribution_epoch={} elapsed_ms={} capability_nodes={} states={}",
			owner.id(),
			owner.type(),
			purpose,
			owner.tagEpoch().sequence(),
			owner.contributionSequence(),
			String.format(java.util.Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1_000_000.0D),
			plan.report().nodes().size(),
			states
		);
		return plan;
	}

	private static WorldgenPlan compileBase(WorldgenOwner owner, WorldgenCompilationPurpose purpose) {
		ChunkGenerator generator = owner.selectedStem().generator();
		BiomeSource source = MinecraftBiomeSourceGraphs.acquisitionSource(generator);
		List<CapabilityNodeReport> reports = new ArrayList<>();

		WorldgenPlans.BiomeComposition biomePlan = compileFacet(
			WorldgenFacet.BIOME_COMPOSITION,
			() -> compileBiomes(owner, source),
			failure -> new WorldgenPlans.BiomeComposition(failure, List.of())
		);
		WorldgenPlans.ProviderSelection providerPlan = new WorldgenPlans.ProviderSelection(
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
		WorldgenPlans.SpatialOwnership spatialPlan = new WorldgenPlans.SpatialOwnership(
			unavailable(WorldgenFacet.SPATIAL_OWNERSHIP, "no_spatial_contract",
				"The selected biome source does not expose provider-domain ownership"),
			Optional.empty()
		);
		WorldgenPlans.SamplerDecoration samplerPlan = new WorldgenPlans.SamplerDecoration(
			descriptor(WorldgenFacet.SAMPLER_DECORATION, CapabilityState.NORMALIZED,
				"Realized and request-owned samplers receive the same owner-local plan and FTF state"),
			Optional.of((plan, inputs, sampler) -> {
				if (!plan.owner().id().equals(owner.id())) {
					throw new IllegalArgumentException("Sampler decorator received a plan for a different owner");
				}
				if (!((Object) sampler instanceof RTFClimateSampler rtfSampler)) {
					throw new IllegalArgumentException("Climate sampler does not expose the FTF public sampler contract");
				}
				rtfSampler.setUndergroundBiomeBandingPreset(inputs.preset(), owner.seed());
				rtfSampler.setUndergroundBiomeSurfaceContext(inputs.generatorContext());
				return sampler;
			})
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
				notMaterialized(WorldgenFacet.STRUCTURES, purpose), List.of(), List.of(), List.of(), List.of()
			)
			: compileFacet(
			WorldgenFacet.STRUCTURES,
			() -> compileStructures(owner),
			failure -> new WorldgenPlans.Structures(failure, List.of(), List.of(), List.of(), List.of())
		);

		List<WorldgenPlans.DomainPlan> plans = List.of(
			biomePlan, providerPlan, decorationPlan, spatialPlan, samplerPlan,
			densityPlan, surfacePlan, carverPlan, featurePlan, structurePlan
		);
		plans.forEach(plan -> reports.add(plan.descriptor().report(owner)));
		addCarverReports(owner, carverPlan, reports);
		addFeatureReports(owner, featurePlan, reports);
		addStructureReports(owner, structurePlan, reports);
		WorldgenExecution execution = WorldgenExecution.serial();
		return new WorldgenPlan(
			owner, biomePlan, providerPlan, decorationPlan, spatialPlan, samplerPlan,
			densityPlan, surfacePlan, carverPlan, featurePlan, structurePlan,
			execution, new WorldgenCapabilityReport(reports, execution)
		);
	}

	private static WorldgenPlans.BiomeComposition compileBiomes(WorldgenOwner owner, BiomeSource source) {
		if (source instanceof MultiNoiseBiomeSource) {
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries =
				MinecraftBiomeSourceGraphs.multiNoiseEntries(source, owner.registries());
			return new WorldgenPlans.BiomeComposition(
				descriptor(WorldgenFacet.BIOME_COMPOSITION, CapabilityState.NORMALIZED,
					"Public multi-noise parameter entries were copied into immutable plan data"),
				entries
			);
		}
		return new WorldgenPlans.BiomeComposition(
			unavailable(WorldgenFacet.BIOME_COMPOSITION, "source_composition_opaque",
				"The selected source exposes only its public positional query boundary"),
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
			selected.execution(), new WorldgenCapabilityReport(reports, selected.execution())
		);
	}

	static WorldgenPlan materializeCandidateOwnership(WorldgenPlan plan) {
		WorldgenPlans.BiomeComposition composition = plan.biomeComposition();
		WorldgenPlans.BiomeComposition candidateOperations = composition;
		WorldgenPlans.ProviderSelection providers = plan.providerSelection();
		WorldgenPlans.SpatialOwnership spatial = plan.spatialOwnership();
		boolean compositionChanged = false;
		boolean providerChanged = false;
		boolean spatialChanged = false;

		if (!composition.entries().isEmpty() && !candidateOperations.stages().isEmpty()
			&& composition.descriptor().state() != CapabilityState.UNAVAILABLE) {
			composition = new WorldgenPlans.BiomeComposition(
				composition.descriptor(), candidateOperations.applyTo(composition.entries())
			);
			compositionChanged = true;
		}

		if (providers.providers().isEmpty() && !composition.entries().isEmpty()
			&& composition.descriptor().state() != CapabilityState.UNAVAILABLE) {
			ResourceLocation id = RTFCommon.location("selected_source_root");
			WorldgenPlans.ProviderDomain domain = new WorldgenPlans.ProviderDomain(
				id, 1.0D, new Climate.ParameterList<>(composition.entries()), 0
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
			List<WorldgenPlans.ProviderDomain> transformed = providers.providers().stream()
				.map(domain -> new WorldgenPlans.ProviderDomain(
					domain.id(), domain.weight(),
					domain.id().equals(rootDomain)
						? new Climate.ParameterList<>(rootEntries)
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
		WorldgenExecution execution = plan.execution()
			.withQueryMode(WorldgenFacet.PROVIDER_SELECTION, WorldgenQueryMode.ISOLATED_PARALLEL_READ)
			.withQueryMode(WorldgenFacet.SPATIAL_OWNERSHIP, WorldgenQueryMode.ISOLATED_PARALLEL_READ);
		return new WorldgenPlan(
			plan.owner(), composition, providers,
			plan.selectionDecoration(), spatial, plan.samplerDecoration(), plan.densitySettings(),
			plan.surface(), plan.carvers(), plan.placedFeatures(), plan.structures(), execution,
			new WorldgenCapabilityReport(reports, execution)
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
			new WorldgenCapabilityReport(reports, execution)
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
					domain -> surfaceFilter(domain.candidates().values())
				));
			return selectionPolicy(
				"Provider-neutral surface filtering executes after all mechanism decorators",
				(result, spatial, target, quartX, quartY, quartZ, sampler) -> {
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
					preset, domain.candidates().values(), owner.seed()
				)
			));
		return selectionPolicy(
			"Provider-neutral FTF underground policy executes after all mechanism decorators",
			(result, spatial, target, quartX, quartY, quartZ, sampler) -> {
				Holder<Biome> selected = result.biome();
				if (!selected.equals(result.baseBiome())) {
					return selected;
				}
				ResourceLocation domain = result.usedFallback() ? fallback : result.domain();
				UndergroundBiomeBanding.Layout<Holder<Biome>> layout = layouts.get(domain);
				if (layout == null) {
					throw new IllegalStateException("No underground policy for candidate domain " + domain);
				}
				return applyUndergroundPolicy(preset, layout, selected, target, quartX, quartY, quartZ, sampler);
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
				RTFCommon.location("ftf_selection_policy"), decorator
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
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries
	) {
		SurfaceBiomeFilter<Holder<Biome>> filter = SurfaceBiomeFilter.create(
			entries,
			(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value)),
			UndergroundBiomeTags::isCave,
			List.of(),
			entries.getFirst().getSecond()
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
		Climate.Sampler sampler
	) {
		float coverage = UndergroundBiomeSurfaceProtection.coverageFactor(
			sampler, target, quartX, quartY, quartZ
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
		Map<ResourceKey<Biome>, List<net.minecraft.core.HolderSet<PlacedFeature>>> featuresByBiome =
			new LinkedHashMap<>();
		List<Holder.Reference<BiomeModifier>> modifiers = owner.lookups()
			.lookup(RTFRegistries.BIOME_MODIFIER)
			.map(lookup -> lookup.listElements()
				.sorted(Comparator.comparing(holder -> holder.key().location().toString()))
				.toList())
			.orElse(List.of());
		for (Holder<Biome> biome : biomes) {
			ResourceKey<Biome> biomeKey = biome.unwrapKey().orElseThrow(
				() -> new IllegalStateException("Selected biome is not registry-keyed")
			);
			List<net.minecraft.core.HolderSet<PlacedFeature>> steps = realizedGenerationSettings(generator, biome)
				.features()
				.stream()
				.map(step -> net.minecraft.core.HolderSet.direct(step.stream().toList()))
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
			for (Holder.Reference<BiomeModifier> modifier : modifiers) {
				int step = modifier.value().step().ordinal();
				while (steps.size() <= step) {
					steps.add(net.minecraft.core.HolderSet.direct());
				}
				List<Holder<PlacedFeature>> updated = modifier.value().apply(
					biome, steps.get(step).stream().toList(), owner.lookups()
				);
				steps.set(step, net.minecraft.core.HolderSet.direct(updated));
			}
			featuresByBiome.put(biomeKey, List.copyOf(steps));
			for (int step = 0; step < steps.size(); step++) {
				int index = 0;
				for (Holder<PlacedFeature> placed : steps.get(step)) {
					PlacedFeature value = placed.value();
					surfaceClassifications.computeIfAbsent(
						value,
						feature -> SurfacePlacementClassifier.classify(feature, owner.lookups())
					);
					pipelines.add(new WorldgenPlans.PlacedFeaturePipeline(
						biomeKey, step, index++, placed, value.feature(), value.placement(),
						value.feature().value().getFeatures().toList()
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
			ores
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
				"Structure sets, structures, pools, and processors retain selected-registry execution order"),
			registryHolders(owner.registries().registryOrThrow(Registries.STRUCTURE)),
			registryHolders(owner.registries().registryOrThrow(Registries.STRUCTURE_SET)),
			registryHolders(owner.registries().registryOrThrow(Registries.TEMPLATE_POOL)),
			registryHolders(owner.registries().registryOrThrow(Registries.PROCESSOR_LIST))
		);
	}

	private static List<Holder<Biome>> sortedBiomes(
		WorldgenOwner owner,
		Collection<Holder<Biome>> biomes
	) {
		Registry<Biome> registry = owner.registries().registryOrThrow(Registries.BIOME);
		return biomes.stream()
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
		for (WorldgenPlans.CarverPipeline pipeline : carvers.pipelines()) {
			reports.add(new CapabilityNodeReport(
				occurrence("carver", pipeline.biome(), pipeline.step().ordinal(), pipeline.index()),
				WorldgenFacet.CARVERS, CapabilityState.OPAQUE_LEAF, "minecraft_executable_interface",
				owner.type(), "ConfiguredWorldCarver public carve boundary", Optional.empty()
			));
		}
	}

	private static void addFeatureReports(
		WorldgenOwner owner,
		WorldgenPlans.PlacedFeatures features,
		List<CapabilityNodeReport> reports
	) {
		for (WorldgenPlans.PlacedFeaturePipeline pipeline : features.pipelines()) {
			reports.add(new CapabilityNodeReport(
				occurrence("feature", pipeline.biome(), pipeline.generationStep(), pipeline.index()),
				WorldgenFacet.PLACED_FEATURES, CapabilityState.OPAQUE_LEAF, "minecraft_executable_interface",
				owner.type(), "Placed modifier pipeline and configured Feature public place boundary", Optional.empty()
			));
		}
	}

	private static void addStructureReports(
		WorldgenOwner owner,
		WorldgenPlans.Structures structures,
		List<CapabilityNodeReport> reports
	) {
		for (Holder.Reference<Structure> structure : structures.structures()) {
			reports.add(new CapabilityNodeReport(
				structure.key().location(), WorldgenFacet.STRUCTURES, CapabilityState.OPAQUE_LEAF,
				"minecraft_executable_interface", owner.type(), "Registered Structure public generate boundary", Optional.empty()
			));
		}
	}

	private static ResourceLocation occurrence(String domain, ResourceKey<Biome> biome, int step, int index) {
		ResourceLocation id = biome.location();
		return RTFCommon.location("runtime/" + domain + "/" + id.getNamespace() + "/" + id.getPath() + "/" + step + "/" + index);
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
