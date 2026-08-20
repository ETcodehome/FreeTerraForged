package raccoonman.reterraforged.world.worldgen.feature.ore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Action;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Contract;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightSemantics;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Inspection;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Membership;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Occurrence;

/** Contract-based classifier with no linkage to optional mod classes. */
public final class OreContractClassifier {
	private final HeightInspection heightInspection;
	private final DynamicOps<JsonElement> ops;

	OreContractClassifier() {
		this(JsonOps.INSTANCE);
	}

	public OreContractClassifier(HolderLookup.Provider registries) {
		this(net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, registries));
	}

	private OreContractClassifier(DynamicOps<JsonElement> ops) {
		this(new OreHeightInspector(ops)::inspect, ops);
	}

	OreContractClassifier(HeightInspection heightInspection) {
		this(heightInspection, JsonOps.INSTANCE);
	}

	private OreContractClassifier(HeightInspection heightInspection, DynamicOps<JsonElement> ops) {
		this.heightInspection = heightInspection;
		this.ops = ops;
	}

	public Occurrence classify(String placedFeatureId, Membership membership, PlacedFeature placedFeature) {
		String phase = "feature_contract";
		String configuredFeatureType = "<inspection-failed>";
		List<String> modifierTypes = new ArrayList<>();
		List<String> modifierConfigurations = new ArrayList<>();
		Optional<String> oreConfiguration = Optional.empty();
		try {
			ConfiguredFeature<?, ?> configured = placedFeature.feature().value();
			configuredFeatureType = featureType(configured);

			phase = "modifier_types";
			for (PlacementModifier modifier : placedFeature.placement()) {
				modifierTypes.add(modifierType(modifier));
				modifierConfigurations.add(modifierConfiguration(modifier));
			}

			if (configured.feature() != Feature.ORE && configured.feature() != Feature.SCATTERED_ORE) {
				return occurrence(
					placedFeatureId,
					Optional.of(membership),
					configuredFeatureType,
					modifierTypes,
					modifierConfigurations,
					Optional.empty(),
					Optional.empty(),
					Contract.CUSTOM_DIAGNOSTIC,
					Inspection.unsupported("configured_feature"),
					Action.PRESERVE_UNCHANGED,
					"CUSTOM_CONFIGURED_FEATURE"
				);
			}
			if (!(configured.config() instanceof OreConfiguration ore)) {
				return occurrence(
					placedFeatureId,
					Optional.of(membership),
					configuredFeatureType,
					modifierTypes,
					modifierConfigurations,
					Optional.empty(),
					Optional.empty(),
					Contract.PRESERVE_UNKNOWN,
					Inspection.unsupported("ore_configuration"),
					Action.PRESERVE_UNCHANGED,
					"INVALID_ORE_CONFIGURATION_SHAPE"
				);
			}

			phase = "ore_configuration";
			oreConfiguration = Optional.of(
				OreConfiguration.CODEC.encodeStart(this.ops, ore)
					.getOrThrow(message -> new OreHeightInspector.InspectionFailure("ore_configuration", message))
					.toString()
			);

			phase = "placement_contract";
			HeightRangePlacement heightPlacement = null;
			boolean customFilter = false;
			for (PlacementModifier modifier : placedFeature.placement()) {
				if (modifier instanceof HeightRangePlacement height) {
					if (heightPlacement != null) {
						return preserved(
							placedFeatureId, membership, configuredFeatureType, modifierTypes, modifierConfigurations, oreConfiguration,
							"MULTIPLE_HEIGHT_RANGES", "placement_contract"
						);
					}
					heightPlacement = height;
					continue;
				}
				if (modifier instanceof PlacementFilter filter) {
					customFilter |= !isVanillaFilter(filter);
					continue;
				}
				if (!(modifier instanceof CountPlacement)
					&& !(modifier instanceof RarityFilter)
					&& !(modifier instanceof InSquarePlacement)) {
					return preserved(
						placedFeatureId, membership, configuredFeatureType, modifierTypes, modifierConfigurations, oreConfiguration,
						"UNSUPPORTED_POSITION_MODIFIER", "placement_contract"
					);
				}
			}
			if (heightPlacement == null) {
				return preserved(
					placedFeatureId, membership, configuredFeatureType, modifierTypes, modifierConfigurations, oreConfiguration,
					"MISSING_HEIGHT_RANGE", "placement_contract"
				);
			}
			int fanoutIndex = safeFanoutIndex(placedFeature.placement());
			for (int index = 0; index < fanoutIndex; index++) {
				if (placedFeature.placement().get(index) instanceof PlacementFilter) {
					return preserved(
						placedFeatureId, membership, configuredFeatureType, modifierTypes, modifierConfigurations, oreConfiguration,
						"UPSTREAM_FILTER_BEFORE_SAFE_FANOUT", "placement_contract"
					);
				}
			}

			phase = "height_provider";
			HeightSemantics height = this.heightInspection.inspect(heightPlacement);
			Contract contract = customFilter ? Contract.STANDARD_WITH_CUSTOM_FILTER : Contract.SUPPORTED_STANDARD;
			return occurrence(
				placedFeatureId,
				Optional.of(membership),
				configuredFeatureType,
				modifierTypes,
				modifierConfigurations,
				oreConfiguration,
				Optional.of(height),
				contract,
				Inspection.classified(),
				Action.REPORT_ONLY,
				customFilter ? "SUPPORTED_CUSTOM_PLACEMENT_FILTER" : "SUPPORTED_STANDARD_ORE"
			);
		} catch (OreHeightInspector.UnsupportedHeightProvider unsupported) {
			return preserved(
				placedFeatureId, membership, configuredFeatureType, modifierTypes, modifierConfigurations, oreConfiguration,
				"UNSUPPORTED_HEIGHT_PROVIDER:" + unsupported.provider(), "height_provider"
			);
		} catch (RuntimeException | LinkageError failure) {
			String failurePhase = failure instanceof OreHeightInspector.InspectionFailure inspectionFailure
				? inspectionFailure.phase()
				: phase;
			return occurrence(
				placedFeatureId,
				Optional.of(membership),
				configuredFeatureType,
				modifierTypes,
				modifierConfigurations,
				oreConfiguration,
				Optional.empty(),
				Contract.PRESERVE_UNKNOWN,
				Inspection.failed(failurePhase, failure),
				Action.PRESERVE_UNCHANGED,
				"INSPECTION_FAILED"
			);
		}
	}

	private static int safeFanoutIndex(List<PlacementModifier> modifiers) {
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
			if (modifier instanceof CountPlacement || modifier instanceof RarityFilter) {
				return index;
			}
		}
		return inSquareIndex >= 0 && inSquareIndex < heightIndex ? inSquareIndex : heightIndex;
	}

	public Occurrence inactive(String placedFeatureId, PlacedFeature placedFeature) {
		String configuredFeatureType = "<inspection-failed>";
		List<String> modifierTypes = new ArrayList<>();
		List<String> modifierConfigurations = new ArrayList<>();
		try {
			configuredFeatureType = featureType(placedFeature.feature().value());
			for (PlacementModifier modifier : placedFeature.placement()) {
				modifierTypes.add(modifierType(modifier));
				modifierConfigurations.add(this.modifierConfiguration(modifier));
			}
			return occurrence(
				placedFeatureId,
				Optional.empty(),
				configuredFeatureType,
				modifierTypes,
				modifierConfigurations,
				Optional.empty(),
				Optional.empty(),
				Contract.NO_ACTIVE_MEMBERSHIP,
				Inspection.unsupported("final_membership"),
				Action.PRESERVE_UNCHANGED,
				"NO_ACTIVE_MEMBERSHIP"
			);
		} catch (RuntimeException | LinkageError failure) {
			return occurrence(
				placedFeatureId,
				Optional.empty(),
				configuredFeatureType,
				modifierTypes,
				modifierConfigurations,
				Optional.empty(),
				Optional.empty(),
				Contract.NO_ACTIVE_MEMBERSHIP,
				Inspection.failed("inactive_snapshot", failure),
				Action.PRESERVE_UNCHANGED,
				"INACTIVE_INSPECTION_FAILED"
			);
		}
	}

	private static Occurrence preserved(
		String placedFeatureId,
		Membership membership,
		String configuredFeatureType,
		List<String> modifierTypes,
		List<String> modifierConfigurations,
		Optional<String> oreConfiguration,
		String reason,
		String phase
	) {
		return occurrence(
			placedFeatureId,
			Optional.of(membership),
			configuredFeatureType,
			modifierTypes,
			modifierConfigurations,
			oreConfiguration,
			Optional.empty(),
			Contract.PRESERVE_UNKNOWN,
			Inspection.unsupported(phase),
			Action.PRESERVE_UNCHANGED,
			reason
		);
	}

	private static Occurrence occurrence(
		String placedFeatureId,
		Optional<Membership> membership,
		String configuredFeatureType,
		List<String> modifierTypes,
		List<String> modifierConfigurations,
		Optional<String> oreConfiguration,
		Optional<HeightSemantics> height,
		Contract contract,
		Inspection inspection,
		Action action,
		String reason
	) {
		List<String> semanticParts = List.of(
			configuredFeatureType,
			String.join(",", modifierTypes),
			String.join(",", modifierConfigurations),
			oreConfiguration.orElse(""),
			height.map(Object::toString).orElse(""),
			contract.name(),
			inspection.status().name(),
			reason
		);
		return new Occurrence(
			placedFeatureId,
			membership,
			configuredFeatureType,
			modifierTypes,
			modifierConfigurations,
			oreConfiguration,
			height,
			contract,
			inspection,
			action,
			reason,
			DynamicOrePlanner.fingerprint(semanticParts)
		);
	}

	private static String featureType(ConfiguredFeature<?, ?> configured) {
		ResourceLocation id = BuiltInRegistries.FEATURE.getKey(configured.feature());
		return id == null ? "<unregistered:" + configured.feature().getClass().getName() + ">" : id.toString();
	}

	private static String modifierType(PlacementModifier modifier) {
		ResourceLocation id = BuiltInRegistries.PLACEMENT_MODIFIER_TYPE.getKey(modifier.type());
		return id == null ? "<unregistered:" + modifier.getClass().getName() + ">" : id.toString();
	}

	@SuppressWarnings("unchecked")
	private String modifierConfiguration(PlacementModifier modifier) {
		com.mojang.serialization.Codec<PlacementModifier> codec =
			(com.mojang.serialization.Codec<PlacementModifier>)(com.mojang.serialization.Codec<?>)modifier.type().codec().codec();
		return codec
			.encodeStart(this.ops, modifier)
			.getOrThrow(message -> new OreHeightInspector.InspectionFailure("modifier_configuration", message))
			.toString();
	}

	private static boolean isVanillaFilter(PlacementFilter filter) {
		PlacementModifierType<?> type = filter.type();
		return type == PlacementModifierType.BLOCK_PREDICATE_FILTER
			|| type == PlacementModifierType.RARITY_FILTER
			|| type == PlacementModifierType.SURFACE_RELATIVE_THRESHOLD_FILTER
			|| type == PlacementModifierType.SURFACE_WATER_DEPTH_FILTER
			|| type == PlacementModifierType.BIOME_FILTER;
	}

	@FunctionalInterface
	interface HeightInspection {
		HeightSemantics inspect(HeightRangePlacement placement);
	}
}
