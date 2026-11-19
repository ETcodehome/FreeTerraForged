package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExpandedPreviewScaleTest {
    @Test
    void expandingPreservesMapScaleAndAddsSurroundingTerrain() {
        int regularPixels = 382;
        int fullscreenPixels = 1280;
        int expandedCells = PresetPreviewScreen.expandedGridSize(fullscreenPixels, regularPixels);

        assertEquals(0, expandedCells % 2);
        assertTrue(expandedCells > IPreviewHandler.SIZE);
        double regularPixelsPerCell = (double) regularPixels / IPreviewHandler.SIZE;
        double expandedPixelsPerCell = (double) fullscreenPixels / expandedCells;
        assertEquals(regularPixelsPerCell, expandedPixelsPerCell, 0.005);
    }

    @Test
    void expandingToTheSameSizeKeepsTheOriginalGrid() {
        assertEquals(IPreviewHandler.SIZE, PresetPreviewScreen.expandedGridSize(400, 400));
    }
}
