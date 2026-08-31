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
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

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
	void selectionPipelinePreservesBaseCandidateAndThreadsCurrentBiome() {
		List<WorldgenPlans.ProviderResult> observed = new java.util.ArrayList<>();
		WorldgenPlans.SelectionDecoration plan = new WorldgenPlans.SelectionDecoration(
			selectionDescriptor(),
			List.of(
				new WorldgenPlans.SelectionDecoratorStage(id("first"), (result, spatial, target, x, y, z, sampler) -> {
					observed.add(result);
					return MIDDLE;
				}),
				new WorldgenPlans.SelectionDecoratorStage(id("second"), (result, spatial, target, x, y, z, sampler) -> {
					observed.add(result);
					return FINAL;
				})
			)
		);

		Holder<Biome> selected = plan.apply(
			new WorldgenPlans.ProviderResult(id("domain"), BASE, false),
			new WorldgenPlans.SpatialResult(id("domain"), 0, 0),
			Climate.target(0, 0, 0, 0, 0, 0), 0, 0, 0, null
		);

		assertEquals(FINAL, selected);
		assertEquals(List.of(BASE, BASE), observed.stream().map(WorldgenPlans.ProviderResult::baseBiome).toList());
		assertEquals(List.of(BASE, MIDDLE), observed.stream().map(WorldgenPlans.ProviderResult::biome).toList());
	}

	@Test
	void duplicateSelectionStageIdsAreRejected() {
		WorldgenPlans.BiomeSelectionDecorator identity = (result, spatial, target, x, y, z, sampler) -> result.biome();
		assertThrows(IllegalArgumentException.class, () -> new WorldgenPlans.SelectionDecoration(
			selectionDescriptor(),
			List.of(
				new WorldgenPlans.SelectionDecoratorStage(id("duplicate"), identity),
				new WorldgenPlans.SelectionDecoratorStage(id("duplicate"), identity)
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
		Holder<ConfiguredFeature<?, ?>> configured = Holder.direct((ConfiguredFeature<?, ?>) null);
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
					biome, 4, 0, placed, configured, List.of(), List.of()
				),
				new WorldgenPlans.PlacedFeaturePipeline(
					biome, 4, 1, placed, configured, List.of(), List.of()
				)
			),
			List.of(),
			java.util.Map.of(),
			raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.empty()
		);

		assertEquals(2, plan.pipelines().size());
		assertEquals(List.of(0, 1), plan.pipelines().stream()
			.map(WorldgenPlans.PlacedFeaturePipeline::index).toList());
		assertEquals(List.of(configured, configured), plan.pipelines().stream()
			.map(WorldgenPlans.PlacedFeaturePipeline::configuredFeature).toList());
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
				Holder.direct((PlacedFeature) null),
				Holder.direct((ConfiguredFeature<?, ?>) null),
				List.of(),
				List.of()
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
