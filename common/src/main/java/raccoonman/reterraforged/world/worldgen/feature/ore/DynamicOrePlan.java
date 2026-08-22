package raccoonman.reterraforged.world.worldgen.feature.ore;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public record DynamicOrePlan(
	Optional<VerticalFrame> verticalFrame,
	Map<String, VerticalTransform> verticalTransforms,
	int activeFeatures,
	int standardOres,
	int delegatedFeatures,
	Map<String, Integer> skippedReasons,
	List<String> failures
) {
	public DynamicOrePlan {
		verticalFrame = verticalFrame == null ? Optional.empty() : verticalFrame;
		verticalTransforms = Collections.unmodifiableMap(new TreeMap<>(verticalTransforms));
		skippedReasons = Collections.unmodifiableMap(new TreeMap<>(skippedReasons));
		failures = List.copyOf(failures);
	}

	public static DynamicOrePlan empty() {
		return new DynamicOrePlan(Optional.empty(), Map.of(), 0, 0, 0, Map.of(), List.of());
	}

	public String summary() {
		return "active_features=" + this.activeFeatures
			+ ", standard_ores=" + this.standardOres
			+ ", dynamic_transforms=" + this.verticalTransforms.size()
			+ ", delegated=" + this.delegatedFeatures
			+ ", skipped=" + this.skippedReasons
			+ ", failures=" + this.failures.size()
			+ ", frame=" + this.verticalFrame.map(Object::toString).orElse("none");
	}

	public enum HeightProviderShape {
		UNIFORM,
		TRAPEZOID
	}

	public enum AnchorType {
		ABSOLUTE,
		ABOVE_BOTTOM,
		BELOW_TOP
	}

	public enum FanoutStage {
		COUNT,
		RARITY,
		IN_SQUARE,
		HEIGHT
	}

	public record Anchor(AnchorType type, int value) {
	}

	public record HeightSemantics(
		HeightProviderShape provider,
		Anchor minInclusive,
		Anchor maxInclusive,
		int plateau
	) {
	}

	public record VerticalFrame(int minY, int maxY, int seaLevel) {
		public VerticalFrame {
			if (minY > maxY) {
				throw new IllegalArgumentException("Minimum generation Y exceeds maximum generation Y");
			}
		}
	}

	public record VerticalTransform(
		double expectedOutputsPerInput,
		FanoutStage fanoutStage,
		int fanoutModifierIndex,
		int heightModifierIndex,
		List<WeightedY> cumulativeIntensity
	) {
		public VerticalTransform {
			cumulativeIntensity = List.copyOf(cumulativeIntensity);
			if (!(expectedOutputsPerInput > 0.0) || !Double.isFinite(expectedOutputsPerInput)) {
				throw new IllegalArgumentException("Expected outputs must be finite and positive");
			}
			if (cumulativeIntensity.isEmpty()) {
				throw new IllegalArgumentException("A dynamic vertical transform requires at least one Y value");
			}
			if (fanoutModifierIndex < 0 || heightModifierIndex < 0) {
				throw new IllegalArgumentException("Placement modifier indices must be non-negative");
			}
			double previous = 0.0;
			for (WeightedY value : cumulativeIntensity) {
				if (!(value.cumulativeIntensity() > previous) || !Double.isFinite(value.cumulativeIntensity())) {
					throw new IllegalArgumentException("Cumulative intensity must be finite and strictly increasing");
				}
				previous = value.cumulativeIntensity();
			}
			if (Math.abs(previous - expectedOutputsPerInput) > Math.max(1.0, expectedOutputsPerInput) * 1.0E-12) {
				throw new IllegalArgumentException("Final cumulative intensity must equal the output expectation");
			}
		}
	}

	public record WeightedY(int y, double cumulativeIntensity) {
	}
}
