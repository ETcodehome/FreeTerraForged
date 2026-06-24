package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class IslandSettings {

	public static final SettingToken<Boolean> ENABLE_ISLANDS
			= PresetManager.registerToggle(
			"island.enableArchipelago",
			true,
			"reterraforged.gui.button.enableArchipelago");

	public static final SettingToken<Float> DENSITY
			= PresetManager.registerFloat(
			"island.density",
			0.655F,
			0.0F,
			1.0F,
			"reterraforged.gui.button.islandDensity");

	public static final SettingToken<Float> SIZE
			= PresetManager.registerFloat(
			"island.size",
			185.0F,
			50.0F,
			500.0F,
			"reterraforged.gui.button.islandSize");

	public static final SettingToken<Float> HEIGHT
			= PresetManager.registerFloat(
			"island.height",
			1.0F,
			0.1F,
			3.0F,
			"reterraforged.gui.button.islandHeight");

	public static final SettingToken<Float> BASE_SCALE
			= PresetManager.registerFloat(
			"island.baseScale",
			1.0F,
			0.1F,
			2.0F,
			"reterraforged.gui.button.islandBaseScale");

	public static final SettingToken<Float> VERTICAL_SCALE
			= PresetManager.registerFloat(
			"island.verticalScale",
			2.0F,
			0.1F,
			3.0F,
			"reterraforged.gui.button.islandVerticalScale");

	public static final SettingToken<Float> HORIZONTAL_SCALE
			= PresetManager.registerFloat(
			"island.horizontalScale",
			0.250F,
			0.1F,
			3.0F,
			"reterraforged.gui.button.islandHorizontalScale");

	public static final SettingToken<Float> MOUNTAIN_CHANCE
			= PresetManager.registerFloat(
			"island.mountainChance",
			0.6F,
			0.0F,
			1.0F,
			"reterraforged.gui.button.mountainChance");

	public static final SettingToken<Float> VOLCANO_CHANCE
			= PresetManager.registerFloat(
			"island.volcanoChance",
			0.5F,
			0.0F,
			1.0F,
			"reterraforged.gui.button.volcanoChance");

	public static final SettingToken<Float> OFFSHORE_DEPTH
			= PresetManager.registerFloat(
			"island.offshoreDepth",
			0.1F,
			0.1F,
			1.0F,
			"reterraforged.gui.button.offshoreDepth");

	public static final SettingToken<Float> BEACH_WIDTH
			= PresetManager.registerFloat(
			"island.beachWidth",
			0.125F,
			0.05F,
			0.5F,
			"reterraforged.gui.button.beachWidth");

	public static final SettingToken<Float> BEACH_COVERAGE
			= PresetManager.registerFloat(
			"island.beachCoverage",
			0.125F,
			0.0F,
			1.0F,
			"reterraforged.gui.button.beachCoverage");

	public static final SettingToken<Float> VOLCANISM_SCALE
			= PresetManager.registerFloat(
			"island.volcanismScale",
			0.5F,
			0.0F,
			1.0F,
			"reterraforged.gui.button.volcanismScale");

	public static final SettingToken<Float> MOUNTAIN_SCALE
			= PresetManager.registerFloat(
			"island.mountainScale",
			0.5F,
			0.0F,
			1.0F,
			"reterraforged.gui.button.mountainScale");

	
	public static final Codec<IslandSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.BOOL.fieldOf("enableArchipelago").forGetter((o) -> o.enableArchipelago),
		Codec.FLOAT.fieldOf("islandDensity").forGetter((o) -> o.islandDensity),
		Codec.FLOAT.fieldOf("islandSize").forGetter((o) -> o.islandSize),
		Codec.FLOAT.fieldOf("islandHeight").forGetter((o) -> o.islandHeight),
		Codec.FLOAT.fieldOf("islandBaseScale").forGetter((o) -> o.islandBaseScale),
		Codec.FLOAT.fieldOf("islandVerticalScale").forGetter((o) -> o.islandVerticalScale),
		Codec.FLOAT.fieldOf("islandHorizontalScale").forGetter((o) -> o.islandHorizontalScale),
		Codec.FLOAT.fieldOf("mountainChance").forGetter((o) -> o.mountainChance),
		Codec.FLOAT.fieldOf("volcanoChance").forGetter((o) -> o.volcanoChance),
		Codec.FLOAT.fieldOf("offshoreDepth").forGetter((o) -> o.offshoreDepth),
		Codec.FLOAT.fieldOf("beachWidth").forGetter((o) -> o.beachWidth),
		Codec.FLOAT.fieldOf("beachCoverage").forGetter((o) -> o.beachCoverage),
		Codec.FLOAT.fieldOf("volcanismScale").forGetter((o) -> o.volcanismScale),
		Codec.FLOAT.fieldOf("mountainScale").forGetter((o) -> o.mountainScale)
	).apply(instance, IslandSettings::new));
	
	public boolean enableArchipelago;
	public float islandDensity;
	public float islandSize;
	public float islandHeight;
	public float islandBaseScale;
	public float islandVerticalScale;
	public float islandHorizontalScale;
	public float mountainChance;
	public float mountainScale;
	public float volcanoChance;
	public float volcanismScale;
	public float offshoreDepth;
	public float beachWidth;
	public float beachCoverage;
	
	public IslandSettings(boolean enableArchipelago, float islandDensity, float islandSize, float islandHeight, float islandBaseScale, float islandVerticalScale, float islandHorizontalScale, float mountainChance, float volcanoChance, float offshoreDepth, float beachWidth, float beachCoverage, float volcanismScale, float mountainScale) {
		this.enableArchipelago = enableArchipelago;
		this.islandDensity = islandDensity;
		this.islandSize = islandSize;
		this.islandHeight = islandHeight;
		this.islandBaseScale = islandBaseScale;
		this.islandVerticalScale = islandVerticalScale;
		this.islandHorizontalScale = islandHorizontalScale;
		this.mountainChance = mountainChance;
		this.volcanoChance = volcanoChance;
		this.offshoreDepth = offshoreDepth;
		this.beachWidth = beachWidth;
		this.beachCoverage = beachCoverage;
		this.mountainScale = mountainScale;
		this.volcanismScale = volcanismScale;
	}
	
	public IslandSettings copy() {
		return new IslandSettings(
				this.enableArchipelago,
				this.islandDensity,
				this.islandSize,
				this.islandHeight,
				this.islandBaseScale,
				this.islandVerticalScale,
				this.islandHorizontalScale,
				this.mountainChance,
				this.volcanoChance,
				this.offshoreDepth,
				this.beachWidth,
				this.beachCoverage,
				this.mountainScale,
				this.volcanismScale);
	}
	
	public static IslandSettings makeDefault() {
		return new IslandSettings( true,
				0.655F,
				185.0F,
				1.0F,
				1.0F,
				2.0F,
				0.250F,
				0.6F,
				0.5F,
				0.1F,
				0.125F,
				0.125F,
				0.5F,
				0.5F);
	}

	public static void legacyMigration(JsonObject json) {
		if (json.has("island") && json.get("island").isJsonObject()) {
			JsonObject legacyIsland = json.getAsJsonObject("island");
			if (legacyIsland.has("enableArchipelago")) json.addProperty("island.enableArchipelago", legacyIsland.get("enableArchipelago").getAsBoolean());
			if (legacyIsland.has("islandDensity")) json.addProperty("island.density", legacyIsland.get("islandDensity").getAsFloat());
			if (legacyIsland.has("islandSize")) json.addProperty("island.size", legacyIsland.get("islandSize").getAsFloat());
			if (legacyIsland.has("islandHeight")) json.addProperty("island.height", legacyIsland.get("islandHeight").getAsFloat());
			if (legacyIsland.has("islandBaseScale")) json.addProperty("island.baseScale", legacyIsland.get("islandBaseScale").getAsFloat());
			if (legacyIsland.has("islandVerticalScale")) json.addProperty("island.verticalScale", legacyIsland.get("islandVerticalScale").getAsFloat());
			if (legacyIsland.has("islandHorizontalScale")) json.addProperty("island.horizontalScale", legacyIsland.get("islandHorizontalScale").getAsFloat());
			if (legacyIsland.has("mountainChance")) json.addProperty("island.mountainChance", legacyIsland.get("mountainChance").getAsFloat());
			if (legacyIsland.has("volcanoChance")) json.addProperty("island.volcanoChance", legacyIsland.get("volcanoChance").getAsFloat());
			if (legacyIsland.has("offshoreDepth")) json.addProperty("island.offshoreDepth", legacyIsland.get("offshoreDepth").getAsFloat());
			if (legacyIsland.has("beachWidth")) json.addProperty("island.beachWidth", legacyIsland.get("beachWidth").getAsFloat());
			if (legacyIsland.has("beachCoverage")) json.addProperty("island.beachCoverage", legacyIsland.get("beachCoverage").getAsFloat());
			if (legacyIsland.has("volcanismScale")) json.addProperty("island.volcanismScale", legacyIsland.get("volcanismScale").getAsFloat());
			if (legacyIsland.has("mountainScale")) json.addProperty("island.mountainScale", legacyIsland.get("mountainScale").getAsFloat());
		}
	}
}