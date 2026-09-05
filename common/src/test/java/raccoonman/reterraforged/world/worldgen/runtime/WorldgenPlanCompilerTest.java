package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import raccoonman.reterraforged.world.worldgen.biome.ClimateQueryPolicy;

class WorldgenPlanCompilerTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}
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
	void directCustomSourceInputCompilesAsTheUniquePreviewRoot() {
		TestBiomeRegistries testRegistries = testRegistries();
		Holder<Biome> output = testRegistries.output();
		MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(
			List.of(com.mojang.datafixers.util.Pair.of(
				Climate.parameters(0, 0, 0, 0, 0, 0, 0), output
			))
		));
		BiomeSourcePlanInput input = new BiomeSourcePlanInput(
			id("direct_source"), Set.of(output), WorldgenQueryMode.OWNER_SERIAL,
			(x, y, z, sampler) -> output
		);
		TerraForgedChunkGenerator generator = new TerraForgedChunkGenerator(
			source, Holder.direct(NoiseGeneratorSettings.dummy()), Optional.of(input)
		);
		LevelStem stem = new LevelStem(Holder.direct((DimensionType) null), generator);
		PreviewRequest owner = new PreviewRequest(
			UUID.randomUUID(), LevelStem.OVERWORLD, 1L, testRegistries.access(),
			testRegistries.access(), stem, "settings", 0L, "resources", new TagEpoch(0L, "tags"),
			WorldgenContributionRevision.Snapshot.empty(LevelStem.OVERWORLD.location())
		);

		WorldgenPlan plan = MinecraftWorldgenPlanCompiler.compile(
			owner, List.of(), WorldgenCompilationPurpose.BIOME_PREVIEW
		);

		assertEquals(input, plan.providerSelection().directInput().orElseThrow());
		assertTrue(plan.providerSelection().providers().isEmpty());
		assertEquals(
			WorldgenQueryMode.OWNER_SERIAL,
			plan.execution().queryMode(WorldgenFacet.PROVIDER_SELECTION)
		);
		assertEquals(Set.of(output), WorldgenBiomeSelection.possibleBiomes(plan));
		WorldgenBiomeSelection.requireExecutablePlan(plan);
	}

	@Test
	void directRegisteredCustomSourceOutputIsCanonicalizedBeforeExecution() {
		TestBiomeRegistries testRegistries = testRegistries();
		Holder.Reference<Biome> registered = testRegistries.output();
		Holder<Biome> direct = Holder.direct(registered.value());
		BiomeSourcePlanInput input = new BiomeSourcePlanInput(
			id("direct_registered_source"), Set.of(direct),
			WorldgenQueryMode.ISOLATED_PARALLEL_READ,
			(x, y, z, sampler) -> direct
		);
		MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(
			List.of(com.mojang.datafixers.util.Pair.of(
				Climate.parameters(0, 0, 0, 0, 0, 0, 0), direct
			))
		));
		TerraForgedChunkGenerator generator = new TerraForgedChunkGenerator(
			source, Holder.direct(NoiseGeneratorSettings.dummy()), Optional.of(input)
		);

		WorldgenPlan plan = MinecraftWorldgenPlanCompiler.compile(
			previewOwner(generator, testRegistries.access()), List.of(), WorldgenCompilationPurpose.BIOME_PREVIEW
		);
		BiomeSourcePlanInput normalized = plan.providerSelection().directInput().orElseThrow();

		assertEquals(Set.of(registered), normalized.possibleOutputs());
		assertSame(registered, normalized.resolve(0, 0, 0, null));
	}

	@Test
	void generationSettingsIncludeRegisteredBiomesOutsideSelection() {
		TestBiomeRegistries testRegistries = testRegistries();
		MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(
			List.of(com.mojang.datafixers.util.Pair.of(
				Climate.parameters(0, 0, 0, 0, 0, 0, 0), testRegistries.output()
			))
		));
		TerraForgedChunkGenerator generator = new TerraForgedChunkGenerator(
			source, Holder.direct(NoiseGeneratorSettings.dummy()), Optional.empty()
		);

		Map<ResourceKey<Biome>, net.minecraft.world.level.biome.BiomeGenerationSettings> settings =
			MinecraftWorldgenPlanCompiler.compileRegisteredBiomeGenerationSettings(
				previewOwner(generator, testRegistries.access()), generator, List.of()
			);

		assertEquals(Set.of(
			testRegistries.output().key(), testRegistries.unselected().key()
		), settings.keySet());
	}

	@Test
	void rejectsAContributionRevisionThatChangesDuringCompilation() {
		TestBiomeRegistries testRegistries = testRegistries();
		Holder<Biome> output = testRegistries.output();
		MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(
			List.of(com.mojang.datafixers.util.Pair.of(
				Climate.parameters(0, 0, 0, 0, 0, 0, 0), output
			))
		));
		BiomeSourcePlanInput input = new BiomeSourcePlanInput(
			id("revision_source"), Set.of(output), WorldgenQueryMode.ISOLATED_PARALLEL_READ,
			(x, y, z, sampler) -> output
		);
		TerraForgedChunkGenerator generator = new TerraForgedChunkGenerator(
			source, Holder.direct(NoiseGeneratorSettings.dummy()), Optional.of(input)
		);
		ChangingRevisionProvider provider = new ChangingRevisionProvider();
		WorldgenProviderCatalog catalog = WorldgenProviderCatalog.of(List.of(provider));
		WorldgenContributionRevision.Snapshot revision = WorldgenContributionRevision.snapshot(
			LevelStem.OVERWORLD, catalog
		);
		PreviewRequest owner = new PreviewRequest(
			UUID.randomUUID(), LevelStem.OVERWORLD, 1L, testRegistries.access(),
			testRegistries.access(),
			new LevelStem(Holder.direct((DimensionType) null), generator),
			"settings", 0L, "resources", new TagEpoch(0L, "tags"), revision
		);

		IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
			MinecraftWorldgenPlanCompiler.compile(
				owner, catalog, WorldgenCompilationPurpose.BIOME_PREVIEW
			)
		);
		assertTrue(failure.getMessage().contains("changed after plan compilation"));
	}

	@Test
	void serializedCustomSourceFactoryAcquiresAnOwnerScopedRoot() {
		TestBiomeRegistries testRegistries = testRegistries();
		FactoryBiomeSource source = new FactoryBiomeSource(false, testRegistries.output());
		TerraForgedChunkGenerator generator = new TerraForgedChunkGenerator(
			source, Holder.direct(NoiseGeneratorSettings.dummy())
		);
		PreviewRequest owner = previewOwner(generator, testRegistries.access());

		WorldgenPlan plan = MinecraftWorldgenPlanCompiler.compile(
			owner, List.of(), WorldgenCompilationPurpose.BIOME_PREVIEW
		);

		assertEquals(FactoryBiomeSource.ID, plan.providerSelection().directInput().orElseThrow().id());
		assertEquals(owner.id(), source.observedOwner);
		WorldgenBiomeSelection.requireExecutablePlan(plan);
	}

	@Test
	void customSourceFactoryIdMismatchIsABoundedPlanFailure() {
		TestBiomeRegistries testRegistries = testRegistries();
		FactoryBiomeSource source = new FactoryBiomeSource(true, testRegistries.output());
		WorldgenPlan plan = MinecraftWorldgenPlanCompiler.compile(
			previewOwner(new TerraForgedChunkGenerator(
				source, Holder.direct(NoiseGeneratorSettings.dummy())
			), testRegistries.access()),
			List.of(), WorldgenCompilationPurpose.BIOME_PREVIEW
		);

		assertEquals(CapabilityState.UNAVAILABLE, plan.providerSelection().descriptor().state());
		assertEquals(
			"custom_source_plan_acquisition_failed",
			plan.providerSelection().descriptor().firstCause().orElseThrow().code()
		);
	}

	@Test
	void unchangedCandidateRootReusesItsRequestOwnedSearchIndex() {
		Holder<Biome> output = Holder.direct((Biome) null);
		BiomeCandidateRoot root = BiomeCandidateRoot.fromEntries(List.of(
			com.mojang.datafixers.util.Pair.of(
				Climate.parameters(0, 0, 0, 0, 0, 0, 0), output
			)
		));
		MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(root.candidates());
		TerraForgedChunkGenerator generator = new TerraForgedChunkGenerator(
			source, Holder.direct(NoiseGeneratorSettings.dummy()), Optional.empty(), Optional.of(root)
		);

		WorldgenPlan plan = MinecraftWorldgenPlanCompiler.compile(
			previewOwner(generator), List.of(), WorldgenCompilationPurpose.BIOME_PREVIEW
		);

		assertSame(root, plan.biomeComposition().candidateRoot().orElseThrow());
		assertSame(root.candidates(), plan.providerSelection().providers().getFirst().candidates());
	}

	private static PreviewRequest previewOwner(TerraForgedChunkGenerator generator) {
		return previewOwner(generator, testRegistries().access());
	}

	private static PreviewRequest previewOwner(
		TerraForgedChunkGenerator generator,
		RegistryAccess.Frozen registries
	) {
		LevelStem stem = new LevelStem(Holder.direct((DimensionType) null), generator);
		return new PreviewRequest(
			UUID.randomUUID(), LevelStem.OVERWORLD, 1L, registries,
			registries, stem, "settings", 0L, "resources", new TagEpoch(0L, "tags"),
			WorldgenContributionRevision.Snapshot.empty(LevelStem.OVERWORLD.location())
		);
	}

	private static TestBiomeRegistries testRegistries() {
		MappedRegistry<Biome> biomes = new MappedRegistry<>(
			Registries.BIOME, com.mojang.serialization.Lifecycle.stable()
		);
		Holder.Reference<Biome> output = biomes.register(
			ResourceKey.create(Registries.BIOME, id("registered_output")),
			testBiome(),
			RegistrationInfo.BUILT_IN
		);
		Holder.Reference<Biome> unselected = biomes.register(
			ResourceKey.create(Registries.BIOME, id("registered_unselected")),
			testBiome(),
			RegistrationInfo.BUILT_IN
		);
		biomes.freeze();
		RegistryAccess.Frozen access = new RegistryAccess.ImmutableRegistryAccess(
			List.of(biomes)
		).freeze();
		return new TestBiomeRegistries(access, output, unselected);
	}

	private static Biome testBiome() {
		return new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(0.8F)
			.downfall(0.4F)
			.specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder()
				.fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
			.mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
			.generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
			.build();
	}

	private record TestBiomeRegistries(
		RegistryAccess.Frozen access,
		Holder.Reference<Biome> output,
		Holder.Reference<Biome> unselected
	) {
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
	void applicableProviderCannotSilentlyOmitItsFacetPlan() {
		Provider provider = provider("missing_applicable_plan", Set.of(WorldgenFacet.SURFACE));
		provider.compiler = facet -> Optional.empty();

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(provider)).compile(emptyPlan());

		assertEquals(CapabilityState.UNAVAILABLE, result.surface().descriptor().state());
		CapabilityFailure failure = result.report().firstCause(WorldgenFacet.SURFACE).orElseThrow();
		assertEquals("provider_compile_failed", failure.code());
		assertTrue(failure.message().contains("supplied no plan"));
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
			Climate.target(0, 0, 0, 0, 0, 0), 0, 0, 0, null, null
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
	void selectionDecoratorSemanticOrderDoesNotDependOnProviderEnumeration() {
		Provider lexicallyFirst = provider("alpha", Set.of(WorldgenFacet.SELECTION_DECORATION));
		Provider lexicallyLast = provider("zulu", Set.of(WorldgenFacet.SELECTION_DECORATION));
		lexicallyFirst.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
			descriptor(lexicallyFirst.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			List.of(stage(lexicallyFirst.id(), 200, new ArrayList<>()))
		));
		lexicallyLast.compiler = facet -> Optional.of(new WorldgenPlans.SelectionDecoration(
			descriptor(lexicallyLast.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			List.of(stage(lexicallyLast.id(), 100, new ArrayList<>()))
		));

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(lexicallyFirst, lexicallyLast))
			.compile(emptyPlan());

		assertEquals(
			List.of(lexicallyLast.id(), lexicallyFirst.id()),
			result.selectionDecoration().orderedDecorators()
		);
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
		lithostitched.compiler = facet -> Optional.of(new WorldgenPlans.BiomeComposition(
			descriptor(lithostitched.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			List.of(),
			List.of(new WorldgenPlans.CandidateCompositionStage(
				lithostitched.id(), 100, candidates -> {
					events.add(lithostitched.id().toString());
					return java.util.stream.Stream.concat(
						candidates.stream(), java.util.stream.Stream.of(candidate())
					).toList();
				}
			))
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
		BiomeCandidateRoot candidateRoot = BiomeCandidateRoot.fromEntries(
			List.of(com.mojang.datafixers.util.Pair.of(point, rootBiome))
		);
		WorldgenPlans.BiomeComposition composition = new WorldgenPlans.BiomeComposition(
			descriptor(id("composition"), WorldgenFacet.BIOME_COMPOSITION, CapabilityState.NORMALIZED),
			candidateRoot.entries(),
			List.of(new WorldgenPlans.CandidateCompositionStage(
				id("addition"), 100, candidates -> java.util.stream.Stream.concat(
					candidates.stream(), java.util.stream.Stream.of(com.mojang.datafixers.util.Pair.of(point, addition))
				).toList()
			)),
			Optional.of(candidateRoot)
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
		assertNotSame(
			candidateRoot.candidates(),
			result.providerSelection().providers().getFirst().candidates()
		);
		assertSame(
			result.biomeComposition().candidateRoot().orElseThrow().candidates(),
			result.providerSelection().providers().getFirst().candidates()
		);
		assertEquals(
			WorldgenQueryMode.OWNER_SERIAL,
			result.execution().queryMode(WorldgenFacet.PROVIDER_SELECTION)
		);
		assertEquals(
			WorldgenQueryMode.OWNER_SERIAL,
			result.execution().queryMode(WorldgenFacet.SPATIAL_OWNERSHIP)
		);
	}

	@Test
	void noOpCandidateStageRetainsTheAcquiredSearchIndex() {
		WorldgenPlan base = emptyPlan();
		Holder<Biome> biome = Holder.direct((Biome) null);
		var point = Climate.parameters(0, 0, 0, 0, 0, 0, 0);
		BiomeCandidateRoot candidateRoot = BiomeCandidateRoot.fromEntries(
			List.of(com.mojang.datafixers.util.Pair.of(point, biome))
		);
		WorldgenPlans.BiomeComposition composition = new WorldgenPlans.BiomeComposition(
			descriptor(id("composition"), WorldgenFacet.BIOME_COMPOSITION, CapabilityState.NORMALIZED),
			candidateRoot.entries(),
			List.of(new WorldgenPlans.CandidateCompositionStage(
				id("no_op"), 100, List::copyOf
			)),
			Optional.of(candidateRoot)
		);
		WorldgenPlan input = new WorldgenPlan(
			base.owner(), composition, base.providerSelection(), base.selectionDecoration(),
			base.spatialOwnership(), base.samplerDecoration(), base.densitySettings(), base.surface(),
			base.carvers(), base.placedFeatures(), base.structures(), base.execution(), base.report()
		);

		WorldgenPlan result = MinecraftWorldgenPlanCompiler.materializeCandidateOwnership(input);

		assertSame(candidateRoot, result.biomeComposition().candidateRoot().orElseThrow());
		assertSame(
			candidateRoot.candidates(),
			result.providerSelection().providers().getFirst().candidates()
		);
		assertEquals(
			WorldgenQueryMode.ISOLATED_PARALLEL_READ,
			result.execution().queryMode(WorldgenFacet.PROVIDER_SELECTION)
		);
		assertEquals(
			WorldgenQueryMode.ISOLATED_PARALLEL_READ,
			result.execution().queryMode(WorldgenFacet.SPATIAL_OWNERSHIP)
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
				descriptor(provider.id(), facet, CapabilityState.PROVIDER_CONTRACT),
				Optional.of(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.dummy().surfaceRule())
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
	void surfaceTransformsMaterializeOnceInTypedOrderAndIntersectQueryModes() {
		List<String> events = new ArrayList<>();
		Provider later = provider("later_surface", Set.of(WorldgenFacet.SURFACE));
		Provider earlier = provider("earlier_surface", Set.of(WorldgenFacet.SURFACE));
		for (Provider provider : List.of(later, earlier)) {
			provider.contributionKind = WorldgenContributionKind.ORDERED_TRANSFORM;
		}
		later.queryMode = WorldgenQueryMode.ISOLATED_PARALLEL_READ;
		later.compiler = facet -> Optional.of(surfaceTransform(later.id(), 200, events));
		earlier.compiler = facet -> Optional.of(surfaceTransform(earlier.id(), 100, events));

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(later, earlier)).compile(emptyPlan());

		assertEquals(List.of(earlier.id().toString(), later.id().toString()), events);
		assertEquals(List.of(earlier.id(), later.id()), result.surface().appliedTransforms());
		assertTrue(result.surface().transforms().isEmpty());
		assertTrue(result.surface().root().isPresent());
		assertEquals(WorldgenQueryMode.OWNER_SERIAL, result.execution().queryMode(WorldgenFacet.SURFACE));
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
	void capabilityProviderCannotOverrideTheOwnerScopedSamplerQueryPolicy() {
		WorldgenPlan worldgenBase = emptyPlan();
		WorldgenPlans.SamplerDecoration previewSampler = new WorldgenPlans.SamplerDecoration(
			worldgenBase.samplerDecoration().descriptor(),
			ClimateQueryPolicy.SURFACE_PREVIEW,
			worldgenBase.samplerDecoration().decorator()
		);
		WorldgenPlan previewBase = new WorldgenPlan(
			worldgenBase.owner(), worldgenBase.biomeComposition(), worldgenBase.providerSelection(),
			worldgenBase.selectionDecoration(), worldgenBase.spatialOwnership(), previewSampler,
			worldgenBase.densitySettings(), worldgenBase.surface(), worldgenBase.carvers(),
			worldgenBase.placedFeatures(), worldgenBase.structures(), worldgenBase.execution(),
			worldgenBase.report()
		);
		Provider provider = provider("sampler", Set.of(WorldgenFacet.SAMPLER_DECORATION));
		provider.compiler = facet -> Optional.of(new WorldgenPlans.SamplerDecoration(
			descriptor(provider.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			ClimateQueryPolicy.WORLDGEN,
			Optional.of((WorldgenPlans.SamplerDecorator) (
				target, quartX, quartY, quartZ
			) -> target)
		));

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(provider)).compile(
			previewBase, WorldgenCompilationPurpose.BIOME_PREVIEW
		);

		assertEquals(ClimateQueryPolicy.SURFACE_PREVIEW, result.samplerDecoration().queryPolicy());
	}

	@Test
	void independentSamplerContributorsComposeByTypedStageOrderAndRestrictConcurrency() {
		List<String> events = new ArrayList<>();
		Provider later = provider("sampler_later", Set.of(WorldgenFacet.SAMPLER_DECORATION));
		later.queryMode = WorldgenQueryMode.ISOLATED_PARALLEL_READ;
		later.compiler = facet -> Optional.of(new WorldgenPlans.SamplerDecoration(
			descriptor(later.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			ClimateQueryPolicy.WORLDGEN,
			List.of(new WorldgenPlans.SamplerDecoratorStage(
				later.id(), 20, (target, quartX, quartY, quartZ) -> {
					events.add("later");
					return target;
				}
			))
		));
		Provider earlier = provider("sampler_earlier", Set.of(WorldgenFacet.SAMPLER_DECORATION));
		earlier.queryMode = WorldgenQueryMode.OWNER_SERIAL;
		earlier.compiler = facet -> Optional.of(new WorldgenPlans.SamplerDecoration(
			descriptor(earlier.id(), facet, CapabilityState.PROVIDER_CONTRACT),
			ClimateQueryPolicy.WORLDGEN,
			List.of(new WorldgenPlans.SamplerDecoratorStage(
				earlier.id(), 10, (target, quartX, quartY, quartZ) -> {
					events.add("earlier");
					return target;
				}
			))
		));

		WorldgenPlan result = new WorldgenPlanCompiler(List.of(later, earlier)).compile(emptyPlan());

		assertEquals(List.of(earlier.id(), later.id()), result.samplerDecoration().stages().stream()
			.map(WorldgenPlans.SamplerDecoratorStage::id).toList());
		assertEquals(WorldgenQueryMode.OWNER_SERIAL,
			result.execution().queryMode(WorldgenFacet.SAMPLER_DECORATION));
		result.samplerDecoration().decorator().orElseThrow().apply(
			Climate.target(0, 0, 0, 0, 0, 0), 1, 2, 3
		);
		assertEquals(List.of("earlier", "later"), events);
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
			new WorldgenPlans.Surface(
				descriptor(id("base_surface"), WorldgenFacet.SURFACE, CapabilityState.NORMALIZED),
				Optional.of(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.dummy().surfaceRule())
			),
			new WorldgenPlans.Carvers(descriptor(id("base_carvers"), WorldgenFacet.CARVERS, CapabilityState.NORMALIZED), List.of()),
			new WorldgenPlans.PlacedFeatures(
				descriptor(id("base_features"), WorldgenFacet.PLACED_FEATURES, CapabilityState.NORMALIZED),
				List.of(), List.of(), Map.of(),
				raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.empty()
			),
			new WorldgenPlans.Structures(
				descriptor(id("base_structures"), WorldgenFacet.STRUCTURES, CapabilityState.NORMALIZED),
				List.of(), List.of(), List.of(), List.of(), List.of()
			),
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
		return stage(id, 0, events);
	}

	private static WorldgenPlans.SelectionDecoratorStage stage(
		ResourceLocation id,
		int order,
		List<String> events
	) {
		return new WorldgenPlans.SelectionDecoratorStage(id, order, (result, spatial, target, x, y, z, sampler, surfaceContext) -> {
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

	private static WorldgenPlans.Surface surfaceTransform(
		ResourceLocation id,
		int order,
		List<String> events
	) {
		return new WorldgenPlans.Surface(
			descriptor(id, WorldgenFacet.SURFACE, CapabilityState.PROVIDER_CONTRACT),
			Optional.empty(),
			List.of(new WorldgenPlans.SurfaceRuleTransformStage(id, order, root -> {
				events.add(id.toString());
				return root;
			})),
			List.of()
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
		@Override public long resourceRevision() { return 0L; }
		@Override public String resourceLayerFingerprint() { return "resources"; }
		@Override public TagEpoch tagEpoch() { return new TagEpoch(0L, "tags"); }
		@Override public WorldgenContributionRevision.Snapshot contributionRevision() {
			return WorldgenContributionRevision.Snapshot.empty(LevelStem.OVERWORLD.location());
		}
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
		private WorldgenContributionKind contributionKind;

		private Provider(ResourceLocation id, Set<WorldgenFacet> facets) {
			this.id = id;
			this.facets = Set.copyOf(facets);
		}

		@Override public ResourceLocation id() { return this.id; }
		@Override public int version() { return this.version; }
		@Override public Set<WorldgenFacet> facets() { return this.facets; }
		@Override public Set<WorldgenOwnerType> ownerTypes() { return Set.of(WorldgenOwnerType.values()); }
		@Override public List<ProviderOrder> ordering() { return this.ordering; }
		@Override public WorldgenContributionKind contributionKind(WorldgenFacet facet) {
			return this.contributionKind == null
				? WorldgenCapabilityProvider.super.contributionKind(facet)
				: this.contributionKind;
		}
		@Override public WorldgenApplicability applicability(WorldgenFacet facet, WorldgenCompilationContext context) {
			return this.applicability;
		}
		@Override public Optional<? extends WorldgenPlans.DomainPlan> compile(WorldgenFacet facet, WorldgenCompilationContext context) {
			return this.compiler.compile(facet);
		}
		@Override public WorldgenQueryMode queryMode(WorldgenFacet facet, WorldgenCompilationContext context) {
			return this.queryMode;
		}
		@Override public WorldgenQueryMode declaredQueryMode(WorldgenFacet facet) { return this.queryMode; }
		@Override public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) { return Optional.empty(); }
	}

	private static final class ChangingRevisionProvider implements WorldgenCapabilityProvider {
		private final AtomicLong revision = new AtomicLong();

		@Override public ResourceLocation id() { return WorldgenPlanCompilerTest.id("changing_revision"); }
		@Override public int version() { return 1; }
		@Override public Set<WorldgenFacet> facets() { return Set.of(WorldgenFacet.SAMPLER_DECORATION); }
		@Override public Set<WorldgenOwnerType> ownerTypes() {
			return Set.of(WorldgenOwnerType.PREVIEW_REQUEST);
		}
		@Override public List<ProviderOrder> ordering() { return List.of(); }
		@Override public boolean providesContributionRevision() { return true; }
		@Override public OptionalLong contributionRevision(ResourceKey<LevelStem> dimension) {
			return OptionalLong.of(this.revision.get());
		}
		@Override public WorldgenApplicability applicability(
			WorldgenFacet facet, WorldgenCompilationContext context
		) {
			this.revision.incrementAndGet();
			return WorldgenApplicability.NOT_APPLICABLE;
		}
		@Override public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
			return Optional.empty();
		}
		@Override public Optional<? extends WorldgenPlans.DomainPlan> compile(
			WorldgenFacet facet, WorldgenCompilationContext context
		) {
			return Optional.empty();
		}
		@Override public WorldgenQueryMode queryMode(
			WorldgenFacet facet, WorldgenCompilationContext context
		) {
			return WorldgenQueryMode.OWNER_SERIAL;
		}
	}

	private static final class FactoryBiomeSource extends net.minecraft.world.level.biome.BiomeSource
		implements BiomeSourcePlanInputFactory {
		private static final ResourceLocation ID = id("source_factory");
		private static final MapCodec<FactoryBiomeSource> CODEC = MapCodec.unit(
			() -> new FactoryBiomeSource(false, Holder.direct((Biome) null))
		);
		private final boolean mismatchedId;
		private final Holder<Biome> output;
		private UUID observedOwner;

		private FactoryBiomeSource(boolean mismatchedId, Holder<Biome> output) {
			this.mismatchedId = mismatchedId;
			this.output = output;
		}

		@Override public ResourceLocation biomeSourcePlanFactoryId() { return ID; }
		@Override public BiomeSourcePlanInput createBiomeSourcePlanInput(WorldgenOwner owner) {
			this.observedOwner = owner.id();
			return new BiomeSourcePlanInput(
				this.mismatchedId ? id("wrong_factory") : ID,
				Set.of(this.output), WorldgenQueryMode.ISOLATED_PARALLEL_READ,
				(x, y, z, sampler) -> this.output
			);
		}
		@Override protected MapCodec<? extends net.minecraft.world.level.biome.BiomeSource> codec() {
			return CODEC;
		}
		@Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return Stream.of(this.output); }
		@Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
			return this.output;
		}
	}

	@FunctionalInterface
	private interface Compiler {
		Optional<? extends WorldgenPlans.DomainPlan> compile(WorldgenFacet facet);
	}
}
