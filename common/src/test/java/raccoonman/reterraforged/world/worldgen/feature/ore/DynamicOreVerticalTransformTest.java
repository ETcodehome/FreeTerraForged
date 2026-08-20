package raccoonman.reterraforged.world.worldgen.feature.ore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.util.RandomSource;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Anchor;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.AnchorType;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightProviderShape;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightSemantics;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalFrame;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalTransform;

class DynamicOreVerticalTransformTest {
	private static final HeightSemantics DIAMOND = new HeightSemantics(
		HeightProviderShape.TRAPEZOID,
		new Anchor(AnchorType.ABOVE_BOTTOM, -80),
		new Anchor(AnchorType.ABOVE_BOTTOM, 80),
		0
	);

	@Test
	void deepDiamondMatchesTheOfflineLocalIntensityModel() {
		VerticalTransform transform = DynamicOreVerticalTransform.derive(
			"minecraft:ore_diamond",
			"contract",
			DIAMOND,
			new VerticalFrame(-624, 383, 63)
		).transform().orElseThrow();

		assertEquals(9.545953360768173, transform.expectedOutputsPerInput(), 1.0E-12);
		assertEquals(-1404, transform.cumulativeIntensity().getFirst().y());
		assertEquals(16, transform.cumulativeIntensity().getLast().y());
		assertEquals(
			transform.expectedOutputsPerInput(),
			transform.cumulativeIntensity().getLast().cumulativeIntensity(),
			1.0E-12
		);
		for (int index = 1; index < transform.cumulativeIntensity().size(); index++) {
			assertTrue(
				transform.cumulativeIntensity().get(index).cumulativeIntensity()
					> transform.cumulativeIntensity().get(index - 1).cumulativeIntensity()
			);
		}
	}

	@Test
	void contractionBelowOneUsesUnbiasedStochasticThinning() {
		VerticalTransform transform = DynamicOreVerticalTransform.derive(
			"minecraft:ore_diamond",
			"contract",
			DIAMOND,
			new VerticalFrame(-16, 383, 63)
		).transform().orElseThrow();
		assertEquals(0.2674897119341565, transform.expectedOutputsPerInput(), 1.0E-12);

		RandomSource random = RandomSource.create(9918273L);
		int trials = 200_000;
		long outputs = 0;
		for (int index = 0; index < trials; index++) {
			outputs += DynamicOrePlacement.stochasticRound(transform.expectedOutputsPerInput(), random);
		}
		assertEquals(transform.expectedOutputsPerInput(), outputs / (double)trials, 0.003);
	}

	@Test
	void fixedGeologicalSegmentDelegatesWithoutChangingItsRandomSampling() {
		HeightSemantics fixed = new HeightSemantics(
			HeightProviderShape.UNIFORM,
			new Anchor(AnchorType.ABSOLUTE, 0),
			new Anchor(AnchorType.ABSOLUTE, 2),
			0
		);
		var derivation = DynamicOreVerticalTransform.derive(
			"test:fixed",
			"contract",
			fixed,
			new VerticalFrame(-1024, 1023, 63)
		);

		assertTrue(derivation.transform().isEmpty());
		assertEquals("FEATURE_VERTICAL_MAPPING_IS_IDENTITY", derivation.reasonCode());
	}

	@Test
	void unchangedDefaultDepthDiamondDelegatesEvenWhenTheWorldTopIsHigher() {
		var derivation = DynamicOreVerticalTransform.derive(
			"minecraft:ore_diamond",
			"contract",
			DIAMOND,
			new VerticalFrame(-64, 383, 63)
		);

		assertTrue(derivation.transform().isEmpty());
		assertEquals("FEATURE_VERTICAL_MAPPING_IS_IDENTITY", derivation.reasonCode());
	}

	@Test
	void shortCeilingRetainsAuthoredAboveWorldClippingTail() {
		HeightSemantics upperIron = new HeightSemantics(
			HeightProviderShape.TRAPEZOID,
			new Anchor(AnchorType.ABSOLUTE, 80),
			new Anchor(AnchorType.ABSOLUTE, 384),
			0
		);
		VerticalTransform transform = DynamicOreVerticalTransform.derive(
			"minecraft:ore_iron_upper",
			"contract",
			upperIron,
			new VerticalFrame(-64, 127, 63)
		).transform().orElseThrow();

		assertEquals(0.2529182879377432, transform.expectedOutputsPerInput(), 1.0E-12);
		assertEquals(67, transform.cumulativeIntensity().getFirst().y());
		assertEquals(144, transform.cumulativeIntensity().getLast().y());
		assertTrue(transform.cumulativeIntensity().stream().anyMatch(value -> value.y() > 127));
	}

	@Test
	void malformedFramesAndEmptyAuthoredRangesFailClosed() {
		assertFalse(DynamicOreVerticalTransform.derive(
			"test:bad_frame", "contract", DIAMOND, new VerticalFrame(-64, 319, 7)
		).transform().isPresent());
		HeightSemantics empty = new HeightSemantics(
			HeightProviderShape.UNIFORM,
			new Anchor(AnchorType.ABSOLUTE, 20),
			new Anchor(AnchorType.ABSOLUTE, 10),
			0
		);
		assertEquals(
			"EMPTY_REFERENCE_HEIGHT_RANGE",
			DynamicOreVerticalTransform.derive(
				"test:empty", "contract", empty, new VerticalFrame(-64, 383, 63)
			).reasonCode()
		);
	}
}
