package etcodehome.freeterraforged.data.worldgen.preset.settings;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class PresentationSettings {
    public static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    // member settings
    public String presetName;
    public String lastModified;

    // serialization handlers for codec
    private String serializePresetName() { return this.presetName; }
    private String serializeLastModified() { return this.lastModified; }

    // Codec for writing to disk
    public static final Codec<PresentationSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("presetName", "").forGetter(PresentationSettings::serializePresetName),
            Codec.STRING.fieldOf("lastModified").orElse("").forGetter(PresentationSettings::serializeLastModified)
    ).apply(instance, PresentationSettings::new));

    // default constructor
    public PresentationSettings() {
        this("", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
    }

    // value constructor
    public PresentationSettings(String lastModified) {
        this("", lastModified);
    }

    public PresentationSettings(String presetName, String lastModified) {
        this.presetName = presetName != null ? presetName : "";
        this.lastModified = lastModified != null ? lastModified : "";
    }

    // explicit default constructor used by presets
    public static PresentationSettings makeDefault() {
        return new PresentationSettings("", "");
    }

    // Allow settings duplication
    public PresentationSettings copy() {
        return new PresentationSettings(this.presetName, this.lastModified);
    }

    public void setPresetName(String presetName) {
        this.presetName = presetName != null ? presetName : "";
    }

    // Touch utility to refresh the timestamp upon saving or renaming
    public void updateLastModified() {
        this.lastModified = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }
}
