package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import raccoonman.reterraforged.world.worldgen.biome.ClimateQueryPolicy;

class WorldgenPlansTest {
	private static final Holder<Biome> VALUE = Holder.direct((Biome) null);
	private static final Climate.ParameterList<Holder<Biome>> TABLE = table(VALUE);
	private static final Holder<Biome> BASE = biome("base");
	private static final Holder<Biome> MIDDLE = biome("middle");
	private static final Holder<Biome> FINAL = biome("final");

	@Test
	void providerDomainsAreStoredInRegistrationOrder() {
		WorldgenPlans.ProviderDomain later = domain("later", 7);
		WorldgenPlans.ProviderDomain earlier = domain("earlier", 2);
		WorldgenPlans.ProviderSelection plan = new WorldgenPlans.ProviderSelection(
			descriptor(), 1L, List.of(later, earlier), Optional.of(earlier.id()), Optional.of(TABLE), Optional.empty()
		);

		assertEquals(List.of(earlier, later), plan.providers());
	}

	@Test
	void duplicateRegistrationOrderFailsClosed() {
		assertThrows(IllegalArgumentException.class, () -> new WorldgenPlans.ProviderSelection(
			descriptor(), 1L, List.of(domain("first", 0), domain("second", 0)),
			Optional.empty(), Optional.of(TABLE), Optional.empty()
		));
	}

	@Test
	void deferredPlaceholderCannotEscapeThroughDefaultTable() {
		assertThrows(IllegalArgumentException.class, () -> new WorldgenPlans.ProviderSelection(
			descriptor(), 1L, List.of(domain("first", 0)), Optional.empty(), Optional.of(TABLE), Optional.of(VALUE)
		));
	}

	@Test
	void emptyPlanCannotInventFallbackState() {
		assertThrows(IllegalArgumentException.class, () -> new WorldgenPlans.ProviderSelection(
			descriptor(), 1L, List.of(), Optional.empty(), Optional.of(TABLE), Optional.empty()
		));
	}

	@Test
	void directCustomRootIsImmutableExclusiveAndChecksItsOutputClosure() {
		Holder<Biome> declared = biome("declared");
		Holder<Biome> undeclared = biome("undeclared");
		BiomeSourcePlanInput input = new BiomeSourcePlanInput(
			id("direct"), Set.of(declared), WorldgenQueryMode.ISOLATED_PARALLEL_READ,
			(x, y, z, sampler) -> x == 0 ? declared : undeclared
		);
		WorldgenPlans.ProviderSelection direct = new WorldgenPlans.ProviderSelection(
			descriptor(), 0L, List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
			Optional.of(input)
		);

		assertEquals(declared, input.resolve(0, 0, 0, null));
		assertThrows(IllegalStateException.class, () -> input.resolve(1, 0, 0, null));
		assertEquals(input, direct.directInput().orElseThrow());
		assertThrows(IllegalArgumentException.class, () -> new WorldgenPlans.ProviderSelection(
			descriptor(), 0L, List.of(domain("candidate", 0)), Optional.empty(), Optional.empty(),
			Optional.empty(), Optional.of(input)
		));
	}

	@Test
	void authoritativeSpatialDomainSelectsItsTableWithoutASecondAssignment() {
		WorldgenPlans.ProviderDomain first = domain("first", 0);
		WorldgenPlans.ProviderDomain second = domain("second", 1);
		WorldgenPlans.ProviderSelection plan = new WorldgenPlans.ProviderSelection(
			descriptor(), 1L, List.of(first, second), Optional.of(first.id()), Optional.of(TABLE), Optional.empty()
		);

		assertEquals(
			second.id(),
			plan.resolve(second.id(), Climate.target(0, 0, 0, 0, 0, 0)).orElseThrow().domain()
		);
	}

	@Test
	void providerIndexAndPreparedSelectorPreserveAuthoritativeDomains() {
		WorldgenPlans.ProviderDomain first = domain("first", 0);
		WorldgenPlans.ProviderDomain second = new WorldgenPlans.ProviderDomain(
			id("second"), 3.0D, TABLE, 1
		);
		WorldgenPlans.ProviderSelection plan = new WorldgenPlans.ProviderSelection(
			descriptor(), 991L, List.of(first, second), Optional.of(first.id()), Optional.of(TABLE), Optional.empty()
		);

		for (int x = -20; x <= 20; x++) {
			for (int z = -20; z <= 20; z++) {
				assertEquals(
					WeightedRendezvous.select(plan.salt(), x, z, plan.providers()).id(),
					plan.selectDomain(x, z)
				);
			}
		}
		assertThrows(
			IllegalArgumentException.class,
			() -> plan.resolveRequired(id("missing"), Climate.target(0, 0, 0, 0, 0, 0))
		);
	}

	@Test
	void samplerQueryPolicyIsExplicitImmutablePlanData() {
		PlanDescriptor descriptor = new PlanDescriptor(
			id("sampler"), WorldgenFacet.SAMPLER_DECORATION, CapabilityState.NORMALIZED,
			"test", "test", Optional.empty()
		);
		WorldgenPlans.SamplerDecoration preview = new WorldgenPlans.SamplerDecoration(
			descriptor, ClimateQueryPolicy.SURFACE_PREVIEW, Optional.empty()
		);

		assertEquals(ClimateQueryPolicy.SURFACE_PREVIEW, preview.queryPolicy());
		assertEquals(
			ClimateQueryPolicy.WORLDGEN,
			new WorldgenPlans.SamplerDecoration(descriptor, Optional.empty()).queryPolicy()
		);
	}

	@Test
	void providerResultCarriesImmutableBestAndDistinctNextBestCandidates() {
		Holder<Biome> best = biome("best");
		Holder<Biome> duplicateBest = best;
		Holder<Biome> next = biome("next");
		Climate.ParameterPoint bestPoint = Climate.parameters(0, 0, 0, 0, 0, 0, 0);
		Climate.ParameterPoint duplicatePoint = Climate.parameters(0.4F, 0, 0, 0, 0, 0, 0);
		Climate.ParameterPoint nextPoint = Climate.parameters(0.2F, 0, 0, 0, 0, 0, 0);
		WorldgenPlans.ProviderDomain domain = new WorldgenPlans.ProviderDomain(
			id("fittest"), 1.0D,
			new Climate.ParameterList<>(List.of(
				Pair.of(bestPoint, best),
				Pair.of(duplicatePoint, duplicateBest),
				Pair.of(nextPoint, next)
			)),
			0
		);
		WorldgenPlans.ProviderSelection plan = new WorldgenPlans.ProviderSelection(
			descriptor(), 1L, List.of(domain), Optional.of(domain.id()), Optional.empty(), Optional.empty()
		);

		WorldgenPlans.ProviderResult result = plan.resolve(
			domain.id(), Climate.target(0, 0, 0, 0, 0, 0)
		).orElseThrow();
		WorldgenPlans.CandidateFit fit = result.candidateFit();

		assertEquals(best, result.baseBiome());
		assertEquals(bestPoint, fit.ultimate().point());
		assertEquals(next, fit.penultimate().orElseThrow().biome());
		assertEquals(4_000_000L, fit.penultimate().orElseThrow().distance());
	}

	@Test
	void selectionPipelinePreservesBaseCandidateAndThreadsCurrentBiome() {
		List<WorldgenPlans.ProviderResult> observed = new java.util.ArrayList<>();
		WorldgenPlans.SelectionDecoration plan = new WorldgenPlans.SelectionDecoration(
			selectionDescriptor(),
			List.of(
				new WorldgenPlans.SelectionDecoratorStage(id("first"), 10, (result, spatial, target, x, y, z, sampler, surfaceContext) -> {
					observed.add(result);
					return MIDDLE;
				}),
				new WorldgenPlans.SelectionDecoratorStage(id("second"), 20, (result, spatial, target, x, y, z, sampler, surfaceContext) -> {
					observed.add(result);
					return FINAL;
				})
			)
		);

		Holder<Biome> selected = plan.apply(
			new WorldgenPlans.ProviderResult(id("domain"), BASE, false),
			new WorldgenPlans.SpatialResult(id("domain"), 0, 0),
			Climate.target(0, 0, 0, 0, 0, 0), 0, 0, 0, null, null
		);

		assertEquals(FINAL, selected);
		assertEquals(List.of(BASE, BASE), observed.stream().map(WorldgenPlans.ProviderResult::baseBiome).toList());
		assertEquals(List.of(BASE, MIDDLE), observed.stream().map(WorldgenPlans.ProviderResult::biome).toList());
	}

	@Test
	void duplicateSelectionStageIdsAreRejected() {
		WorldgenPlans.BiomeSelectionDecorator identity = (result, spatial, target, x, y, z, sampler, surfaceContext) -> result.biome();
		assertThrows(IllegalArgumentException.class, () -> new WorldgenPlans.SelectionDecoration(
			selectionDescriptor(),
			List.of(
				new WorldgenPlans.SelectionDecoratorStage(id("duplicate"), 10, identity),
				new WorldgenPlans.SelectionDecoratorStage(id("duplicate"), 20, identity)
			)
		));
	}

	@Test
	void appendedSelectionStagesRetainEveryDeclaredOutput() {
		WorldgenPlans.SelectionDecoration first = new WorldgenPlans.SelectionDecoration(
			selectionDescriptor(), List.of(), Set.of(BASE)
		);
		WorldgenPlans.SelectionDecoration second = new WorldgenPlans.SelectionDecoration(
			selectionDescriptor(), List.of(), Set.of(FINAL)
		);

		assertEquals(Set.of(BASE, FINAL), first.append(second, selectionDescriptor()).possibleOutputs());
	}

	@Test
	void sharedConfiguredFeatureRemainsTwoOrderedPlacedOccurrences() {
		ResourceKey<Biome> biome = ResourceKey.create(
			Registries.BIOME, ResourceLocation.fromNamespaceAndPath("unseen", "biome")
		);
		Holder<PlacedFeature> placed = Holder.direct((PlacedFeature) null);
		WorldgenPlans.PlacedFeatures plan = new WorldgenPlans.PlacedFeatures(
			new PlanDescriptor(
				ResourceLocation.fromNamespaceAndPath("test", "features"),
				WorldgenFacet.PLACED_FEATURES,
				CapabilityState.NORMALIZED,
				"registry_graph",
				"test",
				Optional.empty()
			),
			List.of(
				new WorldgenPlans.PlacedFeaturePipeline(
					biome, 4, 0, placed
				),
				new WorldgenPlans.PlacedFeaturePipeline(
					biome, 4, 1, placed
				)
			),
			List.of(),
			java.util.Map.of(),
			raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.empty()
		);

		assertEquals(2, plan.pipelines().size());
		assertEquals(List.of(0, 1), plan.pipelines().stream()
			.map(WorldgenPlans.PlacedFeaturePipeline::index).toList());
		assertEquals(List.of(placed, placed), plan.forBiome(biome, 4));
	}

	@Test
	void nonContiguousPlacedOccurrenceIndicesAreRejected() {
		ResourceKey<Biome> biome = ResourceKey.create(
			Registries.BIOME, ResourceLocation.fromNamespaceAndPath("unseen", "biome")
		);
		assertThrows(IllegalArgumentException.class, () -> new WorldgenPlans.PlacedFeatures(
			new PlanDescriptor(
				ResourceLocation.fromNamespaceAndPath("test", "features"),
				WorldgenFacet.PLACED_FEATURES,
				CapabilityState.NORMALIZED,
				"registry_graph",
				"test",
				Optional.empty()
			),
			List.of(new WorldgenPlans.PlacedFeaturePipeline(
				biome,
				4,
				1,
				Holder.direct((PlacedFeature) null)
			)),
			List.of(),
			java.util.Map.of(),
			raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.empty()
		));
	}

	private static WorldgenPlans.ProviderDomain domain(String path, int order) {
		return new WorldgenPlans.ProviderDomain(
			ResourceLocation.fromNamespaceAndPath("test", path), 1.0D, TABLE, order
		);
	}

	private static Climate.ParameterList<Holder<Biome>> table(Holder<Biome> value) {
		return new Climate.ParameterList<>(List.of(
			Pair.of(Climate.parameters(0, 0, 0, 0, 0, 0, 0), value)
		));
	}

	private static PlanDescriptor descriptor() {
		return new PlanDescriptor(
			ResourceLocation.fromNamespaceAndPath("test", "providers"),
			WorldgenFacet.PROVIDER_SELECTION,
			CapabilityState.PROVIDER_CONTRACT,
			"synthetic",
			"test",
			Optional.empty()
		);
	}

	private static PlanDescriptor selectionDescriptor() {
		return new PlanDescriptor(
			id("selection"), WorldgenFacet.SELECTION_DECORATION, CapabilityState.PROVIDER_CONTRACT,
			"synthetic", "test", Optional.empty()
		);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Holder<Biome> biome(String value) {
		return (Holder) Holder.direct(value);
	}
}
