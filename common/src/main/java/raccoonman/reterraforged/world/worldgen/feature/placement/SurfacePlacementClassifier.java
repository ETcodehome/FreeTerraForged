package raccoonman.reterraforged.world.worldgen.feature.placement;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.valueproviders.IntProvider;
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
import raccoonman.reterraforged.mixin.EnvironmentScanPlacementAccessor;
import raccoonman.reterraforged.mixin.RandomOffsetPlacementAccessor;

/**
 * Recognizes only the conventional placement-modifier pipeline whose declared
 * semantics make same-column surface rescue safe. Unknown shapes fail closed.
 */
final class SurfacePlacementClassifier {

	private SurfacePlacementClassifier() {
	}

	static Classification classify(PlacedFeature feature, HolderLookup.Provider registries) {
		List<PlacementModifier> modifiers = feature.placement();
		int scanIndex = uniqueIndex(modifiers, EnvironmentScanPlacement.class);
		if (scanIndex < 2 || scanIndex + 1 >= modifiers.size()) {
			return Classification.rejected();
		}
		if (!(modifiers.get(scanIndex - 1) instanceof HeightRangePlacement height)
			|| !DynamicHeightRangePlacement.isCanonicalRange(height)
			|| !(modifiers.get(scanIndex - 2) instanceof InSquarePlacement)
			|| !(modifiers.get(scanIndex + 1) instanceof RandomOffsetPlacement offset)) {
			return Classification.rejected();
		}

		CountPlacement countPlacement = null;
		for (int i = 0; i < scanIndex - 2; i++) {
			PlacementModifier modifier = modifiers.get(i);
			if (!(modifier instanceof CountPlacement) && !(modifier instanceof RarityFilter)) {
				return Classification.rejected();
			}
			if (modifier instanceof CountPlacement count) {
				if (countPlacement != null) {
					return Classification.rejected();
				}
				countPlacement = count;
			}
		}
		for (int i = scanIndex + 2; i < modifiers.size(); i++) {
			PlacementModifier modifier = modifiers.get(i);
			if (!(modifier instanceof BiomeFilter) && !(modifier instanceof BlockPredicateFilter)) {
				return Classification.rejected();
			}
		}

		EnvironmentScanPlacement scan = (EnvironmentScanPlacement)modifiers.get(scanIndex);
		EnvironmentScanPlacementAccessor scanAccessor = (EnvironmentScanPlacementAccessor)(Object)scan;
		Direction direction = scanAccessor.reterraforged$getDirectionOfSearch();
		if (direction.getAxis() != Direction.Axis.Y) {
			return Classification.rejected();
		}

		RandomOffsetPlacementAccessor offsetAccessor = (RandomOffsetPlacementAccessor)(Object)offset;
		IntProvider xz = offsetAccessor.reterraforged$getXzSpread();
		IntProvider y = offsetAccessor.reterraforged$getYSpread();
		int expectedY = -direction.getStepY();
		if (!isConstant(xz, 0) || !isConstant(y, expectedY)) {
			return Classification.rejected();
		}

		BlockPredicate allowed = scanAccessor.reterraforged$getAllowedSearchCondition();
		BlockPredicate target = scanAccessor.reterraforged$getTargetCondition();
		JsonElement allowedJson = encode(allowed, registries);
		JsonElement targetJson = encode(target, registries);
		if (allowedJson == null
			|| targetJson == null
			|| !isOnlyAir(allowed, allowedJson)
			|| !isSurfaceTarget(target, targetJson, direction)) {
			return Classification.rejected();
		}

		return Classification.eligible(new SurfacePipeline(
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

	private static boolean isConstant(IntProvider provider, int value) {
		return provider.getMinValue() == value && provider.getMaxValue() == value;
	}

	private static JsonElement encode(BlockPredicate predicate, HolderLookup.Provider registries) {
		return BlockPredicate.CODEC
			.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), predicate)
			.result()
			.orElse(null);
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
		return predicate.type() == BlockPredicateType.MATCHING_BLOCK_TAG
			&& object.has("tag")
			&& "minecraft:air".equals(object.get("tag").getAsString());
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

	record SurfacePipeline(
		EnvironmentScanPlacement scan,
		BlockPredicate target,
		BlockPredicate allowed,
		int placementOffsetY,
		List<PlacementModifier> downstreamFilters,
		CountPlacement countPlacement
	) {
	}

	record Classification(SurfacePipeline pipeline) {

		static Classification eligible(SurfacePipeline pipeline) {
			return new Classification(pipeline);
		}

		static Classification rejected() {
			return new Classification(null);
		}

		boolean eligible() {
			return this.pipeline != null;
		}
	}
}
