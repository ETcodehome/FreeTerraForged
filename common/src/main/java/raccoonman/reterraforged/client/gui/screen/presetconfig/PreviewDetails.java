package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Locale;

import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;

final class PreviewDetails {
    private PreviewDetails() {
    }

    static Detail forCell(RenderMode mode, Cell cell, Levels levels, String biomeId) {
        return switch (mode) {
            case BIOME -> new Detail(
                Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_BIOME),
                biomeId == null ? "" : biomeId
            );
            case TRANSITION_POINTS -> new Detail(Component.literal("Category"), cell.terrain.getCategory().name());
            case TEMPERATURE -> value("Temperature", cell.regionTemperature);
            case MOISTURE -> value("Moisture", cell.regionMoisture);
            case BIOME_CELLS -> value("Biome Cell", cell.biomeRegionId);
            case MACRO_NOISE -> value("Macro Noise", cell.macroBiomeId);
            case TERRAIN_REGION -> new Detail(Component.literal("Terrain Type"), cell.terrain.getName());
            case HYPSOMETRIC, TOPOGRAPHY -> new Detail(
                Component.literal("Elevation"),
                "Y=" + levels.scale(cell.height)
            );
            case CONTINENT_UPLIFT -> value("Uplift", cell.waterTable);
            case CONTINENT_EDGE -> value("Continent Edge", cell.continentEdge);
            case RIVER_ZONE -> new Detail(Component.literal("River Zone"), cell.riverZone.name());
        };
    }

    private static Detail value(String label, float value) {
        return new Detail(Component.literal(label), String.format(Locale.ROOT, "%.4f", value));
    }

    record Detail(Component label, String value) {
    }
}
