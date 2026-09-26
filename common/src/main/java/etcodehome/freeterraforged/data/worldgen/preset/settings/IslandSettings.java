package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class IslandSettings {

	// Single source of truth for default values
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
		return new PartA(this.enableArchipelago, this.islandDensity, this.islandSize, this.islandHeight, this.islandBaseScale, this.islandVerticalScale, this.islandHorizontalScale, this.offshoreDepth, this.macroDensityPercentage);
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
	public float volcanismHorizontalScale;
	public float mountainHorizontalScale;
	public float offshoreDepth;
	public float beachWidth;
	public float beachCoverage;
	public float macroDensityPercentage;

	public IslandSettings(boolean enableArchipelago, float islandDensity, float islandSize, float islandHeight, float islandBaseScale, float islandVerticalScale, float islandHorizontalScale, float mountainChance, float volcanoChance, float offshoreDepth, float beachWidth, float beachCoverage, float mountainScale, float volcanismScale, float mountainHorizontalScale, float volcanismHorizontalScale, float macroDensityPercentage) {
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
		this.mountainHorizontalScale = mountainHorizontalScale;
		this.volcanismHorizontalScale = volcanismHorizontalScale;
		this.macroDensityPercentage = macroDensityPercentage;
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
				this.volcanismScale,
				this.mountainHorizontalScale,
				this.volcanismHorizontalScale,
				this.macroDensityPercentage
		);
	}

	public static IslandSettings makeDefault() {
		return new IslandSettings(true,
				0.4F,
				150.0F,
				0.1F,
				0.1F,
				1.5F,
				5.0F,
				0.5F,
				0.5F,
				0.275F,
				0.225F,
				0.6F,
				0.4F,
				0.85F,
				0.7F,
				0.7F,
				1.0F
		);
	}
}