package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.MapCodec;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;

class WorldgenPlanDiagnosticsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void snapshotIsDeterministicValueOnlyAndCensusesTheExecutablePlan() {
		WorldgenPlan plan = WorldgenPlanCompilerTest.emptyPlan();

		WorldgenPlanDiagnostics.Snapshot snapshot = plan.diagnostics();
		String first = snapshot.toJson().toString();
		String second = snapshot.toJson().toString();

		assertEquals(first, second);
		assertEquals(3, snapshot.toJson().get("schema_version").getAsInt());
		assertEquals(10, snapshot.facets().size());
		assertEquals(
			plan.owner().id().toString(),
			snapshot.toJson().getAsJsonObject("owner").get("id").getAsString()
		);
		assertEquals(
			plan.owner().resourceRevision(),
			snapshot.toJson().getAsJsonObject("owner").get("resource_revision").getAsLong()
		);
		assertTrue(snapshot.toJson().has("source_shape"));
		assertTrue(snapshot.toJson().has("graph_census"));
		assertEquals(
			plan.structures().rules().size(),
			snapshot.toJson().getAsJsonObject("graph_census").get("structure_rules").getAsInt()
		);
		assertTrue(snapshot.toJson().has("possible_outputs"));
		assertTrue(snapshot.toJson().has("density_extent"));
		assertTrue(snapshot.toJson().has("adaptations"));
		assertTrue(snapshot.toJson().getAsJsonObject("adaptations").has("chunk_local_placement"));
		assertTrue(snapshot.toJson().has("capability_summary"));
		assertEquals(
			plan.report().nodes().size(),
			snapshot.toJson().getAsJsonObject("capability_summary").get("node_count").getAsInt()
		);
		assertFalse(first.contains("WorldgenPlan["));
		assertFalse(first.contains("@"));
	}

	@Test
	void unknownCustomDensityRetainsTheFullConfiguredExtent() {
		WorldgenPlan base = WorldgenPlanCompilerTest.emptyPlan();
		NoiseGeneratorSettings original = NoiseGeneratorSettings.dummy();
		NoiseRouter router = original.noiseRouter();
		NoiseRouter customRouter = new NoiseRouter(
			router.barrierNoise(), router.fluidLevelFloodednessNoise(), router.fluidLevelSpreadNoise(),
			router.lavaNoise(), router.temperature(), router.vegetation(), router.continents(),
			router.erosion(), router.depth(), router.ridges(), router.initialDensityWithoutJaggedness(),
			new CustomDensity(), router.veinToggle(), router.veinRidged(), router.veinGap()
		);
		NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
			original.noiseSettings(), original.defaultBlock(), original.defaultFluid(), customRouter,
			original.surfaceRule(), original.spawnTarget(), original.seaLevel(),
			original.disableMobGeneration(), original.aquifersEnabled(), original.oreVeinsEnabled(),
			original.useLegacyRandomSource()
		);
		WorldgenPlan plan = copy(
			base,
			new WorldgenPlans.DensitySettings(
				base.densitySettings().descriptor(), Optional.of(Holder.direct(settings))
			),
			base.report()
		);

		WorldgenPlanDiagnostics.DensityExtent extent = plan.diagnostics().densityExtent();

		assertTrue(extent.available());
		assertEquals("full_configured_height", extent.mode());
		assertEquals(original.noiseSettings().minY(), extent.minY());
		assertEquals(original.noiseSettings().height(), extent.height());
		assertEquals("bounded_density_analysis_not_enabled", extent.fallbackReason());
	}

	@Test
	void compactDiagnosticsRetainUnavailableNodeTypeOwnerAndFirstCause() {
		WorldgenPlan base = WorldgenPlanCompilerTest.emptyPlan();
		CapabilityFailure failure = CapabilityFailure.unavailable(
			"unknown_synthetic_type", "synthetic node has no stable acquisition contract"
		);
		CapabilityNodeReport unavailable = new CapabilityNodeReport(
			ResourceLocation.fromNamespaceAndPath("synthetic", "custom_node"),
			WorldgenFacet.BIOME_COMPOSITION,
			CapabilityState.UNAVAILABLE,
			"synthetic:custom_codec",
			base.owner().type(),
			"synthetic unsupported node",
			Optional.of(failure)
		);
		WorldgenCapabilityReport report = new WorldgenCapabilityReport(
			List.of(unavailable), base.execution()
		);
		WorldgenPlan plan = copy(base, base.densitySettings(), report);

		var nodes = plan.diagnostics().capabilitySummary().unavailableNodes();

		assertEquals(1, nodes.size());
		assertEquals(unavailable.id(), nodes.getFirst().id());
		assertEquals("synthetic:custom_codec", nodes.getFirst().mechanism());
		assertEquals(base.owner().type(), nodes.getFirst().owner());
		assertEquals("unknown_synthetic_type", nodes.getFirst().firstCause().code());
	}

	private static WorldgenPlan copy(
		WorldgenPlan base,
		WorldgenPlans.DensitySettings density,
		WorldgenCapabilityReport report
	) {
		return new WorldgenPlan(
			base.owner(), base.biomeComposition(), base.providerSelection(), base.selectionDecoration(),
			base.spatialOwnership(), base.samplerDecoration(), density, base.surface(), base.carvers(),
			base.placedFeatures(), base.structures(), base.execution(), report
		);
	}

	private record CustomDensity() implements DensityFunction.SimpleFunction {
		@Override
		public double compute(FunctionContext context) {
			return context.blockY() >= 0 ? 1.0D : -1.0D;
		}

		@Override
		public double minValue() {
			return -1.0D;
		}

		@Override
		public double maxValue() {
			return 1.0D;
		}

		@Override
		public KeyDispatchDataCodec<? extends DensityFunction> codec() {
			return KeyDispatchDataCodec.of(MapCodec.unit(this));
		}
	}
}
