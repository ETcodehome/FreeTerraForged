package raccoonman.reterraforged.world.worldgen.feature.ore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import net.minecraft.core.Holder;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.block.Blocks;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Contract;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Action;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.FanoutStage;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalFrame;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlanner.BiomeInput;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlanner.FeatureInput;

class DynamicOrePlannerTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void preservesDuplicateMembershipOrderAndReportsInactiveEntries() {
		PlacedFeature active = ore(5);
		PlacedFeature inactive = ore(2);
		BiomeInput biome = new BiomeInput("minecraft:plains", steps(
			new FeatureInput("example:active", active),
			new FeatureInput("example:active", active)
		));

		DynamicOrePlan plan = new DynamicOrePlanner().build(
			List.of(biome),
			List.of(new FeatureInput("example:inactive", inactive), new FeatureInput("example:active", active))
		);

		assertEquals(3, plan.occurrences().size());
		assertEquals(List.of(0, 1), plan.occurrences().subList(0, 2).stream()
			.map(occurrence -> occurrence.membership().orElseThrow().order())
			.toList());
		assertEquals(Contract.NO_ACTIVE_MEMBERSHIP, plan.occurrences().get(2).contract());
		assertEquals("example:inactive", plan.occurrences().get(2).placedFeatureId());
		assertTrue(plan.summary().contains("inspection_failed=0"));
		assertEquals(
			plan.occurrences().get(0).contractFingerprint(),
			plan.occurrences().get(1).contractFingerprint()
		);
	}

	@Test
	void normalizesEquivalentLoaderGraphsToTheSameFingerprint() {
		PlacedFeature first = ore(3);
		PlacedFeature second = ore(8);
		BiomeInput plains = new BiomeInput("minecraft:plains", steps(new FeatureInput("test:first", first)));
		BiomeInput forest = new BiomeInput("minecraft:forest", steps(new FeatureInput("test:second", second)));
		DynamicOrePlanner planner = new DynamicOrePlanner();

		DynamicOrePlan fabric = planner.build(
			List.of(plains, forest),
			List.of(new FeatureInput("test:first", first), new FeatureInput("test:second", second))
		);
		DynamicOrePlan neoForge = planner.build(
			List.of(forest, plains),
			List.of(new FeatureInput("test:second", second), new FeatureInput("test:first", first))
		);

		assertEquals(fabric.graphFingerprint(), neoForge.graphFingerprint());
		assertEquals(fabric.occurrences(), neoForge.occurrences());
	}

	@Test
	void planIsValueOnlyImmutableAndSafeForConcurrentReads() throws Exception {
		PlacedFeature feature = ore(4);
		DynamicOrePlan plan = new DynamicOrePlanner().build(
			List.of(new BiomeInput("minecraft:plains", steps(new FeatureInput("test:ore", feature)))),
			List.of(new FeatureInput("test:ore", feature))
		);

		assertThrows(UnsupportedOperationException.class, () -> plan.occurrences().clear());
		assertThrows(UnsupportedOperationException.class, () -> plan.occurrences().getFirst().placementModifierTypes().add("test:mutate"));
		for (RecordComponent component : DynamicOrePlan.Occurrence.class.getRecordComponents()) {
			assertTrue(!component.getType().getName().startsWith("net.minecraft"), component.getName());
		}

		try (var executor = Executors.newFixedThreadPool(4)) {
			List<Callable<String>> readers = Collections.nCopies(64, () -> plan.graphFingerprint() + plan.summary());
			List<String> results = executor.invokeAll(readers).stream().map(future -> {
				try {
					return future.get();
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
			}).toList();
			assertEquals(1, results.stream().distinct().count());
		}
	}

	@Test
	void replacingARegistryEpochProducesAnIndependentPlan() {
		DynamicOrePlanner planner = new DynamicOrePlanner();
		PlacedFeature oldFeature = ore(1);
		DynamicOrePlan oldPlan = planner.build(
			List.of(new BiomeInput("minecraft:plains", steps(new FeatureInput("test:ore", oldFeature)))),
			List.of(new FeatureInput("test:ore", oldFeature))
		);
		PlacedFeature newFeature = ore(12);
		DynamicOrePlan newPlan = planner.build(
			List.of(new BiomeInput("minecraft:plains", steps(new FeatureInput("test:ore", newFeature)))),
			List.of(new FeatureInput("test:ore", newFeature))
		);

		assertNotEquals(oldPlan.graphFingerprint(), newPlan.graphFingerprint());
		assertEquals(1, oldPlan.occurrences().size());
		assertEquals(1, newPlan.occurrences().size());
	}

	@Test
	void derivesOneDynamicTransformForRepeatedBiomeMemberships() {
		PlacedFeature active = ore(7);
		BiomeInput plains = new BiomeInput("minecraft:plains", steps(new FeatureInput("test:ore", active)));
		BiomeInput forest = new BiomeInput("minecraft:forest", steps(new FeatureInput("test:ore", active)));

		DynamicOrePlan plan = new DynamicOrePlanner().build(
			List.of(plains, forest),
			List.of(new FeatureInput("test:ore", active)),
			java.util.Optional.of(new VerticalFrame(-624, 383, 63))
		);

		assertEquals(1, plan.verticalTransforms().size());
		assertEquals(2, plan.occurrences().size());
		assertTrue(plan.occurrences().stream().allMatch(occurrence -> occurrence.action() == Action.DYNAMIC_VERTICAL_DENSITY));
	}

	@Test
	void referenceFrameDelegatesWithoutPublishingATransform() {
		PlacedFeature active = ore(7);
		DynamicOrePlan plan = new DynamicOrePlanner().build(
			List.of(new BiomeInput("minecraft:plains", steps(new FeatureInput("test:ore", active)))),
			List.of(new FeatureInput("test:ore", active)),
			java.util.Optional.of(new VerticalFrame(-64, 319, 63))
		);

		assertTrue(plan.verticalTransforms().isEmpty());
		assertEquals(Action.DELEGATE_REFERENCE_IDENTITY, plan.occurrences().getFirst().action());
	}

	@Test
	void selectsRarityFanoutBeforeHorizontalSampling() {
		DynamicOrePlan plan = transformedPlan(oreWithPlacements(
			RarityFilter.onAverageOnceEvery(9),
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(0), VerticalAnchor.absolute(64))
		));

		var transform = plan.verticalTransforms().get("test:ore");
		assertEquals(FanoutStage.RARITY, transform.fanoutStage());
		assertEquals(0, transform.fanoutModifierIndex());
		assertEquals(2, transform.heightModifierIndex());
	}

	@Test
	void selectsInSquareFanoutForImplicitMultiplicity() {
		DynamicOrePlan plan = transformedPlan(oreWithPlacements(
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(0), VerticalAnchor.absolute(64))
		));

		var transform = plan.verticalTransforms().get("test:ore");
		assertEquals(FanoutStage.IN_SQUARE, transform.fanoutStage());
		assertEquals(0, transform.fanoutModifierIndex());
		assertEquals(1, transform.heightModifierIndex());
	}

	@Test
	void selectsHeightFanoutWhenHeightIsTheFirstSpatialSampler() {
		DynamicOrePlan plan = transformedPlan(oreWithPlacements(
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(0), VerticalAnchor.absolute(64)),
			InSquarePlacement.spread()
		));

		var transform = plan.verticalTransforms().get("test:ore");
		assertEquals(FanoutStage.HEIGHT, transform.fanoutStage());
		assertEquals(0, transform.fanoutModifierIndex());
		assertEquals(0, transform.heightModifierIndex());
	}

	private static List<List<FeatureInput>> steps(FeatureInput... undergroundOres) {
		List<List<FeatureInput>> steps = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			steps.add(List.of());
		}
		steps.add(List.of(undergroundOres));
		return steps;
	}

	private static PlacedFeature ore(int attempts) {
		return oreWithPlacements(
			CountPlacement.of(attempts),
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(0), VerticalAnchor.absolute(64))
		);
	}

	private static DynamicOrePlan transformedPlan(PlacedFeature feature) {
		return new DynamicOrePlanner().build(
			List.of(new BiomeInput("minecraft:plains", steps(new FeatureInput("test:ore", feature)))),
			List.of(new FeatureInput("test:ore", feature)),
			java.util.Optional.of(new VerticalFrame(-624, 383, 63))
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
