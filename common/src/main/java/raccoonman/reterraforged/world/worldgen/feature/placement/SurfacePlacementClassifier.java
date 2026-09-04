package raccoonman.reterraforged.world.worldgen.feature.placement;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicateType;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

/**
 * Recognizes only the conventional placement-modifier pipeline whose declared
 * semantics make same-column surface rescue safe. Unknown shapes fail closed.
 */
public final class SurfacePlacementClassifier {

	private SurfacePlacementClassifier() {
	}

	public static Classification classify(
		PlacedFeature feature,
		Optional<ResourceLocation> featureId,
		HolderLookup.Provider registries
	) {
		try {
			return classifyChecked(feature, featureId, registries);
		} catch (RuntimeException | LinkageError failure) {
			return Classification.failed("SURFACE_CONTRACT_INSPECTION_FAILED", failure);
		}
	}

	private static Classification classifyChecked(
		PlacedFeature feature,
		Optional<ResourceLocation> featureId,
		HolderLookup.Provider registries
	) {
		List<PlacementModifier> modifiers = feature.placement();
		int scanIndex = uniqueIndex(modifiers, EnvironmentScanPlacement.class);
		if (scanIndex < 2 || scanIndex + 1 >= modifiers.size()) {
			return Classification.rejected(scanIndex == -2
				? "MULTIPLE_ENVIRONMENT_SCANS"
				: "MISSING_CANONICAL_SURFACE_SCAN_SEQUENCE");
		}
		if (!(modifiers.get(scanIndex - 1) instanceof HeightRangePlacement height)
			|| !DynamicHeightRangePlacement.isCanonicalRange(height)
			|| !(modifiers.get(scanIndex - 2) instanceof InSquarePlacement)
			|| !(modifiers.get(scanIndex + 1) instanceof RandomOffsetPlacement offset)) {
			return Classification.rejected("NON_CANONICAL_SURFACE_SCAN_SEQUENCE");
		}

		CountPlacement countPlacement = null;
		for (int i = 0; i < scanIndex - 2; i++) {
			PlacementModifier modifier = modifiers.get(i);
			if (!(modifier instanceof CountPlacement) && !(modifier instanceof RarityFilter)) {
				return Classification.rejected("UNSUPPORTED_UPSTREAM_SURFACE_MODIFIER");
			}
			if (modifier instanceof CountPlacement count) {
				if (countPlacement != null) {
					return Classification.rejected("MULTIPLE_SURFACE_COUNT_STAGES");
				}
				countPlacement = count;
			}
		}
		for (int i = scanIndex + 2; i < modifiers.size(); i++) {
			PlacementModifier modifier = modifiers.get(i);
			if (!(modifier instanceof BiomeFilter) && !(modifier instanceof BlockPredicateFilter)) {
				return Classification.rejected("UNSUPPORTED_DOWNSTREAM_SURFACE_MODIFIER");
			}
		}

		EnvironmentScanPlacement scan = (EnvironmentScanPlacement)modifiers.get(scanIndex);
		JsonObject scanJson = encode(EnvironmentScanPlacement.CODEC.codec(), scan, registries);
		if (scanJson == null) {
			return Classification.rejected("ENVIRONMENT_SCAN_CODEC_UNAVAILABLE");
		}
		String directionName = string(scanJson, "direction_of_search");
		Direction direction = directionName == null ? null : Direction.byName(directionName);
		if (direction != Direction.DOWN) {
			return Classification.rejected("NON_DOWNWARD_SURFACE_SCAN");
		}
		JsonObject offsetJson = encode(RandomOffsetPlacement.CODEC.codec(), offset, registries);
		int expectedY = -direction.getStepY();
		if (offsetJson == null
			|| !isConstant(offsetJson.get("xz_spread"), 0)
			|| !isConstant(offsetJson.get("y_spread"), expectedY)) {
			return Classification.rejected("NON_CANONICAL_SURFACE_OFFSET");
		}

		JsonElement allowedJson = scanJson.get("allowed_search_condition");
		JsonElement targetJson = scanJson.get("target_condition");
		BlockPredicate allowed = decodePredicate(allowedJson, registries);
		BlockPredicate target = decodePredicate(targetJson, registries);
		if (allowedJson == null
			|| targetJson == null
			|| allowed == null
			|| target == null
			|| !isOnlyAir(allowed, allowedJson)
			|| !isSurfaceTarget(target, targetJson, direction)) {
			return Classification.rejected("UNPROVEN_SURFACE_SCAN_PREDICATES");
		}

		return Classification.eligible(new SurfacePipeline(
			featureId,
			height,
			scan,
			target,
			allowed,
			expectedY,
			List.copyOf(modifiers.subList(scanIndex + 2, modifiers.size())),
			countPlacement
		));
	}

	private static int uniqueIndex(List<PlacementModifier> modifiers, Class<?> type) {
		int result = -1;
		for (int i = 0; i < modifiers.size(); i++) {
			if (type.isInstance(modifiers.get(i))) {
				if (result >= 0) {
					return -2;
				}
				result = i;
			}
		}
		return result;
	}

	private static boolean isConstant(JsonElement provider, int value) {
		return provider != null && provider.isJsonPrimitive()
			&& provider.getAsJsonPrimitive().isNumber()
			&& provider.getAsInt() == value;
	}

	private static <T> JsonObject encode(Codec<T> codec, T value, HolderLookup.Provider registries) {
		JsonElement encoded = codec
			.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), value)
			.result()
			.orElse(null);
		return encoded != null && encoded.isJsonObject() ? encoded.getAsJsonObject() : null;
	}

	private static BlockPredicate decodePredicate(JsonElement encoded, HolderLookup.Provider registries) {
		if (encoded == null) {
			return null;
		}
		return BlockPredicate.CODEC
			.parse(RegistryOps.create(JsonOps.INSTANCE, registries), encoded)
			.result()
			.orElse(null);
	}

	private static String string(JsonObject object, String member) {
		JsonElement value = object.get(member);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static boolean isOnlyAir(BlockPredicate predicate, JsonElement json) {
		if (!json.isJsonObject() || !hasZeroOffset(json.getAsJsonObject())) {
			return false;
		}
		JsonObject object = json.getAsJsonObject();
		if (predicate.type() == BlockPredicateType.MATCHING_BLOCKS) {
			JsonElement blocks = object.get("blocks");
			if (blocks == null) {
				return false;
			}
			if (blocks.isJsonPrimitive()) {
				return "minecraft:air".equals(blocks.getAsString());
			}
			if (blocks.isJsonArray()) {
				JsonArray array = blocks.getAsJsonArray();
				return array.size() == 1 && "minecraft:air".equals(array.get(0).getAsString());
			}
			return false;
		}
		return false;
	}

	private static boolean isSurfaceTarget(BlockPredicate predicate, JsonElement json, Direction searchDirection) {
		if (!json.isJsonObject() || !hasZeroOffset(json.getAsJsonObject())) {
			return false;
		}
		if (predicate.type() == BlockPredicateType.SOLID) {
			return true;
		}
		JsonObject object = json.getAsJsonObject();
		return predicate.type() == BlockPredicateType.HAS_STURDY_FACE
			&& object.has("direction")
			&& searchDirection.getOpposite().getSerializedName().equals(object.get("direction").getAsString());
	}

	private static boolean hasZeroOffset(JsonObject object) {
		JsonElement offset = object.get("offset");
		if (offset == null) {
			return true;
		}
		if (!offset.isJsonArray()) {
			return false;
		}
		JsonArray array = offset.getAsJsonArray();
		return array.size() == 3
			&& isZero(array.get(0))
			&& isZero(array.get(1))
			&& isZero(array.get(2));
	}

	private static boolean isZero(JsonElement value) {
		return value instanceof JsonPrimitive primitive && primitive.isNumber() && primitive.getAsInt() == 0;
	}

	public record SurfacePipeline(
		Optional<ResourceLocation> featureId,
		HeightRangePlacement heightRange,
		EnvironmentScanPlacement scan,
		BlockPredicate target,
		BlockPredicate allowed,
		int placementOffsetY,
		List<PlacementModifier> downstreamFilters,
		CountPlacement countPlacement
	) {
		public SurfacePipeline {
			featureId = java.util.Objects.requireNonNull(featureId, "featureId");
		}
	}

	public record Classification(SurfacePipeline pipeline, String reasonCode, String failure) {
		public Classification {
			reasonCode = java.util.Objects.requireNonNull(reasonCode, "reasonCode");
		}

		public static Classification eligible(SurfacePipeline pipeline) {
			return new Classification(
				java.util.Objects.requireNonNull(pipeline, "pipeline"),
				"SUPPORTED_SURFACE_RESCUE_CONTRACT",
				null
			);
		}

		public static Classification rejected() {
			return rejected("NOT_CLASSIFIED");
		}

		public static Classification rejected(String reasonCode) {
			return new Classification(null, reasonCode, null);
		}

		public static Classification failed(String reasonCode, Throwable failure) {
			String detail = failure.getClass().getName() + ": "
				+ java.util.Optional.ofNullable(failure.getMessage()).orElse("<no message>");
			return new Classification(null, reasonCode, detail);
		}

		public boolean eligible() {
			return this.pipeline != null;
		}

		public boolean failed() {
			return this.failure != null;
		}
	}
}
