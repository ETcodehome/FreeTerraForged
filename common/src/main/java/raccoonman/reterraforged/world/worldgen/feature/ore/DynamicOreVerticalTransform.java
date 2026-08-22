package raccoonman.reterraforged.world.worldgen.feature.ore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Anchor;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightProviderShape;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightSemantics;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.FanoutStage;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalFrame;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalTransform;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.WeightedY;

final class DynamicOreVerticalTransform {
	static final int REFERENCE_MIN_Y = -64;
	static final int REFERENCE_DEEPSLATE_START_Y = 0;
	static final int REFERENCE_DEEPSLATE_END_Y = 8;
	static final int REFERENCE_SEA_LEVEL = 63;
	static final int REFERENCE_MAX_Y = 319;
	private static final int MAX_OUTPUT_Y_VALUES = 16_384;

	private DynamicOreVerticalTransform() {
	}

	static boolean isReferenceFrame(VerticalFrame frame) {
		return frame.minY() == REFERENCE_MIN_Y
			&& frame.maxY() == REFERENCE_MAX_Y
			&& frame.seaLevel() == REFERENCE_SEA_LEVEL;
	}

	static Derivation derive(
		HeightSemantics height,
		VerticalFrame frame
	) {
		return derive(height, frame, FanoutStage.HEIGHT, 0, 0);
	}

	static Derivation derive(
		HeightSemantics height,
		VerticalFrame frame,
		FanoutStage fanoutStage,
		int fanoutModifierIndex,
		int heightModifierIndex
	) {
		if (!validFrame(frame)) {
			return Derivation.unsupported("NON_MONOTONIC_LIVE_LANDMARKS");
		}
		if (height.plateau() < 0) {
			return Derivation.unsupported("NEGATIVE_TRAPEZOID_PLATEAU");
		}

		long referenceMinLong = resolve(height.minInclusive(), REFERENCE_MIN_Y, REFERENCE_MAX_Y);
		long referenceMaxLong = resolve(height.maxInclusive(), REFERENCE_MIN_Y, REFERENCE_MAX_Y);
		if (referenceMinLong < Integer.MIN_VALUE || referenceMinLong > Integer.MAX_VALUE
			|| referenceMaxLong < Integer.MIN_VALUE || referenceMaxLong > Integer.MAX_VALUE) {
			return Derivation.unsupported("REFERENCE_HEIGHT_OUTSIDE_INTEGER_RANGE");
		}
		int referenceMin = (int)referenceMinLong;
		int referenceMax = (int)referenceMaxLong;
		if (referenceMin > referenceMax) {
			return Derivation.unsupported("EMPTY_REFERENCE_HEIGHT_RANGE");
		}
		if ((long)referenceMax - referenceMin + 1L > MAX_OUTPUT_Y_VALUES) {
			return Derivation.unsupported("REFERENCE_HEIGHT_SUPPORT_TOO_LARGE");
		}
		Map<Integer, Double> referencePmf = probabilityMass(height.provider(), referenceMin, referenceMax, height.plateau());
		TreeMap<Integer, Double> outputWeights = new TreeMap<>();
		for (Map.Entry<Integer, Double> entry : referencePmf.entrySet()) {
			double low = map(entry.getKey() - 0.5, frame);
			double high = map(entry.getKey() + 0.5, frame);
			if (high < low) {
				double swap = low;
				low = high;
				high = swap;
			}
			if (!(high > low) || !Double.isFinite(low) || !Double.isFinite(high)) {
				continue;
			}
			long first = (long)Math.floor(low - 0.5) - 1L;
			long last = (long)Math.ceil(high + 0.5) + 1L;
			if (first < Integer.MIN_VALUE || last > Integer.MAX_VALUE || last - first > MAX_OUTPUT_Y_VALUES + 4L) {
				return Derivation.unsupported("MAPPED_HEIGHT_SUPPORT_TOO_LARGE");
			}
			for (long candidate = first; candidate <= last; candidate++) {
				double overlap = Math.min(high, candidate + 0.5) - Math.max(low, candidate - 0.5);
				if (overlap > 0.0) {
					outputWeights.merge((int)candidate, entry.getValue() * overlap, Double::sum);
				}
			}
		}
		if (outputWeights.isEmpty() || outputWeights.size() > MAX_OUTPUT_Y_VALUES) {
			return Derivation.unsupported("EMPTY_OR_OVERSIZED_MAPPED_HEIGHT_SUPPORT");
		}
		if (isIdentity(referencePmf, outputWeights)) {
			return Derivation.unsupported("FEATURE_VERTICAL_MAPPING_IS_IDENTITY");
		}

		double cumulative = 0.0;
		List<WeightedY> samples = new ArrayList<>(outputWeights.size());
		for (Map.Entry<Integer, Double> entry : outputWeights.entrySet()) {
			double weight = entry.getValue();
			if (!(weight > 0.0) || !Double.isFinite(weight)) {
				continue;
			}
			cumulative += weight;
			samples.add(new WeightedY(entry.getKey(), cumulative));
		}
		if (!(cumulative > 0.0) || !Double.isFinite(cumulative) || samples.isEmpty()) {
			return Derivation.unsupported("INVALID_MAPPED_INTENSITY");
		}
		return Derivation.supported(new VerticalTransform(
			cumulative,
			fanoutStage,
			fanoutModifierIndex,
			heightModifierIndex,
			samples
		));
	}

	private static boolean isIdentity(Map<Integer, Double> reference, Map<Integer, Double> output) {
		if (!reference.keySet().equals(output.keySet())) {
			return false;
		}
		for (Map.Entry<Integer, Double> entry : reference.entrySet()) {
			double actual = output.get(entry.getKey());
			if (Math.abs(actual - entry.getValue()) > Math.max(1.0, entry.getValue()) * 1.0E-12) {
				return false;
			}
		}
		return true;
	}

	private static boolean validFrame(VerticalFrame frame) {
		return frame.minY() <= REFERENCE_DEEPSLATE_START_Y
			&& REFERENCE_DEEPSLATE_START_Y <= REFERENCE_DEEPSLATE_END_Y
			&& REFERENCE_DEEPSLATE_END_Y < frame.seaLevel()
			&& frame.seaLevel() <= frame.maxY();
	}

	private static long resolve(Anchor anchor, int minY, int maxY) {
		return switch (anchor.type()) {
			case ABSOLUTE -> anchor.value();
			case ABOVE_BOTTOM -> (long)minY + anchor.value();
			case BELOW_TOP -> (long)maxY - anchor.value();
		};
	}

	private static Map<Integer, Double> probabilityMass(
		HeightProviderShape provider,
		int minY,
		int maxY,
		int plateau
	) {
		int width = Math.subtractExact(maxY, minY);
		TreeMap<Integer, Double> probabilities = new TreeMap<>();
		if (provider == HeightProviderShape.UNIFORM || plateau >= width) {
			double probability = 1.0 / (width + 1.0);
			for (int y = minY; y <= maxY; y++) {
				probabilities.put(y, probability);
			}
			return probabilities;
		}

		int left = (width - plateau) / 2;
		int right = width - left;
		double denominator = (left + 1.0) * (right + 1.0);
		for (int offset = 0; offset <= width; offset++) {
			int lowerFirst = Math.max(0, offset - left);
			int upperFirst = Math.min(right, offset);
			int combinations = Math.max(0, upperFirst - lowerFirst + 1);
			probabilities.put(minY + offset, combinations / denominator);
		}
		return probabilities;
	}

	private static double map(double referenceY, VerticalFrame frame) {
		// Inclusive integer bands meet at half-block boundaries; seaLevel is the first non-fluid block.
		double[] reference = {
			REFERENCE_MIN_Y - 0.5,
			REFERENCE_DEEPSLATE_START_Y - 0.5,
			REFERENCE_DEEPSLATE_END_Y + 0.5,
			REFERENCE_SEA_LEVEL - 0.5,
			REFERENCE_MAX_Y + 0.5
		};
		double[] live = {
			frame.minY() - 0.5,
			REFERENCE_DEEPSLATE_START_Y - 0.5,
			REFERENCE_DEEPSLATE_END_Y + 0.5,
			frame.seaLevel() - 0.5,
			frame.maxY() + 0.5
		};
		int segment = reference.length - 2;
		if (referenceY < reference[reference.length - 1]) {
			segment = 0;
			while (segment < reference.length - 2 && referenceY >= reference[segment + 1]) {
				segment++;
			}
		}
		double referenceSpan = reference[segment + 1] - reference[segment];
		double liveSpan = live[segment + 1] - live[segment];
		return live[segment] + (referenceY - reference[segment]) * liveSpan / referenceSpan;
	}

	record Derivation(Optional<VerticalTransform> transform, String reasonCode) {
		private static Derivation supported(VerticalTransform transform) {
			return new Derivation(Optional.of(transform), "DYNAMIC_VERTICAL_DENSITY");
		}

		private static Derivation unsupported(String reasonCode) {
			return new Derivation(Optional.empty(), reasonCode);
		}
	}
}
