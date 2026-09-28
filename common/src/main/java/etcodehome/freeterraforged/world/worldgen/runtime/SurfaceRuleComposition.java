package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import etcodehome.freeterraforged.world.worldgen.surface.rule.AcquiredSurfaceRule;

/** Pure acquisition algebra. Graphs execute unchanged; only disjoint dispatch is synthesized. */
public final class SurfaceRuleComposition {
	private SurfaceRuleComposition() {}

	/** Highest priority first. Callers cut the list at replacement/filter barriers. */
	public static Result compose(List<Input> inputs) {
		if (inputs.isEmpty()) throw new IllegalArgumentException("Surface composition requires a root");
		var roots = inputs.stream().map(input -> JsonParser.parseString(input.rule())).toList();
		if (inputs.size() == 1) return new Result(roots.getFirst(), List.of(List.of()));
		var claimed = new TreeSet<String>();
		var recovered = new TreeSet<String>();
		var domains = new ArrayList<List<String>>();
		var dispatch = new JsonArray();
		for (int i = 0; i < inputs.size(); i++) {
			Set<String> domain;
			try {
				domain = domain(roots.get(i));
			} catch (IllegalArgumentException failure) {
				throw new IllegalArgumentException("Surface input pack " + inputs.get(i).pack() + ": " + failure.getMessage()
					+ "; use an explicit replacement surface root or a supported finite-biome contribution", failure);
			}
			domain.removeAll(claimed);
			claimed.addAll(domain);
			domains.add(List.copyOf(domain));
			if (i > 0 && !domain.isEmpty()) {
				recovered.addAll(domain);
				dispatch.add(condition(biomes(domain), roots.get(i)));
			}
		}
		if (recovered.isEmpty()) return new Result(roots.getFirst(), domains);
		// Null from a selected authored root remains null. Never fall through to a different root.
		dispatch.add(condition(not(biomes(recovered)), roots.getFirst()));
		return new Result(sequence(dispatch), domains);
	}

	public record Input(String pack, String rule) {}
	public record Result(JsonElement root, List<List<String>> domains) {
		public Result {
			domains = domains.stream().map(List::copyOf).toList();
		}
	}

	/** Explicit finite biome discriminators, excluding literals in provably unreachable branches. */
	public static Set<String> domain(JsonElement root) {
		var mentioned = new TreeSet<String>();
		inspectRule(root, false, mentioned, "$surface");
		JsonElement background = specialize(root, null);
		mentioned.removeIf(biome -> specialize(root, biome).equals(background));
		return mentioned;
	}

	private static void inspectRule(JsonElement rule, boolean bounded, Set<String> names, String path) {
		var object = object(rule, path);
		switch (type(object, path)) {
			case "minecraft:sequence" -> {
				var children = object.getAsJsonArray("sequence");
				for (int i = 0; i < children.size(); i++) inspectRule(children.get(i), bounded, names, path + ".sequence[" + i + "]");
			}
			case "minecraft:condition" -> {
				boolean finite = inspectCondition(object.get("if_true"), false, bounded, names, path + ".if_true");
				inspectRule(object.get("then_run"), bounded || finite, names, path + ".then_run");
			}
			case "minecraft:block", "minecraft:bandlands" -> {}
			case AcquiredSurfaceRule.TYPE -> inspectRule(object.get("root"), bounded, names, path + ".root");
			default -> {
				if (!bounded) throw new IllegalArgumentException("Unbounded rule " + type(object, path) + " at " + path);
			}
		}
	}

	private static boolean inspectCondition(JsonElement value, boolean negated, boolean bounded, Set<String> names, String path) {
		var object = object(value, path);
		return switch (type(object, path)) {
			case "minecraft:biome" -> {
				for (var name : object.getAsJsonArray("biome_is")) names.add(ResourceLocation.parse(name.getAsString()).toString());
				yield !negated;
			}
			case "minecraft:not" -> inspectCondition(object.get("invert"), !negated, bounded, names, path + ".invert");
			case "minecraft:above_preliminary_surface", "minecraft:hole", "minecraft:noise_threshold",
				"minecraft:steep", "minecraft:stone_depth", "minecraft:temperature", "minecraft:vertical_gradient",
				"minecraft:water", "minecraft:y_above" -> false;
			default -> {
				if (!bounded) throw new IllegalArgumentException("Unbounded condition " + type(object, path) + " at " + path);
				yield false;
			}
		};
	}

	/** Symbolic analysis only: never substitutes this simplified graph for the authored executable. */
	private static JsonElement specialize(JsonElement rule, String biome) {
		var object = rule.getAsJsonObject();
		switch (type(object, "$surface")) {
			case "minecraft:sequence": {
				var children = new JsonArray();
				for (var child : object.getAsJsonArray("sequence")) {
					var specialized = specialize(child, biome);
					if (!isEmpty(specialized)) children.add(specialized);
					if (isTotal(specialized)) break;
				}
				return children.size() == 1 ? children.get(0) : sequence(children);
			}
			case "minecraft:condition": {
				Boolean truth = biomeTruth(object.get("if_true"), biome);
				if (Boolean.FALSE.equals(truth)) return sequence(new JsonArray());
				var child = specialize(object.get("then_run"), biome);
				if (isEmpty(child)) return child;
				// Preserve the explicit domain declaration even when its material equals the generic fallback.
				// This witness exists only in symbolic analysis, never in the published rule graph.
				if (Boolean.TRUE.equals(truth)) {
					var witness = new JsonObject();
					witness.addProperty("type", "freeterraforged:analysis_biome_witness");
					witness.add("condition", object.get("if_true"));
					witness.add("root", child);
					return witness;
				}
				return condition(object.get("if_true"), child);
			}
			case AcquiredSurfaceRule.TYPE: return specialize(object.get("root"), biome);
			default: return rule;
		}
	}

	private static Boolean biomeTruth(JsonElement value, String biome) {
		var object = value.getAsJsonObject();
		return switch (type(object, "$condition")) {
			case "minecraft:biome" -> {
				boolean matches = false;
				for (var name : object.getAsJsonArray("biome_is")) matches |= ResourceLocation.parse(name.getAsString()).toString().equals(biome);
				yield matches;
			}
			case "minecraft:not" -> {
				Boolean child = biomeTruth(object.get("invert"), biome);
				yield child == null ? null : !child;
			}
			default -> null;
		};
	}

	private static boolean isEmpty(JsonElement value) {
		return "minecraft:sequence".equals(type(value.getAsJsonObject(), "$surface"))
			&& value.getAsJsonObject().getAsJsonArray("sequence").isEmpty();
	}

	private static boolean isTotal(JsonElement value) {
		var object = value.getAsJsonObject();
		return switch (type(object, "$surface")) {
			case "minecraft:block", "minecraft:bandlands" -> true;
			case "freeterraforged:analysis_biome_witness" -> isTotal(object.get("root"));
			case "minecraft:sequence" -> {
				boolean total = false;
				for (var child : object.getAsJsonArray("sequence")) total |= isTotal(child);
				yield total;
			}
			default -> false;
		};
	}

	private static JsonObject object(JsonElement value, String path) {
		if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Expected rule/condition object at " + path);
		return value.getAsJsonObject();
	}

	private static String type(JsonObject object, String path) {
		if (!object.has("type")) throw new IllegalArgumentException("Missing rule/condition type at " + path);
		return ResourceLocation.parse(object.get("type").getAsString()).toString();
	}

	private static JsonObject biomes(Set<String> names) {
		var result = new JsonObject();
		result.addProperty("type", "minecraft:biome");
		var values = new JsonArray();
		names.forEach(values::add);
		result.add("biome_is", values);
		return result;
	}

	private static JsonObject not(JsonElement condition) {
		var result = new JsonObject();
		result.addProperty("type", "minecraft:not");
		result.add("invert", condition);
		return result;
	}

	private static JsonObject condition(JsonElement condition, JsonElement rule) {
		var result = new JsonObject();
		result.addProperty("type", "minecraft:condition");
		result.add("if_true", condition);
		result.add("then_run", rule);
		return result;
	}

	private static JsonObject sequence(JsonArray rules) {
		var result = new JsonObject();
		result.addProperty("type", "minecraft:sequence");
		result.add("sequence", rules);
		return result;
	}
}
