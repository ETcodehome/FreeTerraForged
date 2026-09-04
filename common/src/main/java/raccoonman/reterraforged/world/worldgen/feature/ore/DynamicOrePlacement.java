package raccoonman.reterraforged.world.worldgen.feature.ore;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlan;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlans;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.FanoutStage;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalFrame;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalTransform;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.WeightedY;

public final class DynamicOrePlacement {
	private static final double INTEGER_TOLERANCE = 1.0E-12;

	private DynamicOrePlacement() {
	}

	public static Optional<Stream<BlockPos>> getHeightPositions(
		HeightRangePlacement placement,
		PlacementContext context,
		RandomSource random,
		BlockPos origin
	) {
		Optional<Activation> activation = activation(placement, context);
		if (activation.isEmpty()
			|| activation.orElseThrow().modifierIndex() != activation.orElseThrow().transform().heightModifierIndex()) {
			return Optional.empty();
		}
		VerticalTransform transform = activation.orElseThrow().transform();
		int outputCount = transform.fanoutStage() == FanoutStage.HEIGHT
			? stochasticRound(transform.expectedOutputsPerInput(), random)
			: 1;
		if (outputCount == 0) {
			return Optional.of(Stream.empty());
		}
		if (outputCount == 1) {
			return Optional.of(Stream.of(origin.atY(sampleY(transform, random))));
		}
		return Optional.of(IntStream.range(0, outputCount).mapToObj(ignored -> origin.atY(sampleY(transform, random))));
	}

	public static Optional<Stream<BlockPos>> getFanoutPositions(
		PlacementModifier modifier,
		PlacementContext context,
		RandomSource random,
		Stream<BlockPos> authoredPositions,
		FanoutStage expectedStage
	) {
		Optional<Activation> activation = activation(modifier, context);
		if (activation.isEmpty()) {
			return Optional.empty();
		}
		VerticalTransform transform = activation.orElseThrow().transform();
		if (transform.fanoutStage() != expectedStage
			|| transform.fanoutModifierIndex() != activation.orElseThrow().modifierIndex()) {
			return Optional.empty();
		}
		return Optional.of(authoredPositions.flatMap(position -> IntStream
			.range(0, stochasticRound(transform.expectedOutputsPerInput(), random))
			.mapToObj(ignored -> position)));
	}

	public static Optional<Stream<BlockPos>> getInSquarePositions(
		PlacementModifier modifier,
		PlacementContext context,
		RandomSource random,
		BlockPos origin
	) {
		Optional<Activation> activation = activation(modifier, context);
		if (activation.isEmpty()) {
			return Optional.empty();
		}
		VerticalTransform transform = activation.orElseThrow().transform();
		if (transform.fanoutStage() != FanoutStage.IN_SQUARE
			|| transform.fanoutModifierIndex() != activation.orElseThrow().modifierIndex()) {
			return Optional.empty();
		}
		int outputCount = stochasticRound(transform.expectedOutputsPerInput(), random);
		return Optional.of(IntStream.range(0, outputCount).mapToObj(ignored -> new BlockPos(
			random.nextInt(16) + origin.getX(),
			origin.getY(),
			random.nextInt(16) + origin.getZ()
		)));
	}

	public static boolean isStandardOrePlacement(HeightRangePlacement placement, PlacementContext context) {
		if (!(context.generator() instanceof TerraForgedChunkGenerator)) {
			return false;
		}
		return context.topFeature()
			.filter(feature -> feature.placement().stream().anyMatch(modifier -> modifier == placement))
			.filter(DynamicOrePlacement::isStandardOre)
			.isPresent();
	}

	private static boolean isStandardOre(PlacedFeature feature) {
		Feature<?> configuredType = feature.feature().value().feature();
		return configuredType == Feature.ORE || configuredType == Feature.SCATTERED_ORE;
	}

	private static Optional<Activation> activation(PlacementModifier modifier, PlacementContext context) {
		if (!(context.generator() instanceof TerraForgedChunkGenerator generator)) {
			return Optional.empty();
		}
		Optional<PlacedFeature> topFeature = context.topFeature().filter(DynamicOrePlacement::isStandardOre);
		if (topFeature.isEmpty()) {
			return Optional.empty();
		}
		int modifierIndex = identityIndex(topFeature.orElseThrow().placement(), modifier);
		if (modifierIndex < 0) {
			return Optional.empty();
		}
		WorldgenPlans.PlacedFeatures placedFeatures = generator.plan()
			.map(WorldgenPlan::placedFeatures)
			.orElse(null);
		if (placedFeatures == null) {
			return Optional.empty();
		}
		DynamicOrePlan plan = placedFeatures.ores();
		VerticalFrame currentFrame = new VerticalFrame(
			context.getMinGenY(),
			context.getMinGenY() + context.getGenDepth() - 1,
			context.generator().getSeaLevel()
		);
		if (plan.verticalFrame().isEmpty() || !plan.verticalFrame().orElseThrow().equals(currentFrame)) {
			return Optional.empty();
		}
		return placedFeatures.oreTransform(topFeature.orElseThrow())
			.map(transform -> new Activation(transform, modifierIndex));
	}

	private static int identityIndex(List<PlacementModifier> modifiers, PlacementModifier target) {
		for (int index = 0; index < modifiers.size(); index++) {
			if (modifiers.get(index) == target) {
				return index;
			}
		}
		return -1;
	}

	static int stochasticRound(double expectation, RandomSource random) {
		double nearest = Math.rint(expectation);
		if (Math.abs(expectation - nearest) <= INTEGER_TOLERANCE) {
			return Math.toIntExact((long)nearest);
		}
		int guaranteed = Math.toIntExact((long)Math.floor(expectation));
		return guaranteed + (random.nextDouble() < expectation - guaranteed ? 1 : 0);
	}

	static int sampleY(VerticalTransform transform, RandomSource random) {
		List<WeightedY> values = transform.cumulativeIntensity();
		double target = random.nextDouble() * transform.expectedOutputsPerInput();
		int low = 0;
		int high = values.size() - 1;
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (target < values.get(middle).cumulativeIntensity()) {
				high = middle;
			} else {
				low = middle + 1;
			}
		}
		return values.get(low).y();
	}

	private record Activation(VerticalTransform transform, int modifierIndex) {
	}
}
