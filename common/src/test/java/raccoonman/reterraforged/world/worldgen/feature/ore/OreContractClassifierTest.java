package raccoonman.reterraforged.world.worldgen.feature.ore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Action;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Anchor;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.AnchorType;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Contract;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightProviderShape;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.InspectionStatus;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Membership;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Occurrence;

class OreContractClassifierTest {
	private static final Membership MEMBERSHIP = new Membership("minecraft:plains", "underground_ores", 6, 3);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void classifiesUniformOreAndPreservesMixedAnchorSemantics() {
		PlacedFeature feature = ore(
			CountPlacement.of(7),
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(12), VerticalAnchor.absolute(48)),
			BiomeFilter.biome()
		);

		Occurrence result = new OreContractClassifier().classify("minecraft:test", MEMBERSHIP, feature);

		assertEquals(Contract.SUPPORTED_STANDARD, result.contract());
		assertEquals(InspectionStatus.CLASSIFIED, result.inspection().status());
		assertEquals(Action.REPORT_ONLY, result.action());
		assertEquals(
			Optional.of(new DynamicOrePlan.HeightSemantics(
				HeightProviderShape.UNIFORM,
				new Anchor(AnchorType.ABOVE_BOTTOM, 12),
				new Anchor(AnchorType.ABSOLUTE, 48),
				0
			)),
			result.height()
		);
		assertTrue(result.oreConfiguration().orElseThrow().contains("\"size\":9"));
		assertTrue(result.oreConfiguration().orElseThrow().contains("minecraft:stone"));
		assertTrue(result.oreConfiguration().orElseThrow().contains("minecraft:iron_ore"));
	}

	@Test
	void classifiesTriangularAndTrapezoidProvidersWithoutPrivateAccess() {
		PlacedFeature triangle = ore(HeightRangePlacement.triangle(VerticalAnchor.absolute(-32), VerticalAnchor.belowTop(8)));
		PlacedFeature trapezoid = ore(HeightRangePlacement.of(
			TrapezoidHeight.of(VerticalAnchor.absolute(-16), VerticalAnchor.absolute(80), 12)
		));

		Occurrence triangleResult = new OreContractClassifier().classify("test:triangle", MEMBERSHIP, triangle);
		Occurrence trapezoidResult = new OreContractClassifier().classify("test:trapezoid", MEMBERSHIP, trapezoid);

		assertEquals(HeightProviderShape.TRAPEZOID, triangleResult.height().orElseThrow().provider());
		assertEquals(AnchorType.BELOW_TOP, triangleResult.height().orElseThrow().maxInclusive().type());
		assertEquals(0, triangleResult.height().orElseThrow().plateau());
		assertEquals(12, trapezoidResult.height().orElseThrow().plateau());
	}

	@Test
	void acceptsActualCustomPlacementFilterWithoutLinkingItsConcreteClass() {
		PlacedFeature feature = ore(
			CountPlacement.of(4),
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32)),
			TestPlacementFilter.INSTANCE
		);

		Occurrence result = new OreContractClassifier().classify("example:filtered_ore", MEMBERSHIP, feature);

		assertEquals(Contract.STANDARD_WITH_CUSTOM_FILTER, result.contract());
		assertEquals("SUPPORTED_CUSTOM_PLACEMENT_FILTER", result.reasonCode());
		assertEquals(Action.REPORT_ONLY, result.action());
		assertTrue(result.placementModifierTypes().getLast().contains("TestPlacementFilter"));
	}

	@Test
	void preservesAFilterThatWouldRunBeforeTheSafeFanoutBoundary() {
		PlacedFeature feature = ore(
			TestPlacementFilter.INSTANCE,
			CountPlacement.of(4),
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32))
		);

		Occurrence result = new OreContractClassifier().classify("example:upstream_filter", MEMBERSHIP, feature);

		assertEquals(Contract.PRESERVE_UNKNOWN, result.contract());
		assertEquals("UPSTREAM_FILTER_BEFORE_SAFE_FANOUT", result.reasonCode());
		assertEquals(Action.PRESERVE_UNCHANGED, result.action());
	}

	@Test
	void usesRegistryAwareCodecOpsForRegistryBackedModifierConfiguration() {
		PlacedFeature feature = ore(
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32)),
			BlockPredicateFilter.forPredicate(BlockPredicate.matchesBlocks(Blocks.STONE))
		);
		OreContractClassifier classifier = new OreContractClassifier(
			RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
		);

		Occurrence result = classifier.classify("example:registry_codec", MEMBERSHIP, feature);

		assertEquals(Contract.SUPPORTED_STANDARD, result.contract());
		assertEquals(InspectionStatus.CLASSIFIED, result.inspection().status());
		assertTrue(result.placementModifierConfigurations().getLast().contains("minecraft:stone"));
	}

	@Test
	void rejectsAFilterNamedPositionTransformerThatIsNotAPlacementFilter() {
		PlacedFeature feature = ore(
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32)),
			FilterNamedPositionTransformer.INSTANCE
		);

		Occurrence result = new OreContractClassifier().classify("example:not_a_filter", MEMBERSHIP, feature);

		assertEquals(Contract.PRESERVE_UNKNOWN, result.contract());
		assertEquals("UNSUPPORTED_POSITION_MODIFIER", result.reasonCode());
		assertEquals(Action.PRESERVE_UNCHANGED, result.action());
	}

	@Test
	void preservesCustomConfiguredFeaturesAndUnsupportedHeightForms() {
		PlacedFeature custom = new PlacedFeature(
			Holder.direct(new ConfiguredFeature<>(Feature.NO_OP, NoneFeatureConfiguration.INSTANCE)),
			List.of(HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.top()))
		);
		PlacedFeature constant = ore(HeightRangePlacement.of(ConstantHeight.of(VerticalAnchor.absolute(12))));

		Occurrence customResult = new OreContractClassifier().classify("example:custom", MEMBERSHIP, custom);
		Occurrence constantResult = new OreContractClassifier().classify("example:constant", MEMBERSHIP, constant);

		assertEquals(Contract.CUSTOM_DIAGNOSTIC, customResult.contract());
		assertEquals("CUSTOM_CONFIGURED_FEATURE", customResult.reasonCode());
		assertEquals(Action.PRESERVE_UNCHANGED, customResult.action());
		assertEquals(Contract.PRESERVE_UNKNOWN, constantResult.contract());
		assertEquals("UNSUPPORTED_HEIGHT_PROVIDER:minecraft:constant", constantResult.reasonCode());
		assertEquals(InspectionStatus.UNSUPPORTED, constantResult.inspection().status());
		assertEquals(Action.PRESERVE_UNCHANGED, constantResult.action());
	}

	@Test
	void requiresExactlyOneHeightRange() {
		PlacedFeature missing = ore(CountPlacement.of(1));
		PlacedFeature repeated = ore(
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32)),
			HeightRangePlacement.uniform(VerticalAnchor.absolute(0), VerticalAnchor.top())
		);

		assertEquals(
			"MISSING_HEIGHT_RANGE",
			new OreContractClassifier().classify("test:missing", MEMBERSHIP, missing).reasonCode()
		);
		assertEquals(
			"MULTIPLE_HEIGHT_RANGES",
			new OreContractClassifier().classify("test:multiple", MEMBERSHIP, repeated).reasonCode()
		);
	}

	@Test
	void attributesRuntimeAndLinkageFailuresToOnlyTheInspectedOccurrence() {
		PlacedFeature feature = ore(HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.top()));
		OreContractClassifier runtimeFailure = new OreContractClassifier(height -> {
			throw new IllegalStateException("bad codec shape");
		});
		OreContractClassifier linkageFailure = new OreContractClassifier(height -> {
			throw new NoClassDefFoundError("missing optional type");
		});

		Occurrence runtime = runtimeFailure.classify("test:runtime", MEMBERSHIP, feature);
		Occurrence linkage = linkageFailure.classify("test:linkage", MEMBERSHIP, feature);
		Occurrence independent = new OreContractClassifier().classify("test:independent", MEMBERSHIP, feature);

		assertEquals(InspectionStatus.FAILED, runtime.inspection().status());
		assertEquals("java.lang.IllegalStateException", runtime.inspection().failureType().orElseThrow());
		assertEquals(InspectionStatus.FAILED, linkage.inspection().status());
		assertEquals("java.lang.NoClassDefFoundError", linkage.inspection().failureType().orElseThrow());
		assertEquals(Contract.SUPPORTED_STANDARD, independent.contract());
	}

	@Test
	void reportingDoesNotMutateTheConfiguredFeatureOrPlacementChain() {
		ConfiguredFeature<?, ?> configured = new ConfiguredFeature<>(Feature.SCATTERED_ORE, configuration());
		List<PlacementModifier> modifiers = List.of(
			RarityFilter.onAverageOnceEvery(3),
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.absolute(-20), VerticalAnchor.belowTop(4)),
			BiomeFilter.biome()
		);
		PlacedFeature feature = new PlacedFeature(Holder.direct(configured), modifiers);

		Occurrence result = new OreContractClassifier().classify("test:identity", MEMBERSHIP, feature);

		assertEquals(Contract.SUPPORTED_STANDARD, result.contract());
		assertSame(configured, feature.feature().value());
		assertSame(modifiers, feature.placement());
		assertSame(configured.config(), feature.feature().value().config());
		assertFalse(result.oreConfiguration().isEmpty());
	}

	private static PlacedFeature ore(PlacementModifier... modifiers) {
		return new PlacedFeature(
			Holder.direct(new ConfiguredFeature<>(Feature.ORE, configuration())),
			List.of(modifiers)
		);
	}

	private static OreConfiguration configuration() {
		return new OreConfiguration(new BlockMatchTest(Blocks.STONE), Blocks.IRON_ORE.defaultBlockState(), 9, 0.35F);
	}

	private static final class TestPlacementFilter extends PlacementFilter {
		private static final TestPlacementFilter INSTANCE = new TestPlacementFilter();
		private static final PlacementModifierType<TestPlacementFilter> TYPE = () -> MapCodec.unit(() -> INSTANCE);

		@Override
		protected boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos position) {
			return true;
		}

		@Override
		public PlacementModifierType<?> type() {
			return TYPE;
		}
	}

	private static final class FilterNamedPositionTransformer extends PlacementModifier {
		private static final FilterNamedPositionTransformer INSTANCE = new FilterNamedPositionTransformer();
		private static final PlacementModifierType<FilterNamedPositionTransformer> TYPE = () -> MapCodec.unit(() -> INSTANCE);

		@Override
		public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos position) {
			return Stream.of(position.above());
		}

		@Override
		public PlacementModifierType<?> type() {
			return TYPE;
		}
	}
}
