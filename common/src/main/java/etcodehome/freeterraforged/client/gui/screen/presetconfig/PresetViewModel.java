package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Converts the preset JSON schema into the editor-oriented model used by the read-only viewer. */
final class PresetViewModel {
	private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String GUI = FTFCommon.MOD_ID + ".gui.";
	private static final String VIEW = GUI + "preset_view.";
	private static final List<String> CATEGORY_ORDER = List.of(
		"world",
		"surface",
		"caves",
		"climate",
		"terrain",
		"rivers",
		"flow",
		"island",
		"erosionFilters",
		"miscellaneous",
		"presentation"
	);
	private static final Map<String, List<String>> SECTION_ORDER = Map.ofEntries(
		Map.entry("world", List.of("continent", "controlPoints", "properties")),
		Map.entry("surface", List.of("erosion")),
		Map.entry("caves", List.of("noiseCaves", "carvers")),
		Map.entry("climate", List.of("temperature", "moisture", "biomeShape", "biomeEdgeShape", "climateEffects")),
		Map.entry("terrain", List.of(
			"general", "steppe", "plains", "hills", "dales", "plateau", "badlands", "torridonian", "mountains", "volcano"
		)),
		Map.entry("rivers", List.of("general", "mainRivers", "branchRivers", "lakes", "wetlands")),
		Map.entry("flow", List.of("riverFlowDynamics")),
		Map.entry("island", List.of("island", "islandChances", "islandScales", "islandTransitions")),
		Map.entry("erosionFilters", List.of("erosion")),
		Map.entry("miscellaneous", List.of("general")),
		Map.entry("presentation", List.of("details"))
	);

	private static final Map<String, String> CATEGORY_TITLES = Map.ofEntries(
		Map.entry("world", FTFTranslationKeys.GUI_WORLD_SETTINGS_TITLE),
		Map.entry("surface", FTFTranslationKeys.GUI_SURFACE_SETTINGS_TITLE),
		Map.entry("caves", FTFTranslationKeys.GUI_UNDERGROUND_SETTINGS_TITLE),
		Map.entry("climate", FTFTranslationKeys.GUI_CLIMATE_SETTINGS_TITLE),
		Map.entry("terrain", FTFTranslationKeys.GUI_TERRAIN_SETTINGS_TITLE),
		Map.entry("rivers", FTFTranslationKeys.GUI_RIVER_SETTINGS_TITLE),
		Map.entry("flow", VIEW + "category.flow"),
		Map.entry("island", FTFTranslationKeys.GUI_ISLAND_SETTINGS_TITLE),
		Map.entry("erosionFilters", FTFTranslationKeys.GUI_FILTER_SETTINGS_TITLE),
		Map.entry("miscellaneous", FTFTranslationKeys.GUI_MISCELLANEOUS_SETTINGS_TITLE),
		Map.entry("presentation", VIEW + "category.presentation")
	);

	private static final Map<String, String> SECTION_TITLES = Map.ofEntries(
		Map.entry("general", FTFTranslationKeys.GUI_LABEL_GENERAL),
		Map.entry("continent", FTFTranslationKeys.GUI_LABEL_CONTINENT),
		Map.entry("controlPoints", FTFTranslationKeys.GUI_LABEL_CONTROL_POINTS),
		Map.entry("properties", FTFTranslationKeys.GUI_LABEL_PROPERTIES),
		Map.entry("scree", FTFTranslationKeys.GUI_LABEL_SCREE_THRESHOLDS),
		Map.entry("noiseCaves", FTFTranslationKeys.GUI_LABEL_NOISE_CAVES),
		Map.entry("carvers", FTFTranslationKeys.GUI_LABEL_CARVERS),
		Map.entry("temperature", FTFTranslationKeys.GUI_LABEL_TEMPERATURE),
		Map.entry("moisture", FTFTranslationKeys.GUI_LABEL_MOISTURE),
		Map.entry("biomeShape", FTFTranslationKeys.GUI_LABEL_BIOME_SHAPE),
		Map.entry("biomeEdgeShape", FTFTranslationKeys.GUI_LABEL_BIOME_EDGE_SHAPE),
		Map.entry("steppe", FTFTranslationKeys.GUI_LABEL_STEPPE),
		Map.entry("plains", FTFTranslationKeys.GUI_LABEL_PLAINS),
		Map.entry("hills", FTFTranslationKeys.GUI_LABEL_HILLS),
		Map.entry("dales", FTFTranslationKeys.GUI_LABEL_DALES),
		Map.entry("plateau", FTFTranslationKeys.GUI_LABEL_PLATEAU),
		Map.entry("badlands", FTFTranslationKeys.GUI_LABEL_BADLANDS),
		Map.entry("torridonian", FTFTranslationKeys.GUI_LABEL_TORRIDONIAN),
		Map.entry("mountains", FTFTranslationKeys.GUI_LABEL_MOUNTAINS),
		Map.entry("volcano", FTFTranslationKeys.GUI_LABEL_VOLCANO),
		Map.entry("mainRivers", FTFTranslationKeys.GUI_LABEL_MAIN_RIVERS),
		Map.entry("branchRivers", FTFTranslationKeys.GUI_LABEL_BRANCH_RIVERS),
		Map.entry("lakes", FTFTranslationKeys.GUI_LABEL_LAKES),
		Map.entry("wetlands", FTFTranslationKeys.GUI_LABEL_WETLANDS),
		Map.entry("riverFlowDynamics", FTFTranslationKeys.GUI_LABEL_RIVER_FLOW_DYNAMICS),
		Map.entry("island", FTFTranslationKeys.GUI_LABEL_ISLAND),
		Map.entry("islandChances", FTFTranslationKeys.GUI_LABEL_ISLAND_CHANCES),
		Map.entry("islandScales", FTFTranslationKeys.GUI_LABEL_ISLAND_SCALES),
		Map.entry("islandTransitions", FTFTranslationKeys.GUI_LABEL_ISLAND_TRANSITIONS),
		Map.entry("erosion", FTFTranslationKeys.GUI_LABEL_EROSION),
		Map.entry("climateEffects", VIEW + "section.climate_effects"),
		Map.entry("details", VIEW + "section.details")
	);

	private PresetViewModel() {
	}

	static List<Category> create(JsonObject root) {
		List<Category> categories = new ArrayList<>();
		for (String categoryKey : orderedKeys(root, CATEGORY_ORDER)) {
			categories.add(new Category(
				categoryKey,
				categoryTitle(categoryKey),
				createSections(categoryKey, root.get(categoryKey))
			));
		}
		return List.copyOf(categories);
	}

	private static List<Section> createSections(String category, JsonElement value) {
		Map<String, List<Setting>> grouped = new LinkedHashMap<>();
		if (!value.isJsonObject()) {
			addSetting(grouped, "general", category, category, category, value);
		} else {
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				String path = category + "." + entry.getKey();
				if (entry.getValue().isJsonObject()) {
					String section = entry.getKey();
					collectSettings(grouped, section, category, path, entry.getValue());
				} else {
					String section = sectionForPrimitive(category, entry.getKey());
					addSetting(grouped, section, category, path, entry.getKey(), entry.getValue());
				}
			}
		}

		List<Section> sections = new ArrayList<>();
		for (String sectionKey : orderedKeys(grouped, SECTION_ORDER.getOrDefault(category, List.of()))) {
			sections.add(new Section(
				sectionKey,
				sectionTitle(category, sectionKey),
				List.copyOf(grouped.get(sectionKey))
			));
		}
		return List.copyOf(sections);
	}

	private static void collectSettings(
		Map<String, List<Setting>> grouped,
		String section,
		String category,
		String path,
		JsonElement value
	) {
		if (!value.isJsonObject()) {
			addSetting(grouped, section, category, path, lastSegment(path), value);
			return;
		}
		for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
			collectSettings(grouped, section, category, path + "." + entry.getKey(), entry.getValue());
		}
	}

	private static void addSetting(
		Map<String, List<Setting>> grouped,
		String section,
		String category,
		String path,
		String key,
		JsonElement value
	) {
		String translationKey = settingTranslationKey(category, section, key);
		Component label = translationKey == null
			? Component.literal(friendlyName(key))
			: Component.translatable(translationKey);
		Component displayedValue = displayValue(value);
		Component tooltip = settingTooltip(path, value, translationKey);
		grouped.computeIfAbsent(section, ignored -> new ArrayList<>()).add(new Setting(
			path,
			label,
			displayedValue,
			copyPair(path, value),
			tooltip
		));
	}

	private static String sectionForPrimitive(String category, String key) {
		if (category.equals("caves")) {
			return switch (key) {
				case "entranceCaveProbability", "cheeseCaveDepthOffset", "cheeseCaveProbability",
					"spaghettiCaveProbability", "noodleCaveProbability" -> "noiseCaves";
				default -> "carvers";
			};
		}
		if (category.equals("island")) {
			return switch (key) {
				case "enableArchipelago" -> "island";
				case "islandDensity", "macroDensityPercentage", "mountainChance", "volcanoChance" -> "islandChances";
				case "offshoreDepth", "beachWidth", "beachCoverage" -> "islandTransitions";
				default -> "islandScales";
			};
		}
		return switch (category) {
			case "flow" -> "riverFlowDynamics";
			case "erosionFilters" -> "erosion";
			case "climate" -> "climateEffects";
			case "presentation" -> "details";
			default -> "general";
		};
	}

	private static String settingTranslationKey(String category, String section, String key) {
		List<String> candidates = new ArrayList<>();
		if (category.equals("climate")) {
			if (section.equals("temperature") || section.equals("moisture")) {
				String prefix = section;
				if (key.equals("seedOffset")) {
					candidates.add(GUI + "button.climateSeedOffset");
				} else if (key.equals("bias")) {
					candidates.add(GUI + "slider." + prefix + "OffsetBias");
				} else {
					candidates.add(GUI + "slider." + prefix + capitalize(key));
				}
			} else if (section.equals("biomeEdgeShape")) {
				candidates.add(GUI + (key.equals("type") ? "button.biomeEdgeType" : "slider.biomeEdge" + capitalize(key)));
			}
		}
		if (category.equals("terrain") && !section.equals("general")) {
			candidates.add(GUI + "slider.terrain." + key);
		}
		if (category.equals("rivers")) {
			if (key.equals("seedOffset")) {
				candidates.add(GUI + "button.riverSeedOffset");
			} else if (section.equals("mainRivers") || section.equals("branchRivers")) {
				candidates.add(GUI + "slider.river." + key);
			} else if (section.equals("lakes")) {
				candidates.add(GUI + "slider.lake." + key);
			} else if (section.equals("wetlands")) {
				candidates.add(GUI + "slider." + (key.equals("chance") ? "wetlandChance" : "wetland." + key));
			}
		}
		if (category.equals("island")) {
			candidates.add(GUI + "slider.island" + capitalize(key));
		}
		if (category.equals("erosionFilters")) {
			candidates.add(GUI + "slider.erosionFilter" + capitalize(key));
		}
		if (category.equals("caves")) {
			if (key.equals("caveCarverProbability")) {
				candidates.add(GUI + "slider.carverCaveProbability");
			} else if (key.equals("deepCaveCarverProbability")) {
				candidates.add(GUI + "slider.deepCarverCaveProbability");
			} else if (key.equals("ravineCarverProbability")) {
				candidates.add(GUI + "slider.ravineProbability");
			}
		}
		if (category.equals("miscellaneous") && key.equals("plainStoneErosion")) {
			candidates.add(GUI + "button.stoneErosion");
		}
		if (category.equals("flow")) {
			candidates.add(switch (key) {
				case "flowParticles" -> GUI + "button.river.flow.particles";
				case "boatFlowDynamics" -> GUI + "button.boat.flow.dynamics";
				case "navigableWaterfalls" -> GUI + "button.boat.navigable.waterfalls";
				default -> "";
			});
		}
		if (category.equals("presentation")) {
			candidates.add(VIEW + "setting." + snakeCase(key));
		}

		candidates.add(GUI + "button." + key);
		candidates.add(GUI + "slider." + key);
		for (String candidate : candidates) {
			if (!candidate.isEmpty() && I18n.exists(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private static Component settingTooltip(String path, JsonElement value, String translationKey) {
		MutableComponent tooltip;
		if (translationKey != null && I18n.exists(translationKey + ".tooltip")) {
			tooltip = Component.translatable(translationKey + ".tooltip");
		} else {
			tooltip = Component.translatableWithFallback(VIEW + "setting_help", "Read-only active preset setting");
		}
		return tooltip
			.append("\n")
			.append(Component.literal(path + " = " + compact(value, 120)).withStyle(ChatFormatting.DARK_GRAY))
			.append("\n")
			.append(Component.translatableWithFallback(VIEW + "copy_setting", "Click to copy this setting")
				.withStyle(ChatFormatting.AQUA));
	}

	private static Component displayValue(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return Component.literal("null").withStyle(ChatFormatting.GRAY);
		}
		if (value.isJsonPrimitive()) {
			if (value.getAsJsonPrimitive().isBoolean()) {
				String key = value.getAsBoolean() ? FTFTranslationKeys.GUI_BUTTON_TRUE : FTFTranslationKeys.GUI_BUTTON_FALSE;
				return Component.translatable(key).withStyle(ChatFormatting.WHITE);
			}
			if (value.getAsJsonPrimitive().isString()) {
				String text = value.getAsString();
				if (text.matches("[A-Z][A-Z0-9_]*")) {
					text = friendlyName(text.toLowerCase(Locale.ROOT));
				}
				return Component.literal(text.isEmpty() ? "—" : text).withStyle(ChatFormatting.WHITE);
			}
		}
		return Component.literal(compact(value, 80)).withStyle(ChatFormatting.WHITE);
	}

	private static Component categoryTitle(String key) {
		String translation = CATEGORY_TITLES.get(key);
		return translation == null
			? Component.literal(friendlyName(key))
			: Component.translatable(translation);
	}

	private static Component sectionTitle(String category, String key) {
		if (category.equals("surface") && key.equals("erosion")) {
			return Component.translatable(FTFTranslationKeys.GUI_LABEL_SCREE_THRESHOLDS);
		}
		String translation = SECTION_TITLES.get(key);
		return translation == null
			? Component.literal(friendlyName(key))
			: Component.translatable(translation);
	}

	private static String copyPair(String path, JsonElement value) {
		JsonObject pair = new JsonObject();
		pair.add(lastSegment(path), value.deepCopy());
		return PRETTY_JSON.toJson(pair);
	}

	private static String compact(JsonElement value, int maxLength) {
		String text = value.toString();
		return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
	}

	private static String friendlyName(String key) {
		String spaced = key.replace('_', ' ').replace('.', ' ');
		StringBuilder result = new StringBuilder(spaced.length() + 8);
		for (int i = 0; i < spaced.length(); i++) {
			char current = spaced.charAt(i);
			if (i > 0 && Character.isUpperCase(current) && spaced.charAt(i - 1) != ' ') {
				result.append(' ');
			}
			result.append(current);
		}
		if (result.isEmpty()) {
			return "";
		}
		result.setCharAt(0, Character.toUpperCase(result.charAt(0)));
		return result.toString();
	}

	private static String capitalize(String value) {
		return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	private static String snakeCase(String value) {
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char current = value.charAt(i);
			if (i > 0 && Character.isUpperCase(current)) {
				result.append('_');
			}
			result.append(Character.toLowerCase(current));
		}
		return result.toString();
	}

	private static String lastSegment(String path) {
		return path.substring(path.lastIndexOf('.') + 1);
	}

	private static List<String> orderedKeys(JsonObject object, List<String> preferredOrder) {
		return orderedKeys(object.keySet(), preferredOrder);
	}

	private static List<String> orderedKeys(Map<String, ?> object, List<String> preferredOrder) {
		return orderedKeys(object.keySet(), preferredOrder);
	}

	private static List<String> orderedKeys(Iterable<String> keys, List<String> preferredOrder) {
		Map<String, Integer> positions = new LinkedHashMap<>();
		for (int index = 0; index < preferredOrder.size(); index++) {
			positions.put(preferredOrder.get(index), index);
		}
		List<String> ordered = new ArrayList<>();
		keys.forEach(ordered::add);
		ordered.sort(Comparator
			.comparingInt((String key) -> positions.getOrDefault(key, Integer.MAX_VALUE))
			.thenComparing(String::compareTo));
		return ordered;
	}

	record Category(String key, Component title, List<Section> sections) {
	}

	record Section(String key, Component title, List<Setting> settings) {
	}

	record Setting(String path, Component label, Component value, String copiedText, Component tooltip) {
		Component message() {
			return Component.empty()
				.append(this.label)
				.append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
				.append(this.value);
		}
	}
}
