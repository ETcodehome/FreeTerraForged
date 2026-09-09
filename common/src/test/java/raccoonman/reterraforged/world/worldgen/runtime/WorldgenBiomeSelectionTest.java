package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceLocation;

class WorldgenBiomeSelectionTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void rejectsTheExactUnavailableSelectionCauseBeforeExecution() {
		WorldgenPlan base = WorldgenPlanCompilerTest.emptyPlan();
		WorldgenPlan plan = withSelection(
			base,
			new WorldgenPlans.SelectionDecoration(
				unavailable(
					WorldgenFacet.SELECTION_DECORATION,
					"missing_factory",
					"request-owned factory is unavailable"
				),
				List.of()
			)
		);

		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> WorldgenBiomeSelection.requireExecutablePlan(plan)
		);
		assertEquals(
			"FTF biome selection facet SELECTION_DECORATION is unavailable [missing_factory]: "
				+ "request-owned factory is unavailable",
			failure.getMessage()
		);
	}

	@Test
	void rejectsUnavailableProviderFacetEvenWithoutDecorators() {
		WorldgenPlan base = WorldgenPlanCompilerTest.emptyPlan();
		WorldgenPlan plan = new WorldgenPlan(
			base.owner(), base.biomeComposition(),
			new WorldgenPlans.ProviderSelection(
				unavailable(WorldgenFacet.PROVIDER_SELECTION, "inactive_provider", "inactive"),
				0L, List.of(), Optional.empty(), Optional.empty(), Optional.empty()
			),
			base.selectionDecoration(),
			new WorldgenPlans.SpatialOwnership(
				unavailable(WorldgenFacet.SPATIAL_OWNERSHIP, "inactive_spatial", "inactive"),
				Optional.empty()
			),
			base.samplerDecoration(), base.densitySettings(), base.surface(), base.carvers(),
			base.placedFeatures(), base.structures(), base.execution(), base.report()
		);

		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> WorldgenBiomeSelection.requireExecutablePlan(plan)
		);
		assertEquals(
			"FTF biome selection facet PROVIDER_SELECTION is unavailable [inactive_provider]: inactive",
			failure.getMessage()
		);
	}

	@Test
	void requiresSpatialOwnershipForAnActiveDecorator() {
		WorldgenPlan base = WorldgenPlanCompilerTest.emptyPlan();
		WorldgenPlans.SelectionDecoration selection = new WorldgenPlans.SelectionDecoration(
			base.selectionDecoration().descriptor(),
			List.of(new WorldgenPlans.SelectionDecoratorStage(
				id("decorator"),
				0,
				(result, spatial, target, x, y, z, sampler, surfaceContext) -> result.biome()
			))
		);
		WorldgenPlan plan = new WorldgenPlan(
			base.owner(), base.biomeComposition(), base.providerSelection(),
			selection,
			new WorldgenPlans.SpatialOwnership(
				unavailable(WorldgenFacet.SPATIAL_OWNERSHIP, "spatial_missing", "cell resolver is unavailable"),
				Optional.empty()
			),
			base.samplerDecoration(), base.densitySettings(), base.surface(), base.carvers(),
			base.placedFeatures(), base.structures(), base.execution(), base.report()
		);

		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> WorldgenBiomeSelection.requireExecutablePlan(plan)
		);
		assertEquals(
			"FTF biome selection facet SPATIAL_OWNERSHIP is unavailable [spatial_missing]: "
				+ "cell resolver is unavailable",
			failure.getMessage()
		);
	}

	@Test
	void completeDirectCustomRootNeedsNoCandidateOrSpatialPlan() {
		WorldgenPlan base = WorldgenPlanCompilerTest.emptyPlan();
		Holder<Biome> output = Holder.direct((Biome) null);
		WorldgenPlans.ProviderSelection direct = new WorldgenPlans.ProviderSelection(
			base.providerSelection().descriptor(), 0L, List.of(), Optional.empty(), Optional.empty(),
			Optional.empty(), Optional.of(new BiomeSourcePlanInput(
				id("direct"), Set.of(output), WorldgenQueryMode.OWNER_SERIAL,
				(x, y, z, sampler) -> output
			))
		);
		WorldgenPlan plan = new WorldgenPlan(
			base.owner(), base.biomeComposition(), direct,
			new WorldgenPlans.SelectionDecoration(base.selectionDecoration().descriptor(), List.of()),
			new WorldgenPlans.SpatialOwnership(base.spatialOwnership().descriptor(), Optional.empty()),
			base.samplerDecoration(), base.densitySettings(), base.surface(), base.carvers(),
			base.placedFeatures(), base.structures(), base.execution(), base.report()
		);

		assertDoesNotThrow(() -> WorldgenBiomeSelection.requireExecutablePlan(plan));
		assertEquals(Set.of(output), WorldgenBiomeSelection.possibleBiomes(plan));
		assertEquals(Set.of(output), WorldgenBiomeSelection.prepare(plan).possibleBiomes());
	}

	private static WorldgenPlan withSelection(
		WorldgenPlan base,
		WorldgenPlans.SelectionDecoration selection
	) {
		return new WorldgenPlan(
			base.owner(), base.biomeComposition(), base.providerSelection(),
			selection, base.spatialOwnership(), base.samplerDecoration(), base.densitySettings(),
			base.surface(), base.carvers(), base.placedFeatures(), base.structures(), base.execution(),
			base.report()
		);
	}

	private static PlanDescriptor unavailable(WorldgenFacet facet, String code, String message) {
		return new PlanDescriptor(
			id(facet.name().toLowerCase()), facet, CapabilityState.UNAVAILABLE, "synthetic", message,
			Optional.of(CapabilityFailure.unavailable(code, message))
		);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}
}
