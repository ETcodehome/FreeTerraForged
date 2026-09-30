package etcodehome.freeterraforged.world.worldgen.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import etcodehome.freeterraforged.platform.ResourcePackUtil;
import etcodehome.freeterraforged.world.worldgen.surface.rule.AcquiredSurfaceRule;
import etcodehome.freeterraforged.world.worldgen.surface.rule.InheritedSurfaceRule;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.SurfaceRules;

public final class WorldgenSurfaceInputs {
	private WorldgenSurfaceInputs() {}

	public static Resource acquire(ResourceManager manager, ResourceLocation settingsId, Resource selected) throws IOException {
		JsonObject settings = readSettings(selected);
		JsonElement surface = settings.get("surface_rule");
		if (surface == null || !containsMarker(surface)) return selected;
		var file = FileToIdConverter.json(Registries.elementsDirPath(Registries.NOISE_SETTINGS)).idToFile(settingsId);
		var layers = manager.getResourceStack(file);
		int selectedIndex = -1;
		for (int i = layers.size() - 1; i >= 0; i--) {
			if (layers.get(i).source() == selected.source()) {
				selectedIndex = i;
				break;
			}
		}
		if (selectedIndex < 0) throw new IllegalStateException("Cannot locate surface input resource layer for " + settingsId);
		var cache = new HashMap<Integer, Layer>();
		IntFunction<Layer> lowerRule = index -> cache.computeIfAbsent(index, ignored -> {
			Resource layer = layers.get(index);
			try {
				JsonElement rule = readSettings(layer).get("surface_rule");
				if (rule == null) throw new IllegalStateException("Missing surface_rule");
				return new Layer(settingsId.toString(), layer.sourcePackId(), index,
					ResourcePackUtil.isBundledModPack(layer.source()), rule.toString());
			} catch (IOException | RuntimeException failure) {
				throw new IllegalStateException("Cannot acquire surface input " + settingsId + " from pack " + layer.sourcePackId(), failure);
			}
		});
		settings.add("surface_rule", normalizeLayers(surface, lowerRule,
			index -> ResourcePackUtil.isBundledModPack(layers.get(index).source()), selectedIndex - 1));
		byte[] bytes = settings.toString().getBytes(StandardCharsets.UTF_8);
		return new Resource(selected.source(), () -> new ByteArrayInputStream(bytes), selected::metadata);
	}

	public record Layer(String resource, String pack, int priority, boolean bundled, String rule) {}

	public static JsonElement normalizeLayers(JsonElement rule, IntFunction<Layer> lowerRule, int lowerIndex) {
		return normalizeLayers(rule, lowerRule, index -> lowerRule.apply(index).bundled(), lowerIndex);
	}

	public static JsonElement normalizeLayers(JsonElement rule, IntFunction<Layer> lowerRule, IntPredicate bundled, int lowerIndex) {
		if (isMarker(rule)) {
			JsonElement fallback = rule.getAsJsonObject().get("fallback");
			if (fallback == null) throw new IllegalStateException("Inherited surface input has no explicit fallback");
			String policy = rule.getAsJsonObject().has("policy") ? rule.getAsJsonObject().get("policy").getAsString() : InheritedSurfaceRule.REPLACE;
			if (InheritedSurfaceRule.BIOME_DOMAINS.equals(policy)) return compose(fallback, lowerRule, bundled, lowerIndex);
			if (!InheritedSurfaceRule.REPLACE.equals(policy)) throw new IllegalStateException("Unknown surface acquisition policy: " + policy);
			return lowerIndex >= 0
				? normalizeLayers(JsonParser.parseString(lowerRule.apply(lowerIndex).rule()), lowerRule, bundled, lowerIndex - 1)
				: normalizeLayers(fallback, lowerRule, bundled, -1);
		}
		return mapChildren(rule, child -> normalizeLayers(child, lowerRule, bundled, lowerIndex));
	}

	private static JsonElement compose(JsonElement fallback, IntFunction<Layer> lowerRule, IntPredicate bundled, int lowerIndex) {
		var layers = new ArrayList<Layer>();
		var inputs = new ArrayList<SurfaceRuleComposition.Input>();
		var sources = new ArrayList<AcquiredSurfaceRule.Source>();
		JsonElement root = fallback.deepCopy();
		try {
			for (int i = lowerIndex; i >= 0; i--) {
				if (!inputs.isEmpty() && !bundled.test(i)) break;
				Layer layer = lowerRule.apply(i);
				JsonElement authored = JsonParser.parseString(layer.rule());
				boolean explicitInheritance = containsMarker(authored);
				if (!inputs.isEmpty() && (!layer.bundled() || explicitInheritance)) break;
				JsonElement normalized = normalizeLayers(authored, lowerRule, bundled, i - 1);
				validate(normalized);
				if (inputs.isEmpty()) root = normalized;
				layers.add(layer);
				inputs.add(new SurfaceRuleComposition.Input(layer.pack(), normalized.toString()));
				if (!layer.bundled() || explicitInheritance) break;
			}
			if (inputs.isEmpty()) return acquired(normalizeLayers(fallback, lowerRule, bundled, -1), sources, List.of());
			var result = SurfaceRuleComposition.compose(inputs);
			for (int i = 0; i < layers.size(); i++) {
				var layer = layers.get(i);
				sources.add(source(layer, i == 0 ? (layer.bundled() ? "default" : "replacement") : "biome_domain", result.domains().get(i)));
			}
			return acquired(result.root(), sources, List.of());
		} catch (RuntimeException failure) {
			// The graph remains decodable for independent facets. Compilation/execution rejects SURFACE.
			for (var layer : layers) sources.add(source(layer, "rejected", List.of()));
			return acquired(root, sources, List.of("Surface acquisition failed: " + failure.getMessage()));
		}
	}

	private static AcquiredSurfaceRule.Source source(Layer layer, String operation, List<String> biomes) {
		try {
			String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(layer.rule().getBytes(StandardCharsets.UTF_8)));
			return new AcquiredSurfaceRule.Source(layer.resource(), layer.pack(), layer.priority(), fingerprint, operation, biomes);
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}

	private static JsonElement acquired(JsonElement root, List<AcquiredSurfaceRule.Source> sources, List<String> failures) {
		var result = new JsonObject();
		result.addProperty("type", AcquiredSurfaceRule.TYPE);
		result.add("root", root);
		result.add("sources", AcquiredSurfaceRule.Source.CODEC.listOf().encodeStart(JsonOps.INSTANCE, sources).getOrThrow());
		if (!failures.isEmpty()) {
			var errors = new JsonArray();
			failures.forEach(errors::add);
			result.add("failures", errors);
		}
		return result;
	}

	public static void requireSupported(SurfaceRules.RuleSource root, RegistryAccess registries) {
		// AcquiredSurfaceRule's encoder rejects actual failed snapshots. Opaque codec payloads
		// resembling our JSON are data, not executable rules, and must not be interpreted here.
		SurfaceRules.RuleSource.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), root).getOrThrow();
	}

	public static void validate(JsonElement value) {
		if (value.isJsonObject()) {
			var object = value.getAsJsonObject();
			if (object.has("type") && object.get("type").isJsonPrimitive()
				&& AcquiredSurfaceRule.TYPE.equals(object.get("type").getAsString()) && object.has("failures")) {
				var failures = object.getAsJsonArray("failures");
				if (!failures.isEmpty()) throw new IllegalStateException(failures.toString());
			}
		}
		children(value).forEach(WorldgenSurfaceInputs::validate);
	}

	private static JsonObject readSettings(Resource resource) throws IOException {
		try (var reader = resource.openAsReader()) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		}
	}

	private static boolean containsMarker(JsonElement value) {
		if (isMarker(value)) return true;
		for (var child : children(value)) if (containsMarker(child)) return true;
		return false;
	}

	private static JsonElement mapChildren(JsonElement value, UnaryOperator<JsonElement> mapper) {
		if (!value.isJsonObject()) return value.deepCopy();
		String slot = childSlot(value);
		JsonObject result = new JsonObject();
		value.getAsJsonObject().entrySet().forEach(entry -> {
			if (!entry.getKey().equals(slot)) result.add(entry.getKey(), entry.getValue().deepCopy());
			else if (slot.equals("sequence")) {
				var mapped = new JsonArray();
				entry.getValue().getAsJsonArray().forEach(child -> mapped.add(mapper.apply(child)));
				result.add(slot, mapped);
			} else result.add(slot, mapper.apply(entry.getValue()));
		});
		return result;
	}

	private static Iterable<JsonElement> children(JsonElement value) {
		String slot = childSlot(value);
		if (slot.isEmpty()) return List.of();
		JsonElement child = value.getAsJsonObject().get(slot);
		return slot.equals("sequence") ? child.getAsJsonArray() : List.of(child);
	}

	private static String childSlot(JsonElement value) {
		if (!value.isJsonObject()) return "";
		JsonElement type = value.getAsJsonObject().get("type");
		if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) return "";
		return switch (ResourceLocation.parse(type.getAsString()).toString()) {
			case "minecraft:sequence" -> "sequence";
			case "minecraft:condition" -> "then_run";
			case AcquiredSurfaceRule.TYPE -> "root";
			default -> "";
		};
	}

	private static boolean isMarker(JsonElement value) {
		if (!value.isJsonObject()) return false;
		JsonElement type = value.getAsJsonObject().get("type");
		return type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
			&& InheritedSurfaceRule.TYPE.equals(type.getAsString());
	}
}
