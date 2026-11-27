package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class MiscellaneousSettings {
	private record StrataSettings(boolean structured, float tilt, float folding, GeologySettings geology) {
		private static final StrataSettings DEFAULT = new StrataSettings(false, 0.06F, 1.0F, GeologySettings.DEFAULT);
		private static final MapCodec<StrataSettings> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("structuredStrata", DEFAULT.structured).forGetter(StrataSettings::structured),
			Codec.floatRange(0, 1.0F).optionalFieldOf("geologicalTilt", DEFAULT.tilt).forGetter(StrataSettings::tilt),
			Codec.floatRange(0, 2.0F).optionalFieldOf("geologicalFolding", DEFAULT.folding).forGetter(StrataSettings::folding),
			GeologySettings.CODEC.optionalFieldOf("geology", DEFAULT.geology).forGetter(StrataSettings::geology)
		).apply(instance, StrataSettings::new));
	}

	public static final Codec<MiscellaneousSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.BOOL.fieldOf("smoothLayerDecorator").forGetter((s) -> s.smoothLayerDecorator),
		Codec.INT.fieldOf("strataRegionSize").forGetter((s) -> s.strataRegionSize),
		Codec.BOOL.fieldOf("strataDecorator").forGetter((s) -> s.strataDecorator),
		Codec.BOOL.fieldOf("oreCompatibleStoneOnly").forGetter((s) -> s.oreCompatibleStoneOnly),
		Codec.BOOL.fieldOf("erosionDecorator").forGetter((s) -> s.screeDecorator),
		Codec.BOOL.fieldOf("plainStoneErosion").forGetter((s) -> s.plainStoneErosion),
		Codec.BOOL.fieldOf("naturalSnowDecorator").forGetter((s) -> s.naturalSnowDecorator),
		Codec.BOOL.fieldOf("customBiomeFeatures").forGetter((s) -> s.customBiomeFeatures),
		Codec.BOOL.fieldOf("vanillaSprings").forGetter((s) -> s.vanillaSprings),
		Codec.BOOL.fieldOf("vanillaLavaLakes").forGetter((s) -> s.vanillaLavaLakes),
		Codec.BOOL.fieldOf("vanillaLavaSprings").forGetter((s) -> s.vanillaLavaSprings),
		Codec.FLOAT.fieldOf("mountainBiomeUsage").forGetter((s) -> s.mountainBiomeUsage),
		Codec.FLOAT.fieldOf("volcanoBiomeUsage").forGetter((s) -> s.volcanoBiomeUsage),
		// Optional so presets and worlds saved before this setting existed decode with default enabled.
		Codec.BOOL.optionalFieldOf("snowCoversSmallPlants", true).forGetter((s) -> s.snowCoversSmallPlants),
		StrataSettings.CODEC.forGetter(MiscellaneousSettings::strataSettings)
	).apply(instance, MiscellaneousSettings::new));
	
	public boolean smoothLayerDecorator;
	public int strataRegionSize;
	public boolean strataDecorator;
	public boolean oreCompatibleStoneOnly;
	public boolean screeDecorator;
	public boolean plainStoneErosion;
	public boolean naturalSnowDecorator;
	public boolean customBiomeFeatures;
	public boolean vanillaSprings;
	public boolean vanillaLavaLakes;
	public boolean vanillaLavaSprings;
    public float mountainBiomeUsage;
    public float volcanoBiomeUsage;
	public boolean snowCoversSmallPlants;
	public boolean structuredStrata = StrataSettings.DEFAULT.structured;
	public float geologicalTilt = StrataSettings.DEFAULT.tilt;
	public float geologicalFolding = StrataSettings.DEFAULT.folding;
	public GeologySettings geology = StrataSettings.DEFAULT.geology;

	private MiscellaneousSettings(boolean smoothLayerDecorator, int strataRegionSize, boolean strataDecorator,
		boolean oreCompatibleStoneOnly, boolean screeDecorator, boolean plainStoneErosion,
		boolean naturalSnowDecorator, boolean customBiomeFeatures, boolean vanillaSprings,
		boolean vanillaLavaLakes, boolean vanillaLavaSprings, float mountainBiomeUsage,
		float volcanoBiomeUsage, boolean snowCoversSmallPlants, StrataSettings strataSettings) {
		this(smoothLayerDecorator, strataRegionSize, strataDecorator, oreCompatibleStoneOnly, screeDecorator,
			plainStoneErosion, naturalSnowDecorator, customBiomeFeatures, vanillaSprings, vanillaLavaLakes,
			vanillaLavaSprings, mountainBiomeUsage, volcanoBiomeUsage, snowCoversSmallPlants);
		this.structuredStrata = strataSettings.structured;
		this.geologicalTilt = strataSettings.tilt;
		this.geologicalFolding = strataSettings.folding;
		this.geology = strataSettings.geology;
	}

	private StrataSettings strataSettings() {
		return new StrataSettings(this.structuredStrata, this.geologicalTilt, this.geologicalFolding, this.geology);
	}
	
	public MiscellaneousSettings(
		boolean smoothLayerDecorator,
		int strataRegionSize,
		boolean strataDecorator,
		boolean oreCompatibleStoneOnly,
		boolean screeDecorator,
		boolean plainStoneErosion,
		boolean naturalSnowDecorator,
		boolean customBiomeFeatures,
		boolean vanillaSprings,
		boolean vanillaLavaLakes,
		boolean vanillaLavaSprings,
	    float mountainBiomeUsage,
	    float volcanoBiomeUsage,
		boolean snowCoversSmallPlants
	) {
		this.smoothLayerDecorator = smoothLayerDecorator;
		this.strataRegionSize = strataRegionSize;
		this.strataDecorator = strataDecorator;
		this.oreCompatibleStoneOnly = oreCompatibleStoneOnly;
		this.screeDecorator = screeDecorator;
		this.plainStoneErosion = plainStoneErosion;
		this.naturalSnowDecorator = naturalSnowDecorator;
		this.customBiomeFeatures = customBiomeFeatures;
		this.vanillaSprings = vanillaSprings;
		this.vanillaLavaLakes = vanillaLavaLakes;
		this.vanillaLavaSprings = vanillaLavaSprings;
		this.mountainBiomeUsage = mountainBiomeUsage;
		this.volcanoBiomeUsage = volcanoBiomeUsage;
		this.snowCoversSmallPlants = snowCoversSmallPlants;
	}

	public MiscellaneousSettings(
		boolean smoothLayerDecorator,
		int strataRegionSize,
		boolean strataDecorator,
		boolean oreCompatibleStoneOnly,
		boolean screeDecorator,
		boolean plainStoneErosion,
		boolean naturalSnowDecorator,
		boolean customBiomeFeatures,
		boolean vanillaSprings,
		boolean vanillaLavaLakes,
		boolean vanillaLavaSprings,
	    float mountainBiomeUsage,
	    float volcanoBiomeUsage
	) {
		this(smoothLayerDecorator, strataRegionSize, strataDecorator, oreCompatibleStoneOnly, screeDecorator, plainStoneErosion, naturalSnowDecorator, customBiomeFeatures, vanillaSprings, vanillaLavaLakes, vanillaLavaSprings, mountainBiomeUsage, volcanoBiomeUsage, true);
	}
	
	public MiscellaneousSettings copy() {
		return new MiscellaneousSettings(this.smoothLayerDecorator, this.strataRegionSize, this.strataDecorator, this.oreCompatibleStoneOnly, this.screeDecorator, this.plainStoneErosion, this.naturalSnowDecorator, this.customBiomeFeatures, this.vanillaSprings, this.vanillaLavaLakes, this.vanillaLavaSprings, this.mountainBiomeUsage, this.volcanoBiomeUsage, this.snowCoversSmallPlants, this.strataSettings());
	}

	public static MiscellaneousSettings makeDefault(){
		return new MiscellaneousSettings(
			true,
			889,
			true,
			true,
			true,
			false,
			true,
			true,
			true,
			false,
			false,
			0.85438144F,
			0.855F,
			true
		);
	}
}
