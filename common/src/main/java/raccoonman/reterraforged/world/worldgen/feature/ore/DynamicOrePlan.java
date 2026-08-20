package raccoonman.reterraforged.world.worldgen.feature.ore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, registry-epoch-local description of ore contracts in the final
 * Overworld biome feature graph. This model deliberately stores no registry
 * holders or world-generation objects.
 */
public record DynamicOrePlan(
	String schemaFingerprint,
	String graphFingerprint,
	List<Occurrence> occurrences,
	Optional<VerticalFrame> verticalFrame,
	Map<String, VerticalTransform> verticalTransforms
) {
	public DynamicOrePlan {
		occurrences = List.copyOf(occurrences);
		verticalFrame = verticalFrame == null ? Optional.empty() : verticalFrame;
		verticalTransforms = Map.copyOf(verticalTransforms);
	}

	public DynamicOrePlan(String schemaFingerprint, String graphFingerprint, List<Occurrence> occurrences) {
		this(schemaFingerprint, graphFingerprint, occurrences, Optional.empty(), Map.of());
	}

	public static DynamicOrePlan empty(String schemaFingerprint) {
		return new DynamicOrePlan(schemaFingerprint, DynamicOrePlanner.fingerprint(List.of()), List.of());
	}

	public long count(Contract contract) {
		return this.occurrences.stream().filter(occurrence -> occurrence.contract() == contract).count();
	}

	public long count(InspectionStatus status) {
		return this.occurrences.stream().filter(occurrence -> occurrence.inspection().status() == status).count();
	}

	public String summary() {
		return "schema=" + this.schemaFingerprint
			+ ", graph=" + this.graphFingerprint
			+ ", occurrences=" + this.occurrences.size()
			+ ", standard=" + this.count(Contract.SUPPORTED_STANDARD)
			+ ", custom_filters=" + this.count(Contract.STANDARD_WITH_CUSTOM_FILTER)
			+ ", custom=" + this.count(Contract.CUSTOM_DIAGNOSTIC)
			+ ", preserved=" + this.count(Contract.PRESERVE_UNKNOWN)
			+ ", inactive=" + this.count(Contract.NO_ACTIVE_MEMBERSHIP)
			+ ", inspection_failed=" + this.count(InspectionStatus.FAILED)
			+ ", inspection_unsupported=" + this.count(InspectionStatus.UNSUPPORTED)
			+ ", dynamic_transforms=" + this.verticalTransforms.size()
			+ ", frame=" + this.verticalFrame.map(Object::toString).orElse("none");
	}

	public enum Contract {
		SUPPORTED_STANDARD,
		STANDARD_WITH_CUSTOM_FILTER,
		CUSTOM_DIAGNOSTIC,
		PRESERVE_UNKNOWN,
		NO_ACTIVE_MEMBERSHIP
	}

	public enum InspectionStatus {
		CLASSIFIED,
		UNSUPPORTED,
		FAILED
	}

	public enum Action {
		REPORT_ONLY,
		DYNAMIC_VERTICAL_DENSITY,
		DELEGATE_REFERENCE_IDENTITY,
		PRESERVE_UNCHANGED
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

	public record Membership(String biomeId, String step, int stepIndex, int order) {
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

	/**
	 * Value-only cumulative intensity table for one registered placed feature.
	 * The final cumulative value is the expected number of output positions per
	 * input position at the authored height modifier.
	 */
	public record VerticalTransform(
		String placedFeatureId,
		String contractFingerprint,
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

	public record Inspection(
		InspectionStatus status,
		String phase,
		Optional<String> failureType,
		Optional<String> failureMessage
	) {
		public Inspection {
			failureType = failureType == null ? Optional.empty() : failureType;
			failureMessage = failureMessage == null ? Optional.empty() : failureMessage;
		}

		public static Inspection classified() {
			return new Inspection(InspectionStatus.CLASSIFIED, "complete", Optional.empty(), Optional.empty());
		}

		public static Inspection unsupported(String phase) {
			return new Inspection(InspectionStatus.UNSUPPORTED, phase, Optional.empty(), Optional.empty());
		}

		public static Inspection failed(String phase, Throwable failure) {
			return new Inspection(
				InspectionStatus.FAILED,
				phase,
				Optional.of(failure.getClass().getName()),
				Optional.ofNullable(failure.getMessage())
			);
		}
	}

	public record Occurrence(
		String placedFeatureId,
		Optional<Membership> membership,
		String configuredFeatureType,
		List<String> placementModifierTypes,
		List<String> placementModifierConfigurations,
		Optional<String> oreConfiguration,
		Optional<HeightSemantics> height,
		Contract contract,
		Inspection inspection,
		Action action,
		String reasonCode,
		String contractFingerprint
	) {
		public Occurrence {
			membership = membership == null ? Optional.empty() : membership;
			placementModifierTypes = List.copyOf(placementModifierTypes);
			placementModifierConfigurations = List.copyOf(placementModifierConfigurations);
			oreConfiguration = oreConfiguration == null ? Optional.empty() : oreConfiguration;
			height = height == null ? Optional.empty() : height;
		}

		public Occurrence withAction(Action nextAction, String nextReasonCode) {
			return new Occurrence(
				this.placedFeatureId,
				this.membership,
				this.configuredFeatureType,
				this.placementModifierTypes,
				this.placementModifierConfigurations,
				this.oreConfiguration,
				this.height,
				this.contract,
				this.inspection,
				nextAction,
				nextReasonCode,
				this.contractFingerprint
			);
		}
	}
}
