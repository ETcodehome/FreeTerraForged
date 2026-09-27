package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class IslandSettings {

	public boolean spawnIslands;
	public static final GenericBooleanSetting spawnIslandsSetting = new GenericBooleanSetting(
			"islands.spawn",
			"gui.button.enableArchipelago",
			true
	);

	public float density;
	public static final GenericFloatSetting densitySetting = new GenericFloatSetting(
			"islands.density",
			"gui.slider.islandDensity",
			0.0F,
			1.0F,
			0.4F
	);

	public float size;
	public static final GenericFloatSetting sizeSetting = new GenericFloatSetting(
			"islands.size",
			"gui.slider.islandSize",
			50.0F,
			500.0F,
			150.0F
	);

	public float height;
	public static final GenericFloatSetting heightSetting = new GenericFloatSetting(
			"islands.height",
			"gui.slider.islandHeight",
			0.1F,
			3.0F,
			0.1F
	);

	public float baseScale;
	public static final GenericFloatSetting baseScaleSetting = new GenericFloatSetting(
			"islands.baseScale",
			"gui.slider.islandBaseScale",
			0.1F,
			2.0F,
			0.1F
	);

	public float verticalScale;
	public static final GenericFloatSetting verticalScaleSetting = new GenericFloatSetting(
			"islands.verticalScale",
			"gui.slider.islandVerticalScale",
			0.1F,
			3.0F,
			1.5F
	);

	public float horizontalScale;
	public static final GenericFloatSetting horizontalScaleSetting = new GenericFloatSetting(
			"islands.horizontalScale",
			"gui.slider.islandHorizontalScale",
			0.1F,
			10.0F,
			5.0F
	);
	public float mountainChance;
	public static final GenericFloatSetting mountainChanceSetting = new GenericFloatSetting(
			"islands.mountainChance",
			"gui.slider.islandMountainChance",
			0.0F,
			1.0F,
			0.5F
	);

	public float mountainScale;
	public static final GenericFloatSetting mountainScaleSetting = new GenericFloatSetting(
			"islands.mountainScale",
			"gui.slider.islandMountainScale",
			0.0F,
			1.0F,
			0.4F
	);

	public float volcanoChance;
	public static final GenericFloatSetting volcanoChanceSetting = new GenericFloatSetting(
			"islands.VolcanoChance",
			"gui.slider.islandMountainChance",
			0.0F,
			1.0F,
			0.5F
	);

	public float volcanismScale;
	public static final GenericFloatSetting volcanismScaleSetting = new GenericFloatSetting(
			"islands.volcanismScale",
			"gui.slider.islandVolcanismScale",
			0.0F,
			1.0F,
			0.85F
	);

	public float volcanismHorizontalScale;
	public static final GenericFloatSetting volcanismHorizontalScaleSetting = new GenericFloatSetting(
			"islands.volcanismHorizontalScale",
			"gui.slider.islandVolcanismHorizontalScale",
			0.1F,
			3.0F,
			0.7F
	);

	public float mountainHorizontalScale;
	public static final GenericFloatSetting mountainHorizontalScaleSetting = new GenericFloatSetting(
			"islands.mountainHorizontalScale",
			"gui.slider.islandMountainHorizontalScale",
			0.1F,
			3.0F,
			0.7F
	);

	public float offshoreDepth;
	public static final GenericFloatSetting offshoreDepthSetting = new GenericFloatSetting(
			"islands.offshoreDepth",
			"gui.slider.islandOffshoreDepth",
			0.1F,
			1.0F,
			0.275F
	);

	public float beachWidth;
	public static final GenericFloatSetting beachWidthSetting = new GenericFloatSetting(
			"islands.beachWidth",
			"gui.slider.islandBeachWidth",
			0.05F,
			0.5F,
			0.225F
	);

	public float beachCoverage;
	public static final GenericFloatSetting beachCoverageSetting = new GenericFloatSetting(
			"islands.beachCoverage",
			"gui.slider.islandBeachCoverage",
			0.0F,
			1.0F,
			0.6F
	);

	public float macroDensityPercentage;
	public static final GenericFloatSetting macroDensityPercentageSetting = new GenericFloatSetting(
			"islands.macroDensityPercentage",
			"gui.slider.islandMacroDensityPercentage",
			0.0F,
			1.0F,
			1.0F
	);

	// Single source of truth for default values
	public static IslandSettings makeDefault() {
		return new IslandSettings(
				spawnIslandsSetting.defaultValue,
				densitySetting.defaultValue,
				sizeSetting.defaultValue,
				heightSetting.defaultValue,
				baseScaleSetting.defaultValue,
				verticalScaleSetting.defaultValue,
				horizontalScaleSetting.defaultValue,
				mountainChanceSetting.defaultValue,
				volcanoChanceSetting.defaultValue,
				offshoreDepthSetting.defaultValue,
				beachWidthSetting.defaultValue,
				beachCoverageSetting.defaultValue,
				mountainScaleSetting.defaultValue,
				volcanismScaleSetting.defaultValue,
				mountainHorizontalScaleSetting.defaultValue,
				volcanismHorizontalScaleSetting.defaultValue,
				macroDensityPercentageSetting.defaultValue
		);
	}
	public static final IslandSettings DEFAULT = makeDefault();
	private static final PartA DEFAULT_A = DEFAULT.toPartA();
	private static final PartB DEFAULT_B = DEFAULT.toPartB();

	// Partial MapCodec A referencing the single default instance
	private static final MapCodec<PartA> PART_A_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.BOOL.optionalFieldOf("enableArchipelago", DEFAULT_A.enableArchipelago()).forGetter(PartA::enableArchipelago),
			Codec.FLOAT.optionalFieldOf("islandDensity", DEFAULT_A.islandDensity()).forGetter(PartA::islandDensity),
			Codec.FLOAT.optionalFieldOf("islandSize", DEFAULT_A.islandSize()).forGetter(PartA::islandSize),
			Codec.FLOAT.optionalFieldOf("islandHeight", DEFAULT_A.islandHeight()).forGetter(PartA::islandHeight),
			Codec.FLOAT.optionalFieldOf("islandBaseScale", DEFAULT_A.islandBaseScale()).forGetter(PartA::islandBaseScale),
			Codec.FLOAT.optionalFieldOf("islandVerticalScale", DEFAULT_A.islandVerticalScale()).forGetter(PartA::islandVerticalScale),
			Codec.FLOAT.optionalFieldOf("islandHorizontalScale", DEFAULT_A.islandHorizontalScale()).forGetter(PartA::islandHorizontalScale),
			Codec.FLOAT.optionalFieldOf("offshoreDepth", DEFAULT_A.offshoreDepth()).forGetter(PartA::offshoreDepth),
			Codec.FLOAT.optionalFieldOf("macroDensityPercentage", DEFAULT_A.macroDensityPercentage()).forGetter(PartA::macroDensityPercentage)
	).apply(i, PartA::new));

	// Partial MapCodec B referencing the single default instance
	private static final MapCodec<PartB> PART_B_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.FLOAT.optionalFieldOf("mountainChance", DEFAULT_B.mountainChance()).forGetter(PartB::mountainChance),
			Codec.FLOAT.optionalFieldOf("volcanoChance", DEFAULT_B.volcanoChance()).forGetter(PartB::volcanoChance),
			Codec.FLOAT.optionalFieldOf("beachWidth", DEFAULT_B.beachWidth()).forGetter(PartB::beachWidth),
			Codec.FLOAT.optionalFieldOf("beachCoverage", DEFAULT_B.beachCoverage()).forGetter(PartB::beachCoverage),
			Codec.FLOAT.optionalFieldOf("volcanismScale", DEFAULT_B.volcanismScale()).forGetter(PartB::volcanismScale),
			Codec.FLOAT.optionalFieldOf("mountainScale", DEFAULT_B.mountainScale()).forGetter(PartB::mountainScale),
			Codec.FLOAT.optionalFieldOf("volcanismHorizontalScale", DEFAULT_B.volcanismHorizontalScale()).forGetter(PartB::volcanismHorizontalScale),
			Codec.FLOAT.optionalFieldOf("mountainHorizontalScale", DEFAULT_B.mountainHorizontalScale()).forGetter(PartB::mountainHorizontalScale)
	).apply(i, PartB::new));

	// Main Codec
	public static final Codec<IslandSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			PART_A_CODEC.forGetter(IslandSettings::toPartA),
			PART_B_CODEC.forGetter(IslandSettings::toPartB)
	).apply(instance, IslandSettings::fromParts));

	// Internal helper records
	private record PartA(boolean enableArchipelago, float islandDensity, float islandSize, float islandHeight, float islandBaseScale, float islandVerticalScale, float islandHorizontalScale, float offshoreDepth, float macroDensityPercentage) {}
	private record PartB(float mountainChance, float volcanoChance, float beachWidth, float beachCoverage, float volcanismScale, float mountainScale, float volcanismHorizontalScale, float mountainHorizontalScale) {}

	private PartA toPartA() {
		return new PartA(this.spawnIslands, this.density, this.size, this.height, this.baseScale, this.verticalScale, this.horizontalScale, this.offshoreDepth, this.macroDensityPercentage);
	}

	private PartB toPartB() {
		return new PartB(this.mountainChance, this.volcanoChance, this.beachWidth, this.beachCoverage, this.volcanismScale, this.mountainScale, this.volcanismHorizontalScale, this.mountainHorizontalScale);
	}

	private static IslandSettings fromParts(PartA a, PartB b) {
		return new IslandSettings(
				a.enableArchipelago, a.islandDensity, a.islandSize, a.islandHeight, a.islandBaseScale, a.islandVerticalScale, a.islandHorizontalScale,
				b.mountainChance, b.volcanoChance, a.offshoreDepth, b.beachWidth, b.beachCoverage,
				b.mountainScale, b.volcanismScale, b.mountainHorizontalScale, b.volcanismHorizontalScale,
				a.macroDensityPercentage
		);
	}

	public IslandSettings(boolean enableArchipelago, float islandDensity, float islandSize, float islandHeight, float islandBaseScale, float islandVerticalScale, float islandHorizontalScale, float mountainChance, float volcanoChance, float offshoreDepth, float beachWidth, float beachCoverage, float mountainScale, float volcanismScale, float mountainHorizontalScale, float volcanismHorizontalScale, float macroDensityPercentage) {
		this.spawnIslands = enableArchipelago;
		this.density = islandDensity;
		this.size = islandSize;
		this.height = islandHeight;
		this.baseScale = islandBaseScale;
		this.verticalScale = islandVerticalScale;
		this.horizontalScale = islandHorizontalScale;
		this.mountainChance = mountainChance;
		this.volcanoChance = volcanoChance;
		this.offshoreDepth = offshoreDepth;
		this.beachWidth = beachWidth;
		this.beachCoverage = beachCoverage;
		this.mountainScale = mountainScale;
		this.volcanismScale = volcanismScale;
		this.mountainHorizontalScale = mountainHorizontalScale;
		this.volcanismHorizontalScale = volcanismHorizontalScale;
		this.macroDensityPercentage = macroDensityPercentage;
	}

	public IslandSettings copy() {
		return new IslandSettings(
				this.spawnIslands,
				this.density,
				this.size,
				this.height,
				this.baseScale,
				this.verticalScale,
				this.horizontalScale,
				this.mountainChance,
				this.volcanoChance,
				this.offshoreDepth,
				this.beachWidth,
				this.beachCoverage,
				this.mountainScale,
				this.volcanismScale,
				this.mountainHorizontalScale,
				this.volcanismHorizontalScale,
				this.macroDensityPercentage
		);
	}

}