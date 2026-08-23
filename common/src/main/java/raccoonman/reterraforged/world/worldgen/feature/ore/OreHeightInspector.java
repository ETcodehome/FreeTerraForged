package raccoonman.reterraforged.world.worldgen.feature.ore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Anchor;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.AnchorType;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightProviderShape;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.HeightSemantics;

final class OreHeightInspector {
	private final DynamicOps<JsonElement> ops;

	OreHeightInspector() {
		this(JsonOps.INSTANCE);
	}

	OreHeightInspector(DynamicOps<JsonElement> ops) {
		this.ops = ops;
	}

	HeightSemantics inspect(HeightRangePlacement placement) {
		JsonElement encoded = HeightRangePlacement.CODEC.codec()
			.encodeStart(this.ops, placement)
			.getOrThrow(message -> new InspectionFailure("height_codec", message));
		JsonObject root = object(encoded, "height_codec", "height placement");
		JsonObject height = object(required(root, "height", "height_codec"), "height_codec", "height provider");
		if (!height.has("type")) {
			// HeightProvider.CODEC represents ConstantHeight as a bare anchor.
			throw new UnsupportedHeightProvider("minecraft:constant");
		}
		String type = string(height, "type", "height_provider");

		HeightProviderShape provider = switch (type) {
			case "minecraft:uniform" -> HeightProviderShape.UNIFORM;
			case "minecraft:trapezoid" -> HeightProviderShape.TRAPEZOID;
			default -> throw new UnsupportedHeightProvider(type);
		};
		Anchor min = anchor(required(height, "min_inclusive", "height_anchor"));
		Anchor max = anchor(required(height, "max_inclusive", "height_anchor"));
		int plateau = provider == HeightProviderShape.TRAPEZOID && height.has("plateau")
			? height.get("plateau").getAsInt()
			: 0;
		return new HeightSemantics(provider, min, max, plateau);
	}

	private static Anchor anchor(JsonElement encoded) {
		JsonObject object = object(encoded, "height_anchor", "vertical anchor");
		if (object.size() != 1) {
			throw new InspectionFailure("height_anchor", "Expected exactly one vertical-anchor member: " + object);
		}
		if (object.has("absolute")) {
			return new Anchor(AnchorType.ABSOLUTE, object.get("absolute").getAsInt());
		}
		if (object.has("above_bottom")) {
			return new Anchor(AnchorType.ABOVE_BOTTOM, object.get("above_bottom").getAsInt());
		}
		if (object.has("below_top")) {
			return new Anchor(AnchorType.BELOW_TOP, object.get("below_top").getAsInt());
		}
		throw new InspectionFailure("height_anchor", "Unknown vertical-anchor shape: " + object);
	}

	private static JsonElement required(JsonObject object, String member, String phase) {
		JsonElement value = object.get(member);
		if (value == null) {
			throw new InspectionFailure(phase, "Missing member '" + member + "': " + object);
		}
		return value;
	}

	private static String string(JsonObject object, String member, String phase) {
		return required(object, member, phase).getAsString();
	}

	private static JsonObject object(JsonElement value, String phase, String description) {
		if (!value.isJsonObject()) {
			throw new InspectionFailure(phase, "Expected " + description + " object: " + value);
		}
		return value.getAsJsonObject();
	}

	static final class UnsupportedHeightProvider extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final String provider;

		UnsupportedHeightProvider(String provider) {
			super(provider);
			this.provider = provider;
		}

		String provider() {
			return this.provider;
		}
	}

	static final class InspectionFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final String phase;

		InspectionFailure(String phase, String message) {
			super(message);
			this.phase = phase;
		}

		String phase() {
			return this.phase;
		}
	}
}
