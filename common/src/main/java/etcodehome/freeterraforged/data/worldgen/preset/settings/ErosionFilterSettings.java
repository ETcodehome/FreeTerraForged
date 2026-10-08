package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class ErosionFilterSettings {

    public float scale;
    public static final GenericFloatSetting scaleSetting = new GenericFloatSetting(
            "erosionFilter.scale",
            "gui.slider.erosionFilterScale",
            0.01F,
            4.0F,
            1.13F
    );

    public float strengthMultiplier;
    public static final GenericFloatSetting strengthMultiplierSetting = new GenericFloatSetting(
            "erosionFilter.strengthMultiplier",
            "gui.slider.erosionFilterStrengthMultiplier",
            0.001F,
            1.0F,
            0.001F
    );

    public float gullySharpness;
    public static final GenericFloatSetting gullySharpnessSetting = new GenericFloatSetting(
            "erosionFilter.gullySharpness",
            "gui.slider.erosionFilterGullySharpness",
            0.001F,
            1.0F,
            0.001F
    );

    public float gullySlopeAdhesion;
    public static final GenericFloatSetting gullySlopeAdhesionSetting = new GenericFloatSetting(
            "erosionFilter.gullySlopeAdhesion",
            "gui.slider.erosionFilterGullySlopeAdhesion",
            0.01F,
            3.0F,
            0.01F
    );

    public int flowOctaves;
    public static final GenericIntSetting flowOctavesSetting = new GenericIntSetting(
            "erosionFilter.gullyFlowOctaves",
            "gui.slider.erosionFilterFlowOctaves",
            1,
            8,
            4
    );

    public float lacunarity;
    public static final GenericFloatSetting lacunaritySetting = new GenericFloatSetting(
            "erosionFilter.lacunarity",
            "gui.slider.erosionFilterLacunarity",
            0.01F,
            1.0F,
            0.01F
    );

    public float gain;
    public static final GenericFloatSetting gainSetting = new GenericFloatSetting(
            "erosionFilter.gain",
            "gui.slider.erosionFilterGain",
            0.01F,
            1.0F,
            0.01F
    );

    public float phacelleScale;
    public static final GenericFloatSetting phacelleScaleSetting = new GenericFloatSetting(
            "erosionFilter.phacelleScale",
            "gui.slider.erosionFilterPhacelleScale",
            0.01F,
            2.0F,
            0.352F
    );

    public float phacelleOffset;
    public static final GenericFloatSetting phacelleOffsetSetting = new GenericFloatSetting(
            "erosionFilter.phacelleOffset",
            "gui.slider.erosionFilterPhacelleOffset",
            0.0F,
            1.0F,
            0.163F
    );

    public float phacelleNormalization;
    public static final GenericFloatSetting phacelleNormalizationSetting = new GenericFloatSetting(
            "erosionFilter.phacelleNormalization",
            "gui.slider.erosionFilterPhacelleNormalization",
            0.0F,
            1.0F,
            0.0F
    );

    public float slopeOnsetBase;
    public static final GenericFloatSetting slopeOnsetBaseSetting = new GenericFloatSetting(
            "erosionFilter.slopeOnsetBase",
            "gui.slider.erosionFilterSlopeOnsetBase",
            0.0F,
            3.0F,
            0.0F
    );

    public float slopeOnsetRidge;
    public static final GenericFloatSetting slopeOnsetRidgeSetting = new GenericFloatSetting(
            "erosionFilter.slopeOnsetRidge",
            "gui.slider.erosionFilterSlopeOnsetRidge",
            0.0F,
            2.0F,
            0.129F
    );

    public float slopeOnsetOctave;
    public static final GenericFloatSetting slopeOnsetOctaveSetting = new GenericFloatSetting(
            "erosionFilter.slopeOnsetOctave",
            "gui.slider.erosionFilterSlopeOnsetOctave",
            0.01F,
            2.0F,
            0.01F
    );

    public float roundingMin;
    public static final GenericFloatSetting roundingMinSetting = new GenericFloatSetting(
            "erosionFilter.roundingMin",
            "gui.slider.erosionFilterRoundingMin",
            0.01F,
            5.0F,
            2.0F
    );

    public float roundingMax;
    public static final GenericFloatSetting roundingMaxSetting = new GenericFloatSetting(
            "erosionFilter.roundingMax",
            "gui.slider.erosionFilterRoundingMax",
            0.01F,
            5.0F,
            1.00F
    );

    public float roundingDecay;
    public static final GenericFloatSetting roundingDecaySetting = new GenericFloatSetting(
            "erosionFilter.roundingDecay",
            "gui.slider.erosionFilterRoundingDecay",
            0.01F,
            2.0F,
            0.51F
    );

    // Single source of truth for default values
    public static ErosionFilterSettings makeDefault() {
        return new ErosionFilterSettings(
                scaleSetting.defaultValue,
                strengthMultiplierSetting.defaultValue,
                gullySharpnessSetting.defaultValue,
                gullySlopeAdhesionSetting.defaultValue,
                flowOctavesSetting.defaultValue,
                lacunaritySetting.defaultValue,
                gainSetting.defaultValue,
                phacelleScaleSetting.defaultValue,
                phacelleOffsetSetting.defaultValue,
                phacelleNormalizationSetting.defaultValue,
                slopeOnsetBaseSetting.defaultValue,
                slopeOnsetRidgeSetting.defaultValue,
                slopeOnsetOctaveSetting.defaultValue,
                roundingMinSetting.defaultValue,
                roundingMaxSetting.defaultValue,
                roundingDecaySetting.defaultValue
        );
    }

    public ErosionFilterSettings(
            float scale,
            float strengthMultiplier,
            float gullySharpness,
            float gullySlopeAdhesion,
            int flowOctaves,
            float lacunarity,
            float gain,
            float phacelleScale,
            float phacelleOffset,
            float phacelleNormalization,
            float slopeOnsetBase,
            float slopeOnsetRidge,
            float slopeOnsetOctave,
            float roundingMin,
            float roundingMax,
            float roundingDecay)
    {
            this.scale = scale;
            this.strengthMultiplier = strengthMultiplier;
            this.gullySharpness = gullySharpness;
            this.gullySlopeAdhesion = gullySlopeAdhesion;
            this.flowOctaves = flowOctaves;
            this.lacunarity = lacunarity;
            this.gain = gain;
            this.phacelleScale = phacelleScale;
            this.phacelleOffset = phacelleOffset;
            this.phacelleNormalization = phacelleNormalization;
            this.slopeOnsetBase = slopeOnsetBase;
            this.slopeOnsetRidge = slopeOnsetRidge;
            this.slopeOnsetOctave = slopeOnsetOctave;
            this.roundingMin = roundingMin;
            this.roundingMax = roundingMax;
            this.roundingDecay = roundingDecay;
    }

    public static final ErosionFilterSettings DEFAULT = makeDefault();

    public static final Codec<ErosionFilterSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.optionalFieldOf("scale", DEFAULT.scale).forGetter(o -> o.scale),
            Codec.FLOAT.optionalFieldOf("strengthMultiplier", DEFAULT.strengthMultiplier).forGetter(o -> o.strengthMultiplier),
            Codec.FLOAT.optionalFieldOf("gullySharpness", DEFAULT.gullySharpness).forGetter(o -> o.gullySharpness),
            Codec.FLOAT.optionalFieldOf("gullySlopeAdhesion", DEFAULT.gullySlopeAdhesion).forGetter(o -> o.gullySlopeAdhesion),
            Codec.INT.optionalFieldOf("flowOctaves", DEFAULT.flowOctaves).forGetter(o -> o.flowOctaves),
            Codec.FLOAT.optionalFieldOf("lacunarity", DEFAULT.lacunarity).forGetter(o -> o.lacunarity),
            Codec.FLOAT.optionalFieldOf("gain", DEFAULT.gain).forGetter(o -> o.gain),
            Codec.FLOAT.optionalFieldOf("phacelleScale", DEFAULT.phacelleScale).forGetter(o -> o.phacelleScale),
            Codec.FLOAT.optionalFieldOf("phacelleOffset", DEFAULT.phacelleOffset).forGetter(o -> o.phacelleOffset),
            Codec.FLOAT.optionalFieldOf("phacelleNormalization", DEFAULT.phacelleNormalization).forGetter(o -> o.phacelleNormalization),
            Codec.FLOAT.optionalFieldOf("slopeOnsetBase", DEFAULT.slopeOnsetBase).forGetter(o -> o.slopeOnsetBase),
            Codec.FLOAT.optionalFieldOf("slopeOnsetRidge", DEFAULT.slopeOnsetRidge).forGetter(o -> o.slopeOnsetRidge),
            Codec.FLOAT.optionalFieldOf("slopeOnsetOctave", DEFAULT.slopeOnsetOctave).forGetter(o -> o.slopeOnsetOctave),
            Codec.FLOAT.optionalFieldOf("roundingMin", DEFAULT.roundingMin).forGetter(o -> o.roundingMin),
            Codec.FLOAT.optionalFieldOf("roundingMax", DEFAULT.roundingMax).forGetter(o -> o.roundingMax),
            Codec.FLOAT.optionalFieldOf("roundingDecay", DEFAULT.roundingDecay).forGetter(o -> o.roundingDecay)
    ).apply(instance, ErosionFilterSettings::new));

    public ErosionFilterSettings copy() {
        return new ErosionFilterSettings(
                this.scale,
                this.strengthMultiplier,
                this.gullySharpness,
                this.gullySlopeAdhesion,
                this.flowOctaves,
                this.lacunarity,
                this.gain,
                this.phacelleScale,
                this.phacelleOffset,
                this.phacelleNormalization,
                this.slopeOnsetBase,
                this.slopeOnsetRidge,
                this.slopeOnsetOctave,
                this.roundingMin,
                this.roundingMax,
                this.roundingDecay
        );
    }

}
