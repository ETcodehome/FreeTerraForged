package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;

class RenderModeTest {
    @Test
    void deepOceanShadingDarkensBlueWithoutColorUnderflow() {
        Levels levels = new Levels(256, 64, 63);
        Cell cell = new Cell();
        int base = BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("deep_ocean"));

        cell.height = levels.water;
        int surfaceColor = RenderMode.BIOME.getColor(cell, levels, base);
        cell.height = levels.min;
        int deepColor = RenderMode.BIOME.getColor(cell, levels, base);

        int red = deepColor & 0xFF;
        int green = deepColor >>> 8 & 0xFF;
        int blue = deepColor >>> 16 & 0xFF;
        assertTrue(blue > red && blue > green, "deep shading must preserve the ocean's blue hue");
        assertTrue(blue < (surfaceColor >>> 16 & 0xFF), "deeper water should be darker");
    }
}
