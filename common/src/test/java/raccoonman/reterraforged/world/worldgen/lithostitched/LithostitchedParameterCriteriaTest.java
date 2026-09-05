package raccoonman.reterraforged.world.worldgen.lithostitched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import raccoonman.reterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge.ClimateAxis;
import raccoonman.reterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge.ClimateCriterion;
import raccoonman.reterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge.CapturedInjector;
import raccoonman.reterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge.DensityCriterion;
import raccoonman.reterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge.Kind;
import raccoonman.reterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge.NumericRange;
import raccoonman.reterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge.ParameterCriteria;
import net.minecraft.world.level.dimension.LevelStem;

class LithostitchedParameterCriteriaTest {
	private static final ResourceLocation REGION = ResourceLocation.fromNamespaceAndPath("test", "region");
	private static final Climate.TargetPoint TARGET = new Climate.TargetPoint(
		1000L, 2000L, 3000L, 4000L, 5000L, 6000L
	);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void climateAxesUseTheAuthoredQuantizedCoordinates() {
		assertEquals(0.1D, ClimateAxis.TEMPERATURE.value(TARGET));
		assertEquals(0.2D, ClimateAxis.HUMIDITY.value(TARGET));
		assertEquals(0.3D, ClimateAxis.CONTINENTALNESS.value(TARGET));
		assertEquals(0.4D, ClimateAxis.EROSION.value(TARGET));
		assertEquals(0.5D, ClimateAxis.DEPTH.value(TARGET));
		assertEquals(0.6D, ClimateAxis.WEIRDNESS.value(TARGET));
	}

	@Test
	void criteriaRetainInclusiveClimateAndRegionSemantics() {
		ParameterCriteria criteria = new ParameterCriteria(
			List.of(new ClimateCriterion(ClimateAxis.TEMPERATURE, new NumericRange(0.1D, 0.1D))),
			List.of(),
			Optional.of(REGION)
		);

		assertTrue(criteria.matches(0, 0, 0, TARGET, REGION));
		assertFalse(criteria.matches(0, 0, 0, TARGET, ResourceLocation.withDefaultNamespace("other")));
		assertFalse(criteria.matches(
			0, 0, 0, new Climate.TargetPoint(1001L, 2000L, 3000L, 4000L, 5000L, 6000L), REGION
		));
	}

	@Test
	void densityCriteriaUseExactBlockCoordinates() {
		DensityFunction function = new CoordinateDensity();
		ParameterCriteria criteria = new ParameterCriteria(
			List.of(),
			List.of(new DensityCriterion(function, function, new NumericRange(2_003_004D, 2_003_004D))),
			Optional.empty()
		);

		assertTrue(criteria.matches(2, 3, 4, TARGET, REGION));
		assertFalse(criteria.matches(3, 3, 4, TARGET, REGION));
		assertFalse(criteria.matches(2, 4, 4, TARGET, REGION));
		assertFalse(criteria.matches(2, 3, 5, TARGET, REGION));
	}

	@Test
	void criteriaOwnImmutableCollections() {
		ArrayList<ClimateCriterion> climate = new ArrayList<>();
		ArrayList<DensityCriterion> density = new ArrayList<>();
		climate.add(new ClimateCriterion(ClimateAxis.DEPTH, new NumericRange(-1.0D, 1.0D)));
		ParameterCriteria criteria = new ParameterCriteria(climate, density, Optional.empty());

		climate.clear();
		density.add(new DensityCriterion(new CoordinateDensity(), new CoordinateDensity(), new NumericRange(0, 1)));
		assertEquals(1, criteria.climate().size());
		assertTrue(criteria.density().isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> criteria.climate().clear());
		assertThrows(IllegalArgumentException.class, () -> new NumericRange(1.0D, -1.0D));
	}

	@Test
	void undefinedEqualPriorityTiesUseStableInjectorIds() {
		List<CapturedInjector> injectors = new ArrayList<>(List.of(
			injector("zeta", 1000, 0),
			injector("alpha", 1000, 1),
			injector("middle", 900, 2)
		));

		injectors.sort(LithostitchedCapabilityProvider.executionOrder());
		assertEquals(
			List.of("test:middle", "test:alpha", "test:zeta"),
			injectors.stream().map(value -> value.id().toString()).toList()
		);
	}

	@Test
	void injectorSemanticsAreSelectedByPublicCodecIdentity() {
		assertEquals(Kind.ADD_POINTS, kind("add_points"));
		assertEquals(Kind.FORCE, kind("force_placement"));
		assertEquals(Kind.DISPATCH, kind("dispatch_alternate_layout"));
		assertEquals(Kind.REPLACE_PARTIALLY, kind("replace_partially"));
		assertEquals(Kind.REPLACE_FULLY, kind("replace_fully"));
		assertEquals(
			Kind.UNKNOWN,
			LithostitchedInjectionBridge.kindForCodec(
				ResourceLocation.fromNamespaceAndPath("unseen", "custom_injector")
			)
		);
	}

	@Test
	void densityTypeInspectionTraversesHolderBackedGraphsWithoutCallingTheHolderCodec() {
		DensityFunction holder = new DensityFunctions.HolderHolder(
			Holder.direct(DensityFunctions.endIslands(0L))
		);

		DensityFunction mapped = holder.mapAll(function -> {
			LithostitchedInjectionBridge.densityFunctionType(function);
			return function;
		});

		assertTrue(mapped instanceof DensityFunctions.HolderHolder);
		assertTrue(LithostitchedInjectionBridge.densityFunctionType(holder).isEmpty());
		assertEquals(
			ResourceLocation.withDefaultNamespace("end_islands"),
			LithostitchedInjectionBridge.densityFunctionType(DensityFunctions.endIslands(0L)).orElseThrow()
		);
	}

	private static Kind kind(String path) {
		return LithostitchedInjectionBridge.kindForCodec(
			ResourceLocation.fromNamespaceAndPath("lithostitched", path)
		);
	}

	private static CapturedInjector injector(String id, int priority, int encounter) {
		return new CapturedInjector(
			ResourceLocation.fromNamespaceAndPath("test", id),
			ResourceLocation.fromNamespaceAndPath("test", "codec"),
			encounter,
			priority,
			Kind.REPLACE_PARTIALLY,
			LevelStem.OVERWORLD,
			Optional.empty(),
			Optional.empty(),
			List.of(),
			Optional.empty(),
			List.of(),
			Optional.empty()
		);
	}

	private record CoordinateDensity() implements DensityFunction.SimpleFunction {
		@Override
		public double compute(DensityFunction.FunctionContext context) {
			return context.blockX() * 1_000_000D + context.blockY() * 1_000D + context.blockZ();
		}

		@Override
		public double minValue() {
			return -Double.MAX_VALUE;
		}

		@Override
		public double maxValue() {
			return Double.MAX_VALUE;
		}

		@Override
		public KeyDispatchDataCodec<? extends DensityFunction> codec() {
			return DensityFunctions.zero().codec();
		}
	}
}
