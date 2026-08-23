package raccoonman.reterraforged.world.worldgen.feature.ore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.FanoutStage;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalFrame;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlanner.BiomeInput;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlanner.FeatureInput;

class DynamicOrePlannerTest {
	private static final VerticalFrame DEEP_FRAME = new VerticalFrame(-624, 383, 63);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void derivesOneDynamicTransformForRepeatedBiomeMemberships() {
		PlacedFeature active = ore(7);
		BiomeInput plains = biome(new FeatureInput("test:ore", active));
		BiomeInput forest = biome(new FeatureInput("test:ore", active));

		DynamicOrePlan plan = new DynamicOrePlanner().build(List.of(plains, forest), DEEP_FRAME);

		assertEquals(1, plan.standardOres());
		assertEquals(1, plan.verticalTransforms().size());
		assertTrue(plan.skippedReasons().isEmpty());
	}

	@Test
	void referenceFrameDelegatesWithoutPublishingATransform() {
		DynamicOrePlan plan = new DynamicOrePlanner().build(
			List.of(biome(new FeatureInput("test:ore", ore(7)))),
			new VerticalFrame(-64, 319, 63)
		);

		assertTrue(plan.verticalTransforms().isEmpty());
		assertEquals(1, plan.delegatedFeatures());
	}

	@Test
	void conflictingFeatureIdentitiesForOneIdFailClosed() {
		DynamicOrePlan plan = new DynamicOrePlanner().build(
			List.of(
				biome(new FeatureInput("test:ore", ore(4))),
				biome(new FeatureInput("test:ore", ore(9)))
			),
			DEEP_FRAME
		);

		assertTrue(plan.verticalTransforms().isEmpty());
		assertEquals(1, plan.skippedReasons().get("CONFLICTING_FEATURES_FOR_ID"));
	}

	@Test
	void selectsSafeFanoutBeforeSpatialSampling() {
		assertFanout(oreWithPlacements(
			CountPlacement.of(7),
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(0), VerticalAnchor.absolute(64))
		), FanoutStage.COUNT, 0, 2);
		assertFanout(oreWithPlacements(
			RarityFilter.onAverageOnceEvery(9),
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(0), VerticalAnchor.absolute(64))
		), FanoutStage.RARITY, 0, 2);
		assertFanout(oreWithPlacements(
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(0), VerticalAnchor.absolute(64))
		), FanoutStage.IN_SQUARE, 0, 1);
		assertFanout(oreWithPlacements(
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(0), VerticalAnchor.absolute(64)),
			InSquarePlacement.spread()
		), FanoutStage.HEIGHT, 0, 0);
	}

	private static void assertFanout(
		PlacedFeature feature,
		FanoutStage stage,
		int modifierIndex,
		int heightModifierIndex
	) {
		var transform = new DynamicOrePlanner().build(
			List.of(biome(new FeatureInput("test:ore", feature))), DEEP_FRAME
		).verticalTransforms().get("test:ore");
		assertEquals(stage, transform.fanoutStage());
		assertEquals(modifierIndex, transform.fanoutModifierIndex());
		assertEquals(heightModifierIndex, transform.heightModifierIndex());
	}

	private static BiomeInput biome(FeatureInput... undergroundOres) {
		List<List<FeatureInput>> steps = new ArrayList<>();
		for (int index = 0; index < 6; index++) {
			steps.add(List.of());
		}
		steps.add(List.of(undergroundOres));
		return new BiomeInput(steps);
	}

	private static PlacedFeature ore(int attempts) {
		return oreWithPlacements(
			CountPlacement.of(attempts),
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(0), VerticalAnchor.absolute(64))
		);
	}

	private static PlacedFeature oreWithPlacements(PlacementModifier... placements) {
		return new PlacedFeature(
			Holder.direct(new ConfiguredFeature<>(Feature.ORE, new OreConfiguration(
				new BlockMatchTest(Blocks.STONE), Blocks.IRON_ORE.defaultBlockState(), 6
			))),
			List.of(placements)
		);
	}
}
