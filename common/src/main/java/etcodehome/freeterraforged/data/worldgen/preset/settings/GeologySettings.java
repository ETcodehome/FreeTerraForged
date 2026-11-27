package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Objects;

/** Controls decorative rock structure. Fault throw is in world blocks. */
public record GeologySettings(float faultThrow, boolean intrusions, boolean unconformities, Layers layers) {
    public static final GeologySettings DEFAULT = new GeologySettings(24, true, true);
    public static final GeologySettings DISABLED = new GeologySettings(0, false, false);
    public static final Codec<GeologySettings> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.floatRange(0, 96).optionalFieldOf("faultThrow", DEFAULT.faultThrow).forGetter(GeologySettings::faultThrow),
        Codec.BOOL.optionalFieldOf("intrusions", DEFAULT.intrusions).forGetter(GeologySettings::intrusions),
        Codec.BOOL.optionalFieldOf("unconformities", DEFAULT.unconformities).forGetter(GeologySettings::unconformities),
        Layers.CODEC.optionalFieldOf("layers", Layers.DEFAULT).forGetter(GeologySettings::layers)
    ).apply(i, GeologySettings::new));

    public GeologySettings {
        if (!Float.isFinite(faultThrow) || faultThrow < 0 || faultThrow > 96) {
            throw new IllegalArgumentException("Invalid fault throw: " + faultThrow);
        }
        Objects.requireNonNull(layers, "layers");
    }

    public GeologySettings(float faultThrow, boolean intrusions, boolean unconformities) {
        this(faultThrow, intrusions, unconformities, Layers.DEFAULT);
    }

    public GeologySettings withFaultThrow(float value) {
        return new GeologySettings(value, intrusions, unconformities, layers);
    }

    public GeologySettings withIntrusions(boolean value) {
        return new GeologySettings(faultThrow, value, unconformities, layers);
    }

    public GeologySettings withUnconformities(boolean value) {
        return new GeologySettings(faultThrow, intrusions, value, layers);
    }

    public GeologySettings withLayers(Layers value) {
        return new GeologySettings(faultThrow, intrusions, unconformities, value);
    }

    /** Thicknesses are in blocks, except for the scale applied to all host beds. */
    public record Layers(float bedThicknessScale, float contrastFrequency, float contrastThickness,
                         float gravelThickness, float lensThickness) {
        public static final float MIN_BED_SCALE = 0.5F;
        public static final float MAX_BED_SCALE = 3.0F;
        public static final float MAX_CONTRAST_THICKNESS = 6.0F;
        public static final float MAX_GRAVEL_THICKNESS = 4.0F;
        public static final float MAX_LENS_THICKNESS = 6.0F;
        public static final Layers DEFAULT = new Layers(1.0F, 0.25F, 2.0F, 1.0F, 2.0F);
        public static final Codec<Layers> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.floatRange(MIN_BED_SCALE, MAX_BED_SCALE).optionalFieldOf("bedThicknessScale", DEFAULT.bedThicknessScale).forGetter(Layers::bedThicknessScale),
            Codec.floatRange(0, 1).optionalFieldOf("contrastFrequency", DEFAULT.contrastFrequency).forGetter(Layers::contrastFrequency),
            Codec.floatRange(0, MAX_CONTRAST_THICKNESS).optionalFieldOf("contrastThickness", DEFAULT.contrastThickness).forGetter(Layers::contrastThickness),
            Codec.floatRange(0, MAX_GRAVEL_THICKNESS).optionalFieldOf("gravelThickness", DEFAULT.gravelThickness).forGetter(Layers::gravelThickness),
            Codec.floatRange(0, MAX_LENS_THICKNESS).optionalFieldOf("lensThickness", DEFAULT.lensThickness).forGetter(Layers::lensThickness)
        ).apply(i, Layers::new));

        public Layers {
            checkRange(bedThicknessScale, MIN_BED_SCALE, MAX_BED_SCALE, "bed thickness scale");
            checkRange(contrastFrequency, 0, 1, "contrast frequency");
            checkRange(contrastThickness, 0, MAX_CONTRAST_THICKNESS, "contrast thickness");
            checkRange(gravelThickness, 0, MAX_GRAVEL_THICKNESS, "gravel thickness");
            checkRange(lensThickness, 0, MAX_LENS_THICKNESS, "lens thickness");
        }

        private static void checkRange(float value, float min, float max, String name) {
            if (!Float.isFinite(value) || value < min || value > max) {
                throw new IllegalArgumentException("Invalid geological " + name + ": " + value);
            }
        }

        public Layers withBedThicknessScale(float value) {
            return new Layers(value, contrastFrequency, contrastThickness, gravelThickness, lensThickness);
        }

        public Layers withContrastFrequency(float value) {
            return new Layers(bedThicknessScale, value, contrastThickness, gravelThickness, lensThickness);
        }

        public Layers withContrastThickness(float value) {
            return new Layers(bedThicknessScale, contrastFrequency, value, gravelThickness, lensThickness);
        }

        public Layers withGravelThickness(float value) {
            return new Layers(bedThicknessScale, contrastFrequency, contrastThickness, value, lensThickness);
        }

        public Layers withLensThickness(float value) {
            return new Layers(bedThicknessScale, contrastFrequency, contrastThickness, gravelThickness, value);
        }
    }
}
