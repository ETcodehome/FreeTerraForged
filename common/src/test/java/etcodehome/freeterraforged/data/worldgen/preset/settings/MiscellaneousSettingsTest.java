package etcodehome.freeterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class MiscellaneousSettingsTest {
    @Test
    void geologicalStructuresSurviveCopyAndJsonAndValidateTheirRanges() {
        var settings = MiscellaneousSettings.makeDefault();
        settings.geology = new GeologySettings(64, false, true);
        var copy = settings.copy();
        var json = MiscellaneousSettings.CODEC.encodeStart(JsonOps.INSTANCE, copy).getOrThrow().getAsJsonObject();
        assertEquals(settings.geology, MiscellaneousSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().geology);
        settings.geology = GeologySettings.DISABLED;
        assertEquals(64, copy.geology.faultThrow());
        assertEquals(new GeologySettings(48, true, false),
            copy.geology.withFaultThrow(48).withIntrusions(true).withUnconformities(false));
        json.remove("geology");
        assertEquals(GeologySettings.DEFAULT, MiscellaneousSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().geology);
        assertThrows(IllegalArgumentException.class, () -> GeologySettings.DEFAULT.withFaultThrow(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> GeologySettings.DEFAULT.withFaultThrow(100));
    }

    @Test
    void layerControlsSurvivePresetCopyAndOlderJsonUsesOriginalLayering() {
        var settings = MiscellaneousSettings.makeDefault();
        var layers = new GeologySettings.Layers(1.75F, 0.6F, 4.5F, 3.5F, 0.5F);
        settings.geology = settings.geology.withLayers(layers);
        var copy = settings.copy();
        assertEquals(layers, copy.geology.layers());
        var json = MiscellaneousSettings.CODEC.encodeStart(JsonOps.INSTANCE, copy).getOrThrow().getAsJsonObject();
        assertEquals(layers, MiscellaneousSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().geology.layers());
        json.getAsJsonObject("geology").remove("layers");
        assertEquals(GeologySettings.Layers.DEFAULT,
            MiscellaneousSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().geology.layers());
    }

    @Test
    void oreRestrictionAndBedDeformationSurviveCopyAndSerialization() {
        var settings = MiscellaneousSettings.makeDefault();
        settings.oreCompatibleStoneOnly = false;
        settings.snowCoversSmallPlants = false;
        settings.structuredStrata = true;
        settings.geologicalTilt = 1.0F;
        settings.geologicalFolding = 1.5F;
        var copy = settings.copy();
        var json = MiscellaneousSettings.CODEC.encodeStart(JsonOps.INSTANCE, copy).getOrThrow();
        var decoded = MiscellaneousSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertFalse(decoded.oreCompatibleStoneOnly);
        assertFalse(decoded.snowCoversSmallPlants);
        assertTrue(decoded.structuredStrata);
        assertEquals(1.0F, decoded.geologicalTilt);
        assertEquals(1.5F, decoded.geologicalFolding);
    }

    @Test
    void legacyPresetFieldsGetTheNewBedDefaults() {
        var json = MiscellaneousSettings.CODEC.encodeStart(JsonOps.INSTANCE,
            MiscellaneousSettings.makeDefault()).getOrThrow().getAsJsonObject();
        assertFalse(json.has("structuredStrata"));
        var decoded = MiscellaneousSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertFalse(decoded.structuredStrata);
        assertEquals(0.06F, decoded.geologicalTilt);
        assertEquals(1, decoded.geologicalFolding);
    }
}
