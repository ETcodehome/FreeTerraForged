package raccoonman.reterraforged.world.worldgen.feature.ore;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.FanoutStage;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightSemantics;

public final class OreContractClassifier {
	private final OreHeightInspector heightInspector;

	OreContractClassifier() {
		this.heightInspector = new OreHeightInspector(JsonOps.INSTANCE);
	}

	public OreContractClassifier(HolderLookup.Provider registries) {
		this.heightInspector = new OreHeightInspector(
			net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, registries)
		);
	}

	public Result classify(PlacedFeature placedFeature) {
		String phase = "configured_feature";
		try {
			var configured = placedFeature.feature().value();
			if (configured.feature() != Feature.ORE && configured.feature() != Feature.SCATTERED_ORE) {
				return Result.notOre();
			}
			if (!(configured.config() instanceof OreConfiguration)) {
				return Result.skipped("INVALID_ORE_CONFIGURATION_SHAPE");
			}

			phase = "placement_contract";
			List<PlacementModifier> modifiers = placedFeature.placement();
			HeightRangePlacement heightPlacement = null;
			for (PlacementModifier modifier : modifiers) {
				if (modifier instanceof HeightRangePlacement height) {
					if (heightPlacement != null) {
						return Result.skipped("MULTIPLE_HEIGHT_RANGES");
					}
					heightPlacement = height;
				} else if (!(modifier instanceof CountPlacement)
					&& !(modifier instanceof RarityFilter)
					&& !(modifier instanceof InSquarePlacement)
					&& !(modifier instanceof PlacementFilter)) {
					return Result.skipped("UNSUPPORTED_POSITION_MODIFIER");
				}
			}
			if (heightPlacement == null) {
				return Result.skipped("MISSING_HEIGHT_RANGE");
			}

			Fanout fanout = selectFanout(modifiers);
			for (int index = 0; index < fanout.modifierIndex(); index++) {
				if (modifiers.get(index) instanceof PlacementFilter) {
					return Result.skipped("UPSTREAM_FILTER_BEFORE_SAFE_FANOUT");
				}
			}

			phase = "height_provider";
			HeightSemantics height = this.heightInspector.inspect(heightPlacement);
			return Result.supported(new Contract(
				height,
				fanout.stage(),
				fanout.modifierIndex(),
				fanout.heightModifierIndex()
			));
		} catch (OreHeightInspector.UnsupportedHeightProvider unsupported) {
			return Result.skipped("UNSUPPORTED_HEIGHT_PROVIDER:" + unsupported.provider());
		} catch (RuntimeException | LinkageError failure) {
			String failurePhase = failure instanceof OreHeightInspector.InspectionFailure inspectionFailure
				? inspectionFailure.phase()
				: phase;
			return Result.failed(
				"INSPECTION_FAILED",
				failurePhase + " | " + failure.getClass().getName() + " | "
					+ Optional.ofNullable(failure.getMessage()).orElse("<no message>")
			);
		}
	}

	private static Fanout selectFanout(List<PlacementModifier> modifiers) {
		int heightIndex = -1;
		int inSquareIndex = -1;
		for (int index = 0; index < modifiers.size(); index++) {
			PlacementModifier modifier = modifiers.get(index);
			if (modifier instanceof HeightRangePlacement && heightIndex < 0) {
				heightIndex = index;
			}
			if (modifier instanceof InSquarePlacement && inSquareIndex < 0) {
				inSquareIndex = index;
			}
		}
		int firstSpatialIndex = inSquareIndex < 0 ? heightIndex : Math.min(heightIndex, inSquareIndex);
		for (int index = firstSpatialIndex - 1; index >= 0; index--) {
			PlacementModifier modifier = modifiers.get(index);
			if (modifier instanceof CountPlacement) {
				return new Fanout(FanoutStage.COUNT, index, heightIndex);
			}
			if (modifier instanceof RarityFilter) {
				return new Fanout(FanoutStage.RARITY, index, heightIndex);
			}
		}
		return inSquareIndex >= 0 && inSquareIndex < heightIndex
			? new Fanout(FanoutStage.IN_SQUARE, inSquareIndex, heightIndex)
			: new Fanout(FanoutStage.HEIGHT, heightIndex, heightIndex);
	}

	public enum Status {
		NOT_ORE,
		SUPPORTED,
		SKIPPED,
		FAILED
	}

	public record Result(Status status, Optional<Contract> contract, String reasonCode, Optional<String> failure) {
		private static Result notOre() {
			return new Result(Status.NOT_ORE, Optional.empty(), "NOT_STANDARD_ORE", Optional.empty());
		}

		private static Result supported(Contract contract) {
			return new Result(Status.SUPPORTED, Optional.of(contract), "SUPPORTED_STANDARD_ORE", Optional.empty());
		}

		private static Result skipped(String reasonCode) {
			return new Result(Status.SKIPPED, Optional.empty(), reasonCode, Optional.empty());
		}

		private static Result failed(String reasonCode, String failure) {
			return new Result(Status.FAILED, Optional.empty(), reasonCode, Optional.of(failure));
		}
	}

	public record Contract(
		HeightSemantics height,
		FanoutStage fanoutStage,
		int fanoutModifierIndex,
		int heightModifierIndex
	) {
	}

	private record Fanout(FanoutStage stage, int modifierIndex, int heightModifierIndex) {
	}
}
