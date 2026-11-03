package etcodehome.freeterraforged.world.worldgen.cell.continent.uplift;

import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoronoiCentroidPeakTest {

    @Test
    void testVoronoiCentroidGeometry() {
        int seed = 12345;
        float jitter = 0.8F;
        float frequency = 1.0F / 4000.0F;
        PlateUpliftBridge bridge = new PlateUpliftBridge(null, seed, jitter, frequency, 54321, 0.0F, 0.5F, 0.75F);

        for (int cy = -2; cy <= 2; cy++) {
            for (int cx = -2; cx <= 2; cx++) {
                PlateUpliftBridge.CellVoronoi v = bridge.getVoronoi(cx, cy);

                assertEquals(cx, v.cellX);
                assertEquals(cy, v.cellY);
                assertTrue(v.edgeCount >= 3, "Expected at least 3 Voronoi edges for cell (" + cx + "," + cy + ")");
                assertTrue(Float.isFinite(v.centroidX), "Centroid X must be finite");
                assertTrue(Float.isFinite(v.centroidY), "Centroid Y must be finite");

                for (int i = 0; i < v.edgeCount; i++) {
                    assertTrue(v.len[i] > 0.001F, "Edge Delaunay length must be positive");
                    assertTrue(v.halfLen[i] > 0.0F, "Edge halfLen must be positive");
                    assertTrue(v.hc[i] > 0.0F, "Centroid distance to edge line must be strictly positive");
                }
            }
        }
    }

    @Test
    void testUpliftPeakAtCentroid() {
        int seed = 42;
        float jitter = 0.8F;
        float frequency = 1.0F / 4000.0F;

        // Bridge with no tectonic uplift (pure unbridged Voronoi pyramid)
        PlateUpliftBridge bridge = new PlateUpliftBridge(null, seed, jitter, frequency, 999, 0.0F, 0.5F, 0.75F);

        for (int cy = -1; cy <= 1; cy++) {
            for (int cx = -1; cx <= 1; cx++) {
                PlateUpliftBridge.CellVoronoi v = bridge.getVoronoi(cx, cy);

                // Convert centroid back to world block coordinates
                float worldCentroidX = v.centroidX / frequency;
                float worldCentroidY = v.centroidY / frequency;

                float gradientAtCentroid = bridge.computeSmoothVoronoiGradient(null, worldCentroidX, worldCentroidY);
                assertTrue(gradientAtCentroid >= 0.99F, "Gradient at Voronoi centroid peak must be ~1.0, was " + gradientAtCentroid);

                // Sample outward along 8 radial directions and check monotonic descent
                for (int angleDeg = 0; angleDeg < 360; angleDeg += 45) {
                    float rad = (float) Math.toRadians(angleDeg);
                    float dirX = NoiseUtil.cos(rad);
                    float dirY = NoiseUtil.sin(rad);

                    float prevVal = 1.0F;
                    for (int step = 1; step <= 5; step++) {
                        float dist = step * 200.0F;
                        float sampleX = worldCentroidX + dirX * dist;
                        float sampleY = worldCentroidY + dirY * dist;

                        float val = bridge.computeSmoothVoronoiGradient(null, sampleX, sampleY);
                        assertTrue(val <= prevVal + 0.01F,
                                "Uplift gradient should monotonically descend from centroid peak: step=" + step + " val=" + val + " prev=" + prevVal);
                        prevVal = val;
                    }
                }
            }
        }
    }

    @Test
    void testIsSectorTooThin() {
        // Sector width < 20 degrees should be thin
        float dPerp = 1.0F;
        float loThin = -0.05F;
        float hiThin = 0.05F;
        assertTrue(PlateUpliftBridge.isSectorTooThin(loThin, hiThin, dPerp, 0.0F), "Narrow 5-degree sector must be flagged thin");

        // Sector width > 40 degrees should not be thin
        float loWide = -0.5F;
        float hiWide = 0.5F;
        assertFalse(PlateUpliftBridge.isSectorTooThin(loWide, hiWide, dPerp, 0.0F), "Wide 50-degree sector must not be thin");
    }
}
