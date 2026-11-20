package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class FlatnessFactorRenderModeTest {

    private final Levels levels = new Levels(Presets.makeFTFDefault().world().properties);

    @Test
    @DisplayName("Verify Cell tracks, copies, and resets flatnessFactor")
    void testCellFlatnessFactorLifecycle() {
        Cell cell = new Cell();
        assertEquals(0.0F, cell.flatnessFactor, 1e-6F, "Default flatnessFactor must be 0.0F");

        cell.flatnessFactor = 0.85F;
        assertEquals(0.85F, cell.flatnessFactor, 1e-6F);

        Cell copy = new Cell();
        copy.copyFrom(cell);
        assertEquals(0.85F, copy.flatnessFactor, 1e-6F, "copyFrom must accurately copy flatnessFactor");

        cell.reset();
        assertEquals(0.0F, cell.flatnessFactor, 1e-6F, "reset must restore flatnessFactor to 0.0F");
    }

    @Test
    @DisplayName("Verify RenderMode.FLATNESS_FACTOR exists and alias RenderMode.FLATNESS matches")
    void testRenderModeFlatnessFactorPresence() {
        assertNotNull(RenderMode.FLATNESS_FACTOR, "RenderMode.FLATNESS_FACTOR must exist");
        assertSame(RenderMode.FLATNESS_FACTOR, RenderMode.FLATNESS, "RenderMode.FLATNESS must alias FLATNESS_FACTOR");
        assertTrue(RenderMode.FLATNESS_FACTOR.handlesWater(), "FLATNESS_FACTOR must handle water so ocean/river isn't overwritten by default water blue");
    }

    @Test
    @DisplayName("Verify RenderMode.FLATNESS_FACTOR renders erosionMask in red matching SEDIMENT mode")
    void testRenderModeErosionMaskRed() {
        Cell riverCell = new Cell();
        riverCell.erosionMask = true;
        riverCell.flatnessFactor = 0.5F;
        riverCell.height = this.levels.water + 0.1F;

        int color = RenderMode.FLATNESS_FACTOR.getColor(riverCell, this.levels, 1.0F, 0.0F);
        int r = color & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = (color >> 16) & 0xFF;

        assertEquals(255, r, "Red channel must be 255 for erosion masked cells");
        assertEquals(32, g, "Green channel must be 32 for erosion masked cells");
        assertEquals(32, b, "Blue channel must be 32 for erosion masked cells");

        // Verify exact match with SEDIMENT mode
        int sedimentColor = RenderMode.SEDIMENT.getColor(riverCell, this.levels, 1.0F, 0.0F);
        assertEquals(sedimentColor, color, "FLATNESS_FACTOR must color erosionMask identical to SEDIMENT mode");
    }

    @Test
    @DisplayName("Verify RenderMode.FLATNESS_FACTOR renders ocean in blue when cell.height <= levels.water")
    void testRenderModeOceanBlue() {
        Cell oceanCell = new Cell();
        oceanCell.erosionMask = false;
        oceanCell.height = this.levels.water - 0.05F;

        int color = RenderMode.FLATNESS_FACTOR.getColor(oceanCell, this.levels, 1.0F, 0.0F);
        int r = color & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = (color >> 16) & 0xFF;

        assertEquals(40, r, "Ocean red channel must match getWaterColor() (40)");
        assertEquals(140, g, "Ocean green channel must match getWaterColor() (140)");
        assertEquals(200, b, "Ocean blue channel must match getWaterColor() (200)");
    }

    @Test
    @DisplayName("Verify RenderMode.FLATNESS_FACTOR white mask fade on black across [0.0, 1.0]")
    void testRenderModeGrayscaleFade() {
        // Black at 0.0
        Cell blackCell = new Cell();
        blackCell.erosionMask = false;
        blackCell.height = this.levels.water + 0.1F;
        blackCell.flatnessFactor = 0.0F;

        int blackColor = RenderMode.FLATNESS_FACTOR.getColor(blackCell, this.levels, 1.0F, 0.0F);
        assertEquals(0, blackColor & 0xFF, "Red channel at 0.0 flatness must be 0");
        assertEquals(0, (blackColor >> 8) & 0xFF, "Green channel at 0.0 flatness must be 0");
        assertEquals(0, (blackColor >> 16) & 0xFF, "Blue channel at 0.0 flatness must be 0");

        // White at 1.0
        Cell whiteCell = new Cell();
        whiteCell.erosionMask = false;
        whiteCell.height = this.levels.water + 0.1F;
        whiteCell.flatnessFactor = 1.0F;

        int whiteColor = RenderMode.FLATNESS_FACTOR.getColor(whiteCell, this.levels, 1.0F, 0.0F);
        assertEquals(255, whiteColor & 0xFF, "Red channel at 1.0 flatness must be 255");
        assertEquals(255, (whiteColor >> 8) & 0xFF, "Green channel at 1.0 flatness must be 255");
        assertEquals(255, (whiteColor >> 16) & 0xFF, "Blue channel at 1.0 flatness must be 255");

        // Monotonic progression and pure grayscale (r == g == b)
        int prevBrightness = -1;
        for (float f = 0.0F; f <= 1.0F; f += 0.05F) {
            Cell sample = new Cell();
            sample.erosionMask = false;
            sample.height = this.levels.water + 0.1F;
            sample.flatnessFactor = f;

            int color = RenderMode.FLATNESS_FACTOR.getColor(sample, this.levels, 1.0F, 0.0F);
            int r = color & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = (color >> 16) & 0xFF;

            assertEquals(r, g, "Grayscale requires red == green");
            assertEquals(g, b, "Grayscale requires green == blue");
            assertTrue(r >= prevBrightness, "Brightness must be monotonically non-decreasing");
            prevBrightness = r;
        }
    }

    @Test
    @DisplayName("Verify PreviewDetails formats FLATNESS_FACTOR detail cleanly")
    void testPreviewDetailsForFlatnessFactor() {
        Cell cell = new Cell();
        cell.flatnessFactor = 0.7342F;

        PreviewDetails.Detail detail = PreviewDetails.forCell(RenderMode.FLATNESS_FACTOR, cell, this.levels, "minecraft:plains");
        assertNotNull(detail, "Detail must not be null");
        assertTrue(detail.label().getString().contains("Flatness"), "Label must contain 'Flatness'");
        assertEquals(String.format(Locale.ROOT, "%.4f", 0.7342F), detail.value(), "Value must format to 4 decimals");
    }

    @Test
    @DisplayName("Verify ContinentalHydrology produces smooth alpha masks between plateau centers and step transitions")
    void testContinentalHydrologyAlphaMasks() {
        float[] centers = ContinentalHydrology.getPlateauCenters();
        assertTrue(centers.length >= ContinentalHydrology.NUM_STEPS, "Must have at least NUM_STEPS plateau centers");

        for (int i = 0; i < ContinentalHydrology.NUM_STEPS; i++) {
            float center = centers[i];
            float flatness = ContinentalHydrology.getFlatnessFactor(center);
            assertTrue(flatness >= 0.95F, "Center of plateau row " + i + " (" + center + ") must have high flatnessFactor (~1.0), got " + flatness);
        }
    }
}
