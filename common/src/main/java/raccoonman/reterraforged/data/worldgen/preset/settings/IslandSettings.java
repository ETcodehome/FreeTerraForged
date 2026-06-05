package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class IslandSettings {
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
		Codec.FLOAT.fieldOf("beachCoverage").forGetter((o) -> o.beachCoverage)
	).apply(instance, IslandSettings::new));
	
	public boolean enableArchipelago;
	public float islandDensity;
	public float islandSize;
	public float islandHeight;
	public float islandBaseScale;
	public float islandVerticalScale;
	public float islandHorizontalScale;
	public float mountainChance;
	public float volcanoChance;
	public float offshoreDepth;
	public float beachWidth;
	public float beachCoverage;
	
	public IslandSettings(boolean enableArchipelago, float islandDensity, float islandSize, float islandHeight, float islandBaseScale, float islandVerticalScale, float islandHorizontalScale, float mountainChance, float volcanoChance, float offshoreDepth, float beachWidth, float beachCoverage) {
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
	}
	
	public IslandSettings copy() {
		return new IslandSettings(this.enableArchipelago, this.islandDensity, this.islandSize, this.islandHeight, this.islandBaseScale, this.islandVerticalScale, this.islandHorizontalScale, this.mountainChance, this.volcanoChance, this.offshoreDepth, this.beachWidth, this.beachCoverage);
	}
	
	public static IslandSettings makeDefault() {
		return new IslandSettings(false, 0.5F, 200.0F, 0.5F, 0.3F, 1.0F, 1.0F, 0.3F, 0.1F, 0.5F, 0.15F, 0.3F);
	}
}