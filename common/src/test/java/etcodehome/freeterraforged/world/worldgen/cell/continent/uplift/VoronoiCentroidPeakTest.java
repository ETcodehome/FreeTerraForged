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

    @Test
    void testHighJitterBridgeSymmetry() {
        int seed = 777;
        float frequency = 1.0F / 4000.0F;

        // Test at high jitter levels: 0.85, 0.95, and 1.00
        for (float jitter : new float[]{0.85F, 0.95F, 1.00F}) {
            PlateUpliftBridge.BridgeProvider provider = new PlateUpliftBridge.BridgeProvider() {
                @Override
                public boolean canBridge(int cx, int cy) {
                    return true;
                }

                @Override
                public boolean isBridged(int ax, int ay, int bx, int by) {
                    // Symmetric bridge roll
                    if (ax > bx || (ax == bx && ay > by)) {
                        int t = ax; ax = bx; bx = t;
                        t = ay; ay = by; by = t;
                    }
                    int s = 9999 ^ (bx * 73856093) ^ (by * 19349663);
                    float roll = 0.5F + NoiseUtil.valCoord2D(s, ax, ay) * 0.5F;
                    return roll < 0.75F;
                }

                @Override
                public float getBridgeChance() {
                    return 0.75F;
                }
            };

            PlateUpliftBridge bridge = new PlateUpliftBridge(provider, seed, jitter, frequency, 12345, 0.8F, 0.5F, 0.75F);

            for (int cy = -3; cy <= 3; cy++) {
                for (int cx = -3; cx <= 3; cx++) {
                    PlateUpliftBridge.CellVoronoi vA = bridge.getVoronoi(cx, cy);

                    for (int i = 0; i < vA.edgeCount; i++) {
                        int ncx = vA.ncx[i];
                        int ncy = vA.ncy[i];

                        PlateUpliftBridge.CellVoronoi vB = bridge.getVoronoi(ncx, ncy);
                        int matchIdx = -1;
                        for (int j = 0; j < vB.edgeCount; j++) {
                            if (vB.ncx[j] == cx && vB.ncy[j] == cy) {
                                matchIdx = j;
                                break;
                            }
                        }

                        assertTrue(matchIdx >= 0,
                                "Edge from (" + cx + "," + cy + ") to (" + ncx + "," + ncy + ") must exist inversely in neighbor Voronoi");

                        // Both sides of the shared border must strictly agree on isBridged
                        assertEquals(vA.isBridged[i], vB.isBridged[matchIdx],
                                "isBridged decision must be strictly symmetric between (" + cx + "," + cy + ") and (" + ncx + "," + ncy + ")");

                        // Both sides must strictly agree on tectonic uplift activity
                        assertEquals(vA.isTectonicActive[i], vB.isTectonicActive[matchIdx],
                                "isTectonicActive decision must be strictly symmetric between (" + cx + "," + cy + ") and (" + ncx + "," + ncy + ")");
                    }
                }
            }
        }
    }

    @Test
    void testBoundaryHeightContinuityAtHighJitter() {
        int seed = 4321;
        float frequency = 1.0F / 4000.0F;

        for (float jitter : new float[]{0.85F, 0.95F, 1.00F}) {
            PlateUpliftBridge.BridgeProvider provider = new PlateUpliftBridge.BridgeProvider() {
                @Override
                public boolean canBridge(int cx, int cy) {
                    return true;
                }

                @Override
                public boolean isBridged(int ax, int ay, int bx, int by) {
                    return true; // All neighbors bridged to thoroughly test continuous uplift
                }

                @Override
                public float getBridgeChance() {
                    return 1.0F;
                }
            };

            PlateUpliftBridge bridge = new PlateUpliftBridge(provider, seed, jitter, frequency, 9999, 1.0F, 0.5F, 0.75F);

            for (int cy = -2; cy <= 2; cy++) {
                for (int cx = -2; cx <= 2; cx++) {
                    PlateUpliftBridge.CellVoronoi vA = bridge.getVoronoi(cx, cy);

                    for (int i = 0; i < vA.edgeCount; i++) {
                        if (!vA.isTectonicActive[i]) {
                            continue;
                        }

                        // Sample 5 points along the shared border segment [lo, hi]
                        float lo = vA.lo[i];
                        float hi = vA.hi[i];
                        float span = hi - lo;
                        if (span < 0.05F) {
                            continue;
                        }

                        for (int step = 1; step <= 5; step++) {
                            float t = lo + span * (step / 6.0F);
                            float borderPtX = vA.mx[i] + t * vA.tx[i];
                            float borderPtY = vA.my[i] + t * vA.ty[i];

                            // Sample just inside Cell A and just inside Cell B (normal offset eps = 1e-4)
                            float eps = 1.0e-4F;
                            float sampleAx = (borderPtX - eps * vA.nx[i]) / frequency;
                            float sampleAy = (borderPtY - eps * vA.ny[i]) / frequency;
                            float sampleBx = (borderPtX + eps * vA.nx[i]) / frequency;
                            float sampleBy = (borderPtY + eps * vA.ny[i]) / frequency;

                            float heightA = bridge.computeSmoothVoronoiGradient(null, sampleAx, sampleAy);
                            float heightB = bridge.computeSmoothVoronoiGradient(null, sampleBx, sampleBy);

                            assertEquals(heightA, heightB, 0.01F,
                                    "Height across bridged border must be seamless at jitter " + jitter
                                            + " between (" + cx + "," + cy + ") and neighbor (" + vA.ncx[i] + "," + vA.ncy[i] + ")"
                                            + " at t=" + t + " (heightA=" + heightA + ", heightB=" + heightB + ")");
                        }
                    }
                }
            }
        }
    }

    @Test
    void testSkippedNeighborCoastalDistanceContinuity() {
        int seed = 1234;
        float jitter = 0.85F;
        float frequency = 1.0F / 4000.0F;

        UpliftContinentGenerator gen = new UpliftContinentGenerator(seed, jitter, frequency, 1.0F) {
            @Override
            public boolean shouldSkip(int cx, int cy) {
                return cx == 0 && cy == 0;
            }

            @Override
            public boolean isBridged(int ax, int ay, int bx, int by) {
                return (ax == 1 && ay == 0 && bx == 2 && by == 0) || (ax == 2 && ay == 0 && bx == 1 && by == 0);
            }

            @Override
            public boolean canBridge(int cx, int cy) {
                return !shouldSkip(cx, cy);
            }
        };

        PlateUpliftBridge.CellVoronoi vA = gen.getPlateUpliftBridge().getVoronoi(1, 0);

        int oceanEdgeIdx = -1;
        for (int i = 0; i < vA.edgeCount; i++) {
            if (vA.ncx[i] == 0 && vA.ncy[i] == 0) {
                oceanEdgeIdx = i;
                break;
            }
        }

        assertTrue(oceanEdgeIdx >= 0, "Cell (1, 0) must share a Voronoi edge with (0, 0)");
        assertFalse(vA.isBridged[oceanEdgeIdx], "Edge to skipped neighbor must not be bridged");

        float lo = vA.lo[oceanEdgeIdx];
        float hi = vA.hi[oceanEdgeIdx];
        float t = (lo + hi) * 0.5F;
        float borderX = vA.mx[oceanEdgeIdx] + t * vA.tx[oceanEdgeIdx];
        float borderY = vA.my[oceanEdgeIdx] + t * vA.ty[oceanEdgeIdx];

        float eps = 0.01F;
        float sampleX = borderX - eps * vA.nx[oceanEdgeIdx];
        float sampleY = borderY - eps * vA.ny[oceanEdgeIdx];

        float distSq = gen.getBoundaryDistanceSq(sampleX, sampleY, 1, 0, vA.seedX, vA.seedY);
        float dist = NoiseUtil.sqrt(distSq);

        assertEquals(eps, dist, 0.005F, "Distance to coast near skipped neighbor must accurately match distance to coastal border");
    }
}
