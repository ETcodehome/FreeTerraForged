package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.Locale;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;
import net.minecraft.network.chat.Component;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;

final class PreviewDetails {
    private PreviewDetails() {
    }

    static Detail forCell(RenderMode mode, Cell cell, Levels levels, String biomeId) {
        return switch (mode) {
            case BIOME -> new Detail(
                    Component.translatable(FTFTranslationKeys.GUI_LABEL_PREVIEW_BIOME),
                    biomeId == null ? "" : biomeId
            );
            case TRANSITION_POINTS -> new Detail(Component.literal("Category"), cell.terrain.getCategory().name());
            case TEMPERATURE -> value("Temperature", cell.temperature);
            case MOISTURE -> value("Moisture", cell.moisture);
            case EROSION -> value("Erosion", cell.erosion);
            case GRADIENT -> value("Gradient", cell.gradient);
            case SEDIMENT -> value("Sediment", cell.sediment);
            case WEIRDNESS -> value("Weirdness", cell.weirdness);
            case BIOME_REGION_EDGE -> value("Mask", cell.biomeRegionEdge);
            case BIOME_CELLS -> value("Biome Cell", cell.biomeRegionId);
            case MACRO_NOISE -> value("Macro Noise", cell.macroBiomeId);
            case TERRAIN_REGION -> new Detail(Component.literal("Terrain Type"), cell.terrain.getName());
            case HYPSOMETRIC, TOPOGRAPHY -> new Detail(
                    Component.literal("Elevation"),
                    "Y=" + levels.scale(cell.height) + " H=" + cell.height
            );
            case CONTINENT_UPLIFT -> value("Uplift", cell.waterTable);
            case CONTINENT_EDGE -> new Detail(Component.literal("Continent Edge"), formatContinentEdge(cell.continentEdge));
            case RIVER_ZONE -> new Detail(Component.literal("River Zone"), cell.riverZone.name());
        };
    }

    private static String formatContinentEdge(float edge) {
        String bandName;
        if (edge <= Continentalness.MUSHROOM_FIELDS.max()) {
            bandName = "Mushroom Fields (C0)";
        } else if (edge <= Continentalness.DEEP_OCEAN.max()) {
            bandName = "Deep Ocean (C1)";
        } else if (edge <= Continentalness.OCEAN.max()) {
            bandName = "Ocean (C2)";
        } else if (edge <= Continentalness.COAST.max()) {
            bandName = "Coast (C3)";
        } else if (edge <= Continentalness.NEAR_INLAND.max()) {
            bandName = "Near-Inland (C5)";
        } else if (edge <= Continentalness.MID_INLAND.max()) {
            bandName = "Mid-Inland (C6)";
        } else {
            bandName = "Far-Inland (C7)";
        }
        return String.format(Locale.ROOT, "%.4f (%s)", edge, bandName);
    }

    private static Detail value(String label, float value) {
        return new Detail(Component.literal(label), String.format(Locale.ROOT, "%.4f", value));
    }

    record Detail(Component label, String value) {
    }
}