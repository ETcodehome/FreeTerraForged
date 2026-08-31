package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;

class WorldgenPlanCompilerTest {
	@Test
	void rejectsDuplicateProviderIds() {
		Provider first = provider("duplicate", Set.of(WorldgenFacet.SURFACE));
		Provider second = provider("duplicate", Set.of(WorldgenFacet.CARVERS));
		assertThrows(PlanCompilationException.class, () -> new WorldgenPlanCompiler(List.of(first, second)));
	}

	@Test
	void rejectsUnknownOrderTargets() {
		Provider provider = provider("one", Set.of());
		provider.ordering = List.of(new ProviderOrder(provider.id(), id("missing")));
		assertThrows(PlanCompilationException.class, () -> new WorldgenPlanCompiler(List.of(provider)));
	}

	@Test
	void ignoresMissingEndpointsOfOptionalCrossMechanismOrder() {
		Provider provider = provider("one", Set.of());
		provider.ordering = List.of(ProviderOrder.optional(provider.id(), id("optional_peer")));

		assertEquals(
			List.of(provider.id()),
			new WorldgenPlanCompiler(List.of(provider)).providers().stream()
				.map(WorldgenCapabilityProvider::id).toList()
		);
	}

	@Test
	void rejectsCycles() {
		Provider first = provider("first", Set.of());
		Provider second = provider("second", Set.of());
		first.ordering = List.of(new ProviderOrder(first.id(), second.id()));
		second.ordering = List.of(new ProviderOrder(second.id(), first.id()));
		assertThrows(PlanCompilationException.class, () -> new WorldgenPlanCompiler(List.of(first, second)));
	}

	@Test
	void orderingIsDeterministicAndHonorsConstraints() {
		Provider alpha = provider("alpha", Set.of());
		Provider beta = provider("beta", Set.of());
		Provider gamma = provider("gamma", Set.of());
		gamma.ordering = List.of(new ProviderOrder(gamma.id(), alpha.id()));

		List<ResourceLocation> ordered = new WorldgenPlanCompiler(List.of(beta, alpha, gamma)).providers()
			.stream().map(WorldgenCapabilityProvider::id).toList();
		assertEquals(List.of(beta.id(), gamma.id(), alpha.id()), ordered);
		assertEquals(
			ordered,
			new WorldgenPlanCompiler(List.of(gamma, beta, alpha)).providers()
				.stream().map(WorldgenCapabilityProvider::id).toList()
		);
	}

	@Test
	void providerFailureIsBoundedToItsFacetAndRetainsFirstCause() {
		Provider provider = provider("partial", Set.of(WorldgenFacet.DENSITY_SETTINGS, WorldgenFacet.SURFACE));
		provider.compiler = facet -> {
			if (facet == WorldgenFacet.SURFACE) {
				throw new IllegalStateException("surface snapshot failed");
			}
			return Optional.of(new WorldgenPlans.DensitySettings(
				descriptor(provider.id(), facet, CapabilityState.PROVIDER_CONTRACT), Optional.empty()
			));
		};

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(provider)).compile(emptyPlan());
		assertEquals(CapabilityState.PROVIDER_CONTRACT, result.densitySettings().descriptor().state());
		assertEquals(CapabilityState.UNAVAILABLE, result.surface().descriptor().state());
		assertEquals(CapabilityState.NORMALIZED, result.placedFeatures().descriptor().state());
		CapabilityFailure failure = result.report().firstCause(WorldgenFacet.SURFACE).orElseThrow();
		assertEquals("provider_compile_failed", failure.code());
		assertEquals("surface snapshot failed", failure.message());
	}

	@Test
	void nonApplicableProviderCannotCompileOrFailAFacet() {
		Provider provider = provider("installed_but_unused", Set.of(WorldgenFacet.SURFACE));
		provider.applicability = WorldgenApplicability.NOT_APPLICABLE;
		provider.compiler = facet -> {
			throw new AssertionError("non-applicable provider was compiled");
		};

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(provider)).compile(emptyPlan());

		assertEquals(CapabilityState.NORMALIZED, result.surface().descriptor().state());
		assertTrue(result.report().nodes().stream().noneMatch(node -> node.id().equals(provider.id())));
	}

	@Test
	void reportsAndInputsAreImmutable() {
		WorldgenPlan plan = emptyPlan();
		assertThrows(UnsupportedOperationException.class, () -> plan.report().nodes().clear());
		assertThrows(UnsupportedOperationException.class, () -> plan.carvers().pipelines().clear());
	}

	@Test
	void selectionDecoratorsComposeInDeterministicProviderOrder() {
		List<String> events = new ArrayList<>();
		Provider first = provider("first", Set.of(WorldgenFacet.SELECTION_DECORATION));
		Provider second = provider("second", Set.of(WorldgenFacet.SELECTION_DECORATION));
		first.queryMode = WorldgenQueryMode.ISOLATED_PARALLEL_READ;
		second.queryMode = WorldgenQueryMode.ISOLATED_PARALLEL_READ;
		first.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
			descriptor(first.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			List.of(stage(first.id(), events))
		));
		second.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
			descriptor(second.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			List.of(stage(second.id(), events))
		));

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(second, first)).compile(emptyPlan());
		Holder<Biome> biome = Holder.direct((Biome) null);
		result.selectionDecoration().apply(
			new WorldgenPlans.ProviderResult(id("root"), biome, false),
			new WorldgenPlans.SpatialResult(id("root"), 0, 0),
			Climate.target(0, 0, 0, 0, 0, 0), 0, 0, 0, null
		);

		assertEquals(List.of(first.id(), second.id()), result.selectionDecoration().orderedDecorators());
		assertEquals(List.of(first.id().toString(), second.id().toString()), events);
		assertEquals(CapabilityState.PROVIDER_CONTRACT, result.selectionDecoration().descriptor().state());
		assertEquals(
			WorldgenQueryMode.ISOLATED_PARALLEL_READ,
			result.execution().queryMode(WorldgenFacet.SELECTION_DECORATION)
		);
		assertEquals(result.execution(), result.report().execution());
	}

	@Test
	void candidateStagesRemainDeferredAndSortBySemanticOrder() {
		List<String> events = new ArrayList<>();
		Provider biolith = provider("biolith", Set.of(WorldgenFacet.BIOME_COMPOSITION));
		Provider lithostitched = provider("lithostitched", Set.of(WorldgenFacet.BIOME_COMPOSITION));
		biolith.ordering = List.of(ProviderOrder.optional(biolith.id(), lithostitched.id()));
		biolith.compiler = facet -> Optional.of(composition(
			biolith.id(), List.of(), 200, events
		));
		lithostitched.compiler = facet -> Optional.of(composition(
			lithostitched.id(), List.of(candidate()), 100, events
		));

		WorldgenPlans.BiomeComposition result = new WorldgenPlanCompiler(List.of(
			lithostitched, biolith
		)).compile(emptyPlan()).biomeComposition();

		assertTrue(events.isEmpty());
		assertEquals(List.of(lithostitched.id(), biolith.id()), result.stages().stream()
			.map(WorldgenPlans.CandidateCompositionStage::id).toList());
		assertEquals(1, result.applyTo(result.entries()).size());
		assertEquals(List.of(lithostitched.id().toString(), biolith.id().toString()), events);
	}

	@Test
	void candidateOperationsTransformOnlyTheDeclaredRootDomain() {
		WorldgenPlan base = emptyPlan();
		Holder<Biome> rootBiome = Holder.direct((Biome) null);
		Holder<Biome> peerBiome = Holder.direct((Biome) null);
		Holder<Biome> addition = Holder.direct((Biome) null);
		var point = Climate.parameters(0, 0, 0, 0, 0, 0, 0);
		ResourceLocation root = id("root");
		ResourceLocation peer = id("peer");
		WorldgenPlans.BiomeComposition composition = new WorldgenPlans.BiomeComposition(
			descriptor(id("composition"), WorldgenFacet.BIOME_COMPOSITION, CapabilityState.NORMALIZED),
			List.of(com.mojang.datafixers.util.Pair.of(point, rootBiome)),
			List.of(new WorldgenPlans.CandidateCompositionStage(
				id("addition"), 100, candidates -> java.util.stream.Stream.concat(
					candidates.stream(), java.util.stream.Stream.of(com.mojang.datafixers.util.Pair.of(point, addition))
				).toList()
			))
		);
		WorldgenPlans.ProviderSelection providers = new WorldgenPlans.ProviderSelection(
			descriptor(id("providers"), WorldgenFacet.PROVIDER_SELECTION, CapabilityState.PROVIDER_CONTRACT),
			1L,
			List.of(
				new WorldgenPlans.ProviderDomain(root, 1.0D, new Climate.ParameterList<>(List.of(
					com.mojang.datafixers.util.Pair.of(point, rootBiome)
				)), 0),
				new WorldgenPlans.ProviderDomain(peer, 1.0D, new Climate.ParameterList<>(List.of(
					com.mojang.datafixers.util.Pair.of(point, peerBiome)
				)), 1)
			),
			Optional.of(root), Optional.empty(), Optional.empty()
		);
		WorldgenPlan input = new WorldgenPlan(
			base.owner(), composition, providers, base.selectionDecoration(), base.spatialOwnership(),
			base.samplerDecoration(), base.densitySettings(), base.surface(), base.carvers(),
			base.placedFeatures(), base.structures(), base.execution(), base.report()
		);

		WorldgenPlan result = MinecraftWorldgenPlanCompiler.materializeCandidateOwnership(input);

		assertEquals(
			List.of(rootBiome, addition),
			result.providerSelection().providers().get(0).candidates().values().stream()
				.map(com.mojang.datafixers.util.Pair::getSecond).toList()
		);
		assertEquals(
			List.of(peerBiome),
			result.providerSelection().providers().get(1).candidates().values().stream()
				.map(com.mojang.datafixers.util.Pair::getSecond).toList()
		);
	}

	@Test
	void graphDependentFacetsUseEveryFinalSelectionOutput() {
		WorldgenPlan base = emptyPlan();
		Holder<Biome> compositionBiome = biome("composition");
		Holder<Biome> rootBiome = biome("root");
		Holder<Biome> peerBiome = biome("peer");
		Holder<Biome> fallbackBiome = biome("fallback");
		Holder<Biome> decoratedBiome = biome("decorated");
		var point = Climate.parameters(0, 0, 0, 0, 0, 0, 0);
		ResourceLocation root = id("root");
		WorldgenPlan selected = new WorldgenPlan(
			base.owner(),
			new WorldgenPlans.BiomeComposition(
				descriptor(id("composition"), WorldgenFacet.BIOME_COMPOSITION, CapabilityState.NORMALIZED),
				List.of(com.mojang.datafixers.util.Pair.of(point, compositionBiome))
			),
			new WorldgenPlans.ProviderSelection(
				descriptor(id("providers"), WorldgenFacet.PROVIDER_SELECTION, CapabilityState.NORMALIZED),
				1L,
				List.of(
					new WorldgenPlans.ProviderDomain(root, 1.0D, new Climate.ParameterList<>(List.of(
						com.mojang.datafixers.util.Pair.of(point, rootBiome)
					)), 0),
					new WorldgenPlans.ProviderDomain(id("peer"), 1.0D, new Climate.ParameterList<>(List.of(
						com.mojang.datafixers.util.Pair.of(point, peerBiome)
					)), 1)
				),
				Optional.of(root),
				Optional.of(new Climate.ParameterList<>(List.of(
					com.mojang.datafixers.util.Pair.of(point, fallbackBiome)
				))),
				Optional.empty()
			),
			new WorldgenPlans.SelectionDecoration(
				descriptor(id("decorators"), WorldgenFacet.SELECTION_DECORATION, CapabilityState.NORMALIZED),
				List.of(), Set.of(decoratedBiome)
			),
			base.spatialOwnership(), base.samplerDecoration(), base.densitySettings(), base.surface(),
			base.carvers(), base.placedFeatures(), base.structures(), base.execution(), base.report()
		);

		assertEquals(
			Set.of(compositionBiome, rootBiome, peerBiome, fallbackBiome, decoratedBiome),
			Set.copyOf(MinecraftWorldgenPlanCompiler.biomesRequiredByGraphFacets(selected))
		);
	}

	@Test
	void composedSelectionPipelineUsesMostRestrictiveQueryMode() {
		Provider parallel = provider("parallel", Set.of(WorldgenFacet.SELECTION_DECORATION));
		Provider serial = provider("serial", Set.of(WorldgenFacet.SELECTION_DECORATION));
		parallel.queryMode = WorldgenQueryMode.ISOLATED_PARALLEL_READ;
		for (Provider provider : List.of(parallel, serial)) {
			provider.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
				descriptor(provider.id(), facet, CapabilityState.PROVIDER_CONTRACT),
				List.of(stage(provider.id(), new ArrayList<>()))
			));
		}

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(serial, parallel)).compile(emptyPlan());

		assertEquals(
			WorldgenQueryMode.OWNER_SERIAL,
			result.execution().queryMode(WorldgenFacet.SELECTION_DECORATION)
		);
	}

	@Test
	void duplicateSelectionStageFailureIsBoundedAndActionable() {
		Provider first = provider("first", Set.of(WorldgenFacet.SELECTION_DECORATION));
		Provider second = provider("second", Set.of(WorldgenFacet.SELECTION_DECORATION));
		ResourceLocation duplicate = id("duplicate_stage");
		for (Provider provider : List.of(first, second)) {
			provider.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
				descriptor(provider.id(), facet, CapabilityState.PROVIDER_CONTRACT),
				List.of(stage(duplicate, new ArrayList<>()))
			));
		}

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(second, first)).compile(emptyPlan());

		assertEquals(CapabilityState.UNAVAILABLE, result.selectionDecoration().descriptor().state());
		CapabilityFailure failure = result.report().firstCause(WorldgenFacet.SELECTION_DECORATION).orElseThrow();
		assertEquals("provider_compile_failed", failure.code());
		assertTrue(failure.message().contains("stage IDs must be unique"));
	}

	@Test
	void candidateProviderAndIndependentDecoratorRemainPeerMechanisms() {
		Provider candidate = provider("candidate", Set.of(
			WorldgenFacet.PROVIDER_SELECTION, WorldgenFacet.SPATIAL_OWNERSHIP
		));
		candidate.compiler = facet -> switch (facet) {
			case PROVIDER_SELECTION -> Optional.of(new WorldgenPlans.ProviderSelection(
				descriptor(candidate.id(), facet, CapabilityState.PROVIDER_CONTRACT),
				1L, List.of(), Optional.empty(), Optional.empty(), Optional.empty()
			));
			case SPATIAL_OWNERSHIP -> Optional.of(new WorldgenPlans.SpatialOwnership(
				descriptor(candidate.id(), facet, CapabilityState.PROVIDER_CONTRACT), Optional.empty()
			));
			default -> Optional.empty();
		};
		Provider decorator = provider("decorator", Set.of(WorldgenFacet.SELECTION_DECORATION));
		decorator.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
			descriptor(decorator.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			List.of(stage(decorator.id(), new ArrayList<>()))
		));

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(decorator, candidate)).compile(emptyPlan());

		assertEquals(candidate.id(), result.providerSelection().descriptor().id());
		assertEquals(candidate.id(), result.spatialOwnership().descriptor().id());
		assertEquals(List.of(decorator.id()), result.selectionDecoration().orderedDecorators());
		assertTrue(result.report().firstCause(WorldgenFacet.SELECTION_DECORATION).isEmpty());
	}

	@Test
	void facetFailureCannotBeHiddenByAProviderThatRunsLater() {
		Provider first = provider("first", Set.of(WorldgenFacet.SURFACE));
		Provider second = provider("second", Set.of(WorldgenFacet.SURFACE));
		first.compiler = facet -> {
			throw new IllegalStateException("first concrete failure");
		};
		second.compiler = facet -> Optional.of(new WorldgenPlans.Surface(
			descriptor(second.id(), facet, CapabilityState.PROVIDER_CONTRACT), Optional.empty()
		));

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(second, first)).compile(emptyPlan());
		CapabilityFailure failure = result.report().firstCause(WorldgenFacet.SURFACE).orElseThrow();
		assertEquals(CapabilityState.UNAVAILABLE, result.surface().descriptor().state());
		assertEquals("provider_compile_failed", failure.code());
		assertEquals("first concrete failure", failure.message());
	}

	@Test
	void firstConflictCauseSurvivesAdditionalProviders() {
		Provider first = provider("first", Set.of(WorldgenFacet.SURFACE));
		Provider second = provider("second", Set.of(WorldgenFacet.SURFACE));
		Provider third = provider("third", Set.of(WorldgenFacet.SURFACE));
		for (Provider provider : List.of(first, second, third)) {
			provider.compiler = facet -> Optional.of(new WorldgenPlans.Surface(
				descriptor(provider.id(), facet, CapabilityState.PROVIDER_CONTRACT), Optional.empty()
			));
		}

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(third, second, first)).compile(emptyPlan());
		CapabilityFailure failure = result.report().firstCause(WorldgenFacet.SURFACE).orElseThrow();
		assertEquals("provider_conflict", failure.code());
		assertTrue(failure.message().contains("test:first"));
		assertTrue(failure.message().contains("test:second"));
		assertTrue(!failure.message().contains("test:third"));
	}

	@Test
	void biomePreviewDoesNotCompileGenerationOnlyProviderFacets() {
		List<WorldgenFacet> compiled = new ArrayList<>();
		Provider provider = provider("scoped", Set.of(
			WorldgenFacet.SELECTION_DECORATION,
			WorldgenFacet.PLACED_FEATURES,
			WorldgenFacet.STRUCTURES
		));
		provider.compiler = facet -> {
			compiled.add(facet);
			if (facet != WorldgenFacet.SELECTION_DECORATION) {
				throw new AssertionError("Preview compiled generation-only facet " + facet);
			}
			return Optional.of(new WorldgenPlans.SelectionDecoration(
				descriptor(provider.id(), facet, CapabilityState.PROVIDER_CONTRACT),
				List.of(stage(provider.id(), new ArrayList<>()))
			));
		};
		new WorldgenPlanCompiler(List.of(provider)).compile(
			emptyPlan(), WorldgenCompilationPurpose.BIOME_PREVIEW
		);

		assertEquals(List.of(WorldgenFacet.SELECTION_DECORATION), compiled);
	}

	@Test
	void rejectsNonPositiveProviderVersions() {
		Provider provider = provider("invalid_version", Set.of());
		provider.version = 0;
		assertThrows(PlanCompilationException.class, () -> new WorldgenPlanCompiler(List.of(provider)));
	}

	@Test
	void providerFacetReplacementRetainsIndependentBaseFailureDiagnostics() {
		Provider provider = provider("selection", Set.of(WorldgenFacet.SELECTION_DECORATION));
		provider.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
			descriptor(provider.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			List.of(stage(provider.id(), new ArrayList<>()))
		));
		WorldgenPlan base = emptyPlan();
		List<CapabilityNodeReport> reports = new ArrayList<>(base.report().nodes());
		reports.add(new CapabilityNodeReport(
			id("unreachable"), WorldgenFacet.SELECTION_DECORATION, CapabilityState.UNAVAILABLE,
			"synthetic", base.owner().type(), "test diagnostic",
			Optional.of(CapabilityFailure.unavailable("selection_provider_required", "test"))
		));
		base = new WorldgenPlan(
			base.owner(), base.biomeComposition(), base.providerSelection(),
			base.selectionDecoration(), base.spatialOwnership(), base.samplerDecoration(),
			base.densitySettings(), base.surface(), base.carvers(), base.placedFeatures(), base.structures(),
			base.execution(), new WorldgenCapabilityReport(reports, base.execution())
		);

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(provider)).compile(base);

		assertEquals(
			"selection_provider_required",
			result.report().firstCause(WorldgenFacet.SELECTION_DECORATION).orElseThrow().code()
		);
	}

	@Test
	void providerParallelQueryCapabilityIsExplicitAndConservative() {
		Provider serial = provider("serial", Set.of(WorldgenFacet.SELECTION_DECORATION));
		serial.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
			descriptor(serial.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			List.of(stage(serial.id(), new ArrayList<>()))
		));
		WorldgenPlan serialPlan = new WorldgenPlanCompiler(List.of(serial)).compile(emptyPlan());
		assertEquals(
			WorldgenQueryMode.OWNER_SERIAL,
			serialPlan.execution().queryMode(WorldgenFacet.SELECTION_DECORATION)
		);

		Provider parallel = provider("parallel", Set.of(WorldgenFacet.SELECTION_DECORATION));
		parallel.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
			descriptor(parallel.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			List.of(stage(parallel.id(), new ArrayList<>()))
		));
		parallel.queryMode = WorldgenQueryMode.ISOLATED_PARALLEL_READ;
		WorldgenPlan parallelPlan = new WorldgenPlanCompiler(List.of(parallel)).compile(emptyPlan());
		assertEquals(
			WorldgenQueryMode.ISOLATED_PARALLEL_READ,
			parallelPlan.execution().queryMode(WorldgenFacet.SELECTION_DECORATION)
		);
	}

	@Test
	void providerFailureCannotLeaveAParallelCapabilityBehind() {
		Provider provider = provider("failed_parallel", Set.of(WorldgenFacet.SELECTION_DECORATION));
		provider.queryMode = WorldgenQueryMode.ISOLATED_PARALLEL_READ;
		provider.compiler = facet -> {
			throw new IllegalStateException("synthetic failure");
		};

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(provider)).compile(emptyPlan());

		assertEquals(CapabilityState.UNAVAILABLE, result.selectionDecoration().descriptor().state());
		assertEquals(
			WorldgenQueryMode.OWNER_SERIAL,
			result.execution().queryMode(WorldgenFacet.SELECTION_DECORATION)
		);
		assertEquals(result.execution(), result.report().execution());
	}

	static WorldgenPlan emptyPlan() {
		return emptyPlan(new TestOwner(UUID.fromString("00000000-0000-0000-0000-000000000001")));
	}

	static WorldgenPlan emptyPlan(WorldgenOwner owner) {
		List<CapabilityNodeReport> reports = new ArrayList<>();
		for (WorldgenFacet facet : WorldgenFacet.values()) {
			reports.add(descriptor(id("base_" + facet.name().toLowerCase()), facet, CapabilityState.NORMALIZED).report(owner));
		}
		return new WorldgenPlan(
			owner,
			new WorldgenPlans.BiomeComposition(descriptor(id("base_biomes"), WorldgenFacet.BIOME_COMPOSITION, CapabilityState.NORMALIZED), List.of()),
			new WorldgenPlans.ProviderSelection(
				descriptor(id("base_providers"), WorldgenFacet.PROVIDER_SELECTION, CapabilityState.NORMALIZED),
				0L,
				List.of(new WorldgenPlans.ProviderDomain(
					id("base_domain"), 1.0D, new Climate.ParameterList<>(List.of(candidate())), 0
				)),
				Optional.of(id("base_domain")), Optional.empty(), Optional.empty()
			),
			new WorldgenPlans.SelectionDecoration(descriptor(id("base_decorators"), WorldgenFacet.SELECTION_DECORATION, CapabilityState.NORMALIZED), List.of()),
			new WorldgenPlans.SpatialOwnership(descriptor(id("base_spatial"), WorldgenFacet.SPATIAL_OWNERSHIP, CapabilityState.NORMALIZED), Optional.empty()),
			new WorldgenPlans.SamplerDecoration(descriptor(id("base_sampler"), WorldgenFacet.SAMPLER_DECORATION, CapabilityState.NORMALIZED), Optional.empty()),
			new WorldgenPlans.DensitySettings(descriptor(id("base_density"), WorldgenFacet.DENSITY_SETTINGS, CapabilityState.NORMALIZED), Optional.empty()),
			new WorldgenPlans.Surface(descriptor(id("base_surface"), WorldgenFacet.SURFACE, CapabilityState.NORMALIZED), Optional.empty()),
			new WorldgenPlans.Carvers(descriptor(id("base_carvers"), WorldgenFacet.CARVERS, CapabilityState.NORMALIZED), List.of()),
			new WorldgenPlans.PlacedFeatures(
				descriptor(id("base_features"), WorldgenFacet.PLACED_FEATURES, CapabilityState.NORMALIZED),
				List.of(), List.of(), Map.of(),
				raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.empty()
			),
			new WorldgenPlans.Structures(descriptor(id("base_structures"), WorldgenFacet.STRUCTURES, CapabilityState.NORMALIZED), List.of(), List.of(), List.of(), List.of()),
			WorldgenExecution.serial(),
			new WorldgenCapabilityReport(reports, WorldgenExecution.serial())
		);
	}

	private static PlanDescriptor descriptor(ResourceLocation id, WorldgenFacet facet, CapabilityState state) {
		return new PlanDescriptor(id, facet, state, "synthetic", "test", Optional.empty());
	}

	private static Provider provider(String name, Set<WorldgenFacet> facets) {
		return new Provider(id(name), facets);
	}

	private static WorldgenPlans.SelectionDecoratorStage stage(
		ResourceLocation id,
		List<String> events
	) {
		return new WorldgenPlans.SelectionDecoratorStage(id, (result, spatial, target, x, y, z, sampler) -> {
			events.add(id.toString());
			return result.biome();
		});
	}

	private static WorldgenPlans.BiomeComposition composition(
		ResourceLocation id,
		List<com.mojang.datafixers.util.Pair<Climate.ParameterPoint, Holder<Biome>>> entries,
		int order,
		List<String> events
	) {
		return new WorldgenPlans.BiomeComposition(
			descriptor(id, WorldgenFacet.BIOME_COMPOSITION, CapabilityState.PROVIDER_CONTRACT),
			entries,
			List.of(new WorldgenPlans.CandidateCompositionStage(id, order, candidates -> {
				events.add(id.toString());
				return candidates;
			}))
		);
	}

	private static com.mojang.datafixers.util.Pair<Climate.ParameterPoint, Holder<Biome>> candidate() {
		return com.mojang.datafixers.util.Pair.of(
			Climate.parameters(0, 0, 0, 0, 0, 0, 0), Holder.direct((Biome) null)
		);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Holder<Biome> biome(String value) {
		return (Holder) Holder.direct(value);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}

	private record TestOwner(UUID id) implements WorldgenOwner {
		@Override
		public ResourceKey<LevelStem> dimension() {
			return LevelStem.OVERWORLD;
		}
		@Override public WorldgenOwnerType type() { return WorldgenOwnerType.WORLDGEN_EPOCH; }
		@Override public long seed() { return 1L; }
		@Override public RegistryAccess.Frozen registries() { return RegistryAccess.EMPTY; }
		@Override public LevelStem selectedStem() { return null; }
		@Override public String settingsIdentity() { return "settings"; }
		@Override public String resourceLayerFingerprint() { return "resources"; }
		@Override public TagEpoch tagEpoch() { return new TagEpoch(0L, "tags"); }
		@Override public long contributionSequence() { return 0L; }
		@Override public net.minecraft.core.HolderLookup.Provider lookups() { return RegistryAccess.EMPTY; }
	}

	private static final class Provider implements WorldgenCapabilityProvider {
		private final ResourceLocation id;
		private final Set<WorldgenFacet> facets;
		private List<ProviderOrder> ordering = List.of();
		private Compiler compiler = facet -> Optional.empty();
		private int version = 1;
		private WorldgenQueryMode queryMode = WorldgenQueryMode.OWNER_SERIAL;
		private WorldgenApplicability applicability = WorldgenApplicability.APPLICABLE;

		private Provider(ResourceLocation id, Set<WorldgenFacet> facets) {
			this.id = id;
			this.facets = Set.copyOf(facets);
		}

		@Override public ResourceLocation id() { return this.id; }
		@Override public int version() { return this.version; }
		@Override public Set<WorldgenFacet> facets() { return this.facets; }
		@Override public Set<WorldgenOwnerType> ownerTypes() { return Set.of(WorldgenOwnerType.values()); }
		@Override public List<ProviderOrder> ordering() { return this.ordering; }
		@Override public WorldgenApplicability applicability(WorldgenFacet facet, WorldgenCompilationContext context) {
			return this.applicability;
		}
		@Override public Optional<? extends WorldgenPlans.DomainPlan> compile(WorldgenFacet facet, WorldgenCompilationContext context) {
			return this.compiler.compile(facet);
		}
		@Override public WorldgenQueryMode queryMode(WorldgenFacet facet, WorldgenCompilationContext context) {
			return this.queryMode;
		}
		@Override public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) { return Optional.empty(); }
	}

	@FunctionalInterface
	private interface Compiler {
		Optional<? extends WorldgenPlans.DomainPlan> compile(WorldgenFacet facet);
	}
}
