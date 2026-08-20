package raccoonman.reterraforged.world.worldgen.feature.ore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.MapCodec;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
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
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Anchor;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.AnchorType;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightProviderShape;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightSemantics;
import raccoonman.reterraforged.world.worldgen.feature.ore.OreContractClassifier.Status;

class OreContractClassifierTest {
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

		OreContractClassifier.Result result = new OreContractClassifier().classify(feature);

		assertEquals(Status.SUPPORTED, result.status());
		assertEquals(
			new HeightSemantics(
				HeightProviderShape.UNIFORM,
				new Anchor(AnchorType.ABOVE_BOTTOM, 12),
				new Anchor(AnchorType.ABSOLUTE, 48),
				0
			),
			result.contract().orElseThrow().height()
		);
	}

	@Test
	void classifiesTriangularAndTrapezoidProviders() {
		PlacedFeature triangle = ore(HeightRangePlacement.triangle(
			VerticalAnchor.absolute(-32), VerticalAnchor.belowTop(8)
		));
		PlacedFeature trapezoid = ore(HeightRangePlacement.of(
			TrapezoidHeight.of(VerticalAnchor.absolute(-16), VerticalAnchor.absolute(80), 12)
		));

		var triangleHeight = new OreContractClassifier().classify(triangle).contract().orElseThrow().height();
		var trapezoidHeight = new OreContractClassifier().classify(trapezoid).contract().orElseThrow().height();

		assertEquals(HeightProviderShape.TRAPEZOID, triangleHeight.provider());
		assertEquals(AnchorType.BELOW_TOP, triangleHeight.maxInclusive().type());
		assertEquals(0, triangleHeight.plateau());
		assertEquals(12, trapezoidHeight.plateau());
	}

	@Test
	void classifiesCustomModifiersByTheirActualPlacementContract() {
		PlacedFeature downstreamFilter = ore(
			CountPlacement.of(4),
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32)),
			TestPlacementFilter.INSTANCE
		);
		PlacedFeature upstreamFilter = ore(
			TestPlacementFilter.INSTANCE,
			CountPlacement.of(4),
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32))
		);
		PlacedFeature transformer = ore(
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32)),
			FilterNamedPositionTransformer.INSTANCE
		);

		assertEquals(Status.SUPPORTED, new OreContractClassifier().classify(downstreamFilter).status());
		assertEquals(
			"UPSTREAM_FILTER_BEFORE_SAFE_FANOUT",
			new OreContractClassifier().classify(upstreamFilter).reasonCode()
		);
		assertEquals(
			"UNSUPPORTED_POSITION_MODIFIER",
			new OreContractClassifier().classify(transformer).reasonCode()
		);
	}

	@Test
	void acceptsRegistryBackedBuiltInFilters() {
		PlacedFeature feature = ore(
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32)),
			BlockPredicateFilter.forPredicate(BlockPredicate.matchesBlocks(Blocks.STONE))
		);
		OreContractClassifier classifier = new OreContractClassifier(
			RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
		);

		assertEquals(Status.SUPPORTED, classifier.classify(feature).status());
	}

	@Test
	void preservesCustomFeaturesAndUnsupportedHeightForms() {
		PlacedFeature custom = new PlacedFeature(
			Holder.direct(new ConfiguredFeature<>(Feature.NO_OP, NoneFeatureConfiguration.INSTANCE)),
			List.of(HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.top()))
		);
		PlacedFeature constant = ore(HeightRangePlacement.of(ConstantHeight.of(VerticalAnchor.absolute(12))));

		assertEquals(Status.NOT_ORE, new OreContractClassifier().classify(custom).status());
		assertEquals(Status.SKIPPED, new OreContractClassifier().classify(constant).status());
		assertEquals(
			"UNSUPPORTED_HEIGHT_PROVIDER:minecraft:constant",
			new OreContractClassifier().classify(constant).reasonCode()
		);
	}

	@Test
	void requiresExactlyOneHeightRange() {
		PlacedFeature missing = ore(CountPlacement.of(1));
		PlacedFeature repeated = ore(
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(32)),
			HeightRangePlacement.uniform(VerticalAnchor.absolute(0), VerticalAnchor.top())
		);

		assertEquals("MISSING_HEIGHT_RANGE", new OreContractClassifier().classify(missing).reasonCode());
		assertEquals("MULTIPLE_HEIGHT_RANGES", new OreContractClassifier().classify(repeated).reasonCode());
	}

	@Test
	void classifyingScatteredOreDoesNotMutateItsPlacementChain() {
		ConfiguredFeature<?, ?> configured = new ConfiguredFeature<>(Feature.SCATTERED_ORE, configuration());
		List<PlacementModifier> modifiers = List.of(
			RarityFilter.onAverageOnceEvery(3),
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.absolute(-20), VerticalAnchor.belowTop(4)),
			BiomeFilter.biome()
		);
		PlacedFeature feature = new PlacedFeature(Holder.direct(configured), modifiers);

		assertEquals(Status.SUPPORTED, new OreContractClassifier().classify(feature).status());
		assertSame(configured, feature.feature().value());
		assertSame(modifiers, feature.placement());
		assertSame(configured.config(), feature.feature().value().config());
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
