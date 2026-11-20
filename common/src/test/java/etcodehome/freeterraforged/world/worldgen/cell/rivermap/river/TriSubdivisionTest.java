package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.RenderMode;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.PlateUpliftBridge;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.noise.module.Line;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class TriSubdivisionTest {

    private static Path resolveOutputDir() {
        String prop = System.getProperty("freeterraforged.test.heightmap.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        String env = System.getenv("FTF_TEST_OUTPUT_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        Path root = Path.of("").toAbsolutePath();
        if (root.endsWith("common")) {
            root = root.getParent();
        }
        return root.resolve(Path.of("build", "reports", "preset-test-heightmaps")).toAbsolutePath().normalize();
    }

    @BeforeAll
    static void setUpRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("Verify plateau centers from hydrology step function")
    void testPlateauCenters() {
        float[] centers = ContinentalHydrology.getPlateauCenters();
        assertNotNull(centers);
        assertEquals(16, centers.length);

        for (int i = 0; i < centers.length; i++) {
            assertTrue(centers[i] >= 0.0F && centers[i] <= 1.0F, "Center must be in [0, 1]: " + centers[i]);
            if (i > 0) {
                assertTrue(centers[i] > centers[i - 1], "Centers must be strictly ascending: " + centers[i - 1] + " < " + centers[i]);
            }
        }
    }

    @Test
    @DisplayName("Generate 2D Visual Review Image of Continent Triangle Subdivision River Network")
    void testGenerateContinentRiverGraphImage() throws IOException {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            assertTrue(ctx.heightmap.continent() instanceof UpliftContinentGenerator, "Default preset should use UpliftContinentGenerator");
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();

            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);
            System.out.printf("[Test] Continent centroid center: (%d, %d)%n", centerX, centerZ);

            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);
            PlateUpliftBridge bridge = uplift.getPlateUpliftBridge();
            float frequency = uplift.getFrequency();

            PlateUpliftBridge.CellVoronoi voronoi = TriSubdivisionRiverGenerator.findVoronoiCell(bridge, frequency, centerX, centerZ);
            assertNotNull(voronoi, "Voronoi cell for continent must exist");

            Random random = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            for (int i = 0; i < voronoi.edgeCount; i++) {
                System.out.printf("Voronoi Edge %d: bridged=%b, tectonicActive=%b%n", i, voronoi.isBridged[i], voronoi.isTectonicActive[i]);
            }
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs = gen.buildDrainageGraphs(voronoi, frequency, random);

            assertFalse(graphs.isEmpty(), "At least one drainage face must be generated");
            System.out.printf("[Test] Generated %d drainage face graphs for continent%n", graphs.size());

            int totalCandidateNodes = 0;
            int totalCulledNodes = 0;
            int totalPopulatedNodes = 0;
            int totalConnections = 0;
            int headwaters = 0;
            int moistureCulledHeadwaters = 0;
            int moistureBoostedHeadwaters = 0;
            int lateralConnections = 0;
            int confluences = 0;
            int mountainNodes = 0;
            int populatedMountainNodes = 0;
            int totalLakes = 0;
            int terminalMountainLakes = 0;
            int valleyLakes = 0;
            int oceanFaces = 0;
            int bridgedFaces = 0;
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                if (g.isBridged) bridgedFaces++;
                else oceanFaces++;
                assertTrue(g.rows.size() >= ContinentalHydrology.NUM_STEPS, "Row count must have at least ContinentalHydrology terracing steps (15)");
                for (List<TriSubdivisionRiverGenerator.DrainageNode> row : g.rows) {
                    totalCandidateNodes += row.size();
                    for (TriSubdivisionRiverGenerator.DrainageNode n : row) {
                        if (n.moistureCulled) moistureCulledHeadwaters++;
                        if (n.moistureBoosted) moistureBoostedHeadwaters++;
                        if (n.isCulled) {
                            totalCulledNodes++;
                            assertFalse(n.isPopulated, "Culled nodes must never be populated");
                            assertEquals(0, n.feederCount, "Culled nodes must have 0 feeders");
                        } else {
                            if (n.isMountain) mountainNodes++;
                            if (n.isLake) {
                                totalLakes++;
                                if (n.isPopulated) terminalMountainLakes++;
                                else valleyLakes++;
                            }
                            if (n.isPopulated) {
                                totalPopulatedNodes++;
                                if (n.isMountain) populatedMountainNodes++;
                                if (n.feederCount >= 2) confluences++;
                            }
                        }
                    }
                }
                totalConnections += g.connections.size();
                for (TriSubdivisionRiverGenerator.RiverConnection conn : g.connections) {
                    if (conn.isHeadwater) headwaters++;
                    if (conn.isLateral) {
                        lateralConnections++;
                        assertEquals(conn.from.row, conn.to.row, "Lateral connections must link nodes on the same row");
                        assertEquals(conn.from.gradient, conn.to.gradient, 1.0e-4F, "Lateral connections share the same uplift plateau");
                        assertTrue(conn.from.isLake && conn.to.isLake, "Lateral connections must link adjacent lake nodes");
                    } else if (conn.isCoastal) {
                        assertTrue(conn.from.row < conn.to.row, "Coastal nodes must strictly head down the row tiers");
                        assertTrue(conn.from.gradient >= conn.to.gradient, "Coastal outlet segments must monotonically descend gradient");
                    } else {
                        // Verify monotonic gradient descent: from.row < to.row and from.gradient >= to.gradient
                        assertTrue(conn.from.row < conn.to.row, "Successive nodes must strictly head down the row tiers");
                        assertTrue(conn.from.gradient >= conn.to.gradient, "Successive nodes must monotonically descend gradient");
                    }
                    assertFalse(conn.from.isCulled, "Connection origin must never be culled");
                    assertFalse(conn.to.isCulled, "Connection destination must never be culled");

                    // Verify that rivers on bridged segments never reach the actual border
                    if (g.isBridged) {
                        assertTrue(conn.from.row < g.rows.size() - 2, "Bridged river origin must stay safely inside territory");
                        assertTrue(conn.to.row < g.rows.size() - 1, "Bridged river destination must never reach the actual border");
                    }

                }
            }

            int topRowCandidates = 0;
            int topRowPopulated = 0;
            int terminalNodes = 0;
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                if (!g.rows.isEmpty()) {
                    for (TriSubdivisionRiverGenerator.DrainageNode n : g.rows.get(0)) {
                        if (!n.isCulled) {
                            topRowCandidates++;
                            if (n.isPopulated) topRowPopulated++;
                        }
                    }
                }
                for (List<TriSubdivisionRiverGenerator.DrainageNode> row : g.rows) {
                    for (TriSubdivisionRiverGenerator.DrainageNode n : row) {
                        if (n.isPopulated && !g.outgoing.containsKey(n)) {
                            terminalNodes++;
                        }
                    }
                }
                for (TriSubdivisionRiverGenerator.DrainageNode n : g.coastalNodes) {
                    if (n.isPopulated) {
                        totalPopulatedNodes++;
                        if (!g.outgoing.containsKey(n)) {
                            terminalNodes++;
                        }
                    }
                }
            }

            System.out.printf("[Test] Candidate Nodes: %d (Culled: %d, Valid Land: %d), Populated: %d, River Segments: %d (Lateral Lake Rivers: %d), Headwaters: %d, Confluences: %d, Mountain Nodes: %d (Populated: %d), Lake Spawns: %d (Mountain Terminal: %d, Valley: %d), Faces: %d (Ocean: %d, Bridged: %d), Top Row Populated: %d/%d%n",
                    totalCandidateNodes, totalCulledNodes, totalCandidateNodes - totalCulledNodes, totalPopulatedNodes, totalConnections, lateralConnections, headwaters, confluences, mountainNodes, populatedMountainNodes, totalLakes, terminalMountainLakes, valleyLakes, graphs.size(), oceanFaces, bridgedFaces, topRowPopulated, topRowCandidates);
            assertTrue(totalCulledNodes > 0, "Some candidate nodes must be culled due to sea level");
            assertTrue(totalPopulatedNodes > 0, "Expected some nodes to be populated at riverCount = 8");
            assertTrue(totalPopulatedNodes < totalCandidateNodes - totalCulledNodes + 100, "Every land node should NOT be connected at riverCount = 8");
            assertTrue(topRowPopulated < topRowCandidates, "Topmost row must not place headwaters at every position");
            assertEquals(totalPopulatedNodes - terminalNodes, totalConnections, "Every non-terminal populated node should have exactly one outgoing connection");
            assertTrue(confluences > 0, "Expected multiple upstream nodes to merge into single downstream nodes (confluences)");
            assertTrue(totalLakes > 0, "Lake spawns should be present on continent");

            // Build full Rivermap to verify carver and warp integration
            Rivermap rivermap = gen.generateRivers(centerX, centerZ, PosUtil.pack(centerX, centerZ));
            assertNotNull(rivermap, "Rivermap generation must succeed");



            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                if (!g.isBridged) {
                    for (TriSubdivisionRiverGenerator.RiverConnection conn : g.connections) {
                        if (!g.outgoing.containsKey(conn.to)) {
                            if (conn.to.isLake) {
                                assertTrue(conn.to.isLake);
                            } else {
                                assertTrue(conn.isCoastal, "Terminal non-lake river segment on ocean face must be coastal");
                                assertTrue(gen.isBelowSeaLevel(conn.to.x, conn.to.z),
                                        "Terminal mouth on ocean face must reach below sea level (into ocean)");
                            }
                        }
                    }
                }
            }

            // Verify in-game carver and spline warp configuration
            List<Network.Builder> generatedRivers = gen.generateTriSubdivisionRivers(centerX, centerZ, new Random(42), GenWarp.EMPTY);
            assertFalse(generatedRivers.isEmpty(), "Generated river networks must not be empty");
            List<Network.Builder> allBuilders = new ArrayList<>();
            for (Network.Builder b : generatedRivers) {
                collectBuilders(b, allBuilders);
            }
            assertFalse(allBuilders.isEmpty(), "Generated river builders must not be empty");
            for (Network.Builder b : allBuilders) {
                assertTrue(b.carver instanceof UpliftRiverCarver || b.carver instanceof UpliftLakeCarver,
                        "Carver must be UpliftRiverCarver or UpliftLakeCarver");
                if (b.carver.getWarp() == RiverWarp.NONE) {
                    // Isolated valley lake basin carver
                    continue;
                }
                assertTrue(b.carver.getWarp() instanceof TriSubdivisionRiverGenerator.SplineRiverWarp,
                        "River carver warp must be an instance of SplineRiverWarp for organic meandering");
                River r = b.carver.getRiver();
                RiverWarp w = b.carver.getWarp();

                // Test endpoint continuity: zero offset at t = 0 and t = 1
                long off0 = w.getOffset(r.x1, r.z1, 0.0F, r);
                assertEquals(0.0F, PosUtil.unpackLeftf(off0), 1.0e-3F, "Offset at t=0 must be exactly 0");
                assertEquals(0.0F, PosUtil.unpackRightf(off0), 1.0e-3F, "Offset at t=0 must be exactly 0");

                long off1 = w.getOffset(r.x2, r.z2, 1.0F, r);
                assertEquals(0.0F, PosUtil.unpackLeftf(off1), 1.0e-3F, "Offset at t=1 must be exactly 0");
                assertEquals(0.0F, PosUtil.unpackRightf(off1), 1.0e-3F, "Offset at t=1 must be exactly 0");

                // Test interior warp fidelity: world point S(t) must warp to straight line L(t)
                for (float testT = 0.2F; testT <= 0.8F; testT += 0.2F) {
                    long off = w.getOffset(0, 0, testT, r);
                    float dx = PosUtil.unpackLeftf(off);
                    float dz = PosUtil.unpackRightf(off);
                    float lx = r.x1 + testT * r.dx;
                    float lz = r.z1 + testT * r.dz;
                    float expectedSplineX = lx - dx;
                    float expectedSplineZ = lz - dz;

                    // Warped block position (wx, wz) where world block is on the spline
                    float wx = expectedSplineX + dx;
                    float wz = expectedSplineZ + dz;
                    assertEquals(lx, wx, 1.0e-3F, "World block on spline must warp to straight line X");
                    assertEquals(lz, wz, 1.0e-3F, "World block on spline must warp to straight line Z");
                }
            }

            // Calculate precise bounding box framing the entire Voronoi polygon AND all river connections
            float polyMinX = (float) centerX;
            float polyMaxX = (float) centerX;
            float polyMinZ = (float) centerZ;
            float polyMaxZ = (float) centerZ;

            for (int i = 0; i < voronoi.edgeCount; i++) {
                float vAx = (voronoi.mx[i] + voronoi.lo[i] * voronoi.tx[i]) / frequency;
                float vAz = (voronoi.my[i] + voronoi.lo[i] * voronoi.ty[i]) / frequency;
                float vBx = (voronoi.mx[i] + voronoi.hi[i] * voronoi.tx[i]) / frequency;
                float vBz = (voronoi.my[i] + voronoi.hi[i] * voronoi.ty[i]) / frequency;
                polyMinX = Math.min(polyMinX, Math.min(vAx, vBx));
                polyMaxX = Math.max(polyMaxX, Math.max(vAx, vBx));
                polyMinZ = Math.min(polyMinZ, Math.min(vAz, vBz));
                polyMaxZ = Math.max(polyMaxZ, Math.max(vAz, vBz));
            }

            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                for (TriSubdivisionRiverGenerator.RiverConnection conn : g.connections) {
                    polyMinX = Math.min(polyMinX, Math.min(conn.from.x, conn.to.x));
                    polyMaxX = Math.max(polyMaxX, Math.max(conn.from.x, conn.to.x));
                    polyMinZ = Math.min(polyMinZ, Math.min(conn.from.z, conn.to.z));
                    polyMaxZ = Math.max(polyMaxZ, Math.max(conn.from.z, conn.to.z));
                }
            }

            float pad = 900.0F;
            float boundWidth = (polyMaxX - polyMinX) + pad * 2.0F;
            float boundHeight = (polyMaxZ - polyMinZ) + pad * 2.0F;
            float squareSize = Math.max(boundWidth, boundHeight);

            float midX = (polyMinX + polyMaxX) * 0.5F;
            float midZ = (polyMinZ + polyMaxZ) * 0.5F;
            float minX = midX - squareSize * 0.5F;
            float maxX = midX + squareSize * 0.5F;
            float minZ = midZ - squareSize * 0.5F;
            float maxZ = midZ + squareSize * 0.5F;

            // --- RENDER 2D REVIEW IMAGE ---
            int imgWidth = 1600;
            int imgHeight = 1600;
            BufferedImage image = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            // Coordinate mapping helper
            java.util.function.Function<Float, Double> mapX = wx -> (double) ((wx - minX) / (maxX - minX) * imgWidth);
            java.util.function.Function<Float, Double> mapZ = wz -> (double) ((wz - minZ) / (maxZ - minZ) * imgHeight);



            // 1. Background: Draw continent terrain gradient and ocean background
            Cell cell = new Cell();
            int bgStep = 4;
            for (int py = 0; py < imgHeight; py += bgStep) {
                float wz = minZ + (py / (float) imgHeight) * (maxZ - minZ);
                for (int px = 0; px < imgWidth; px += bgStep) {
                    float wx = minX + (px / (float) imgWidth) * (maxX - minX);
                    uplift.apply(cell, wx, wz);

                    int rgb;
                    if (gen.isBelowSeaLevel(wx, wz)) {
                        // True ocean
                        rgb = 0x08162B; // deep navy blue
                    } else {
                        // Continental land
                        float t = NoiseUtil.clamp(cell.waterTable, 0.0F, 1.0F);
                        int rVal, gVal, bVal;
                        if (t > 0.0F) {
                            // Uplift peak elevation shading
                            rVal = (int) (22 + t * 50);
                            gVal = (int) (38 + t * 75);
                            bVal = (int) (26 + t * 45);
                        } else {
                            // Continental lowlands / plains shading
                            float edge = NoiseUtil.clamp((uplift.getEdgeValue(wx, wz) - ctx.preset.world().controlPoints.shallowOcean) / (1.0F - ctx.preset.world().controlPoints.shallowOcean), 0.0F, 1.0F);
                            rVal = (int) (16 + edge * 12);
                            gVal = (int) (30 + edge * 15);
                            bVal = (int) (20 + edge * 12);
                        }

                        // Overlay flatness factor as a darkening fade on the underlying green ground
                        float flatness = NoiseUtil.clamp(cell.flatnessFactor, 0.0F, 1.0F);
                        if (flatness > 0.0F) {
                            float fade = flatness * 0.65F;
                            rVal = (int) (rVal * (1.0F - fade));
                            gVal = (int) (gVal * (1.0F - fade));
                            bVal = (int) (bVal * (1.0F - fade));
                        }
                        rgb = (rVal << 16) | (gVal << 8) | bVal;
                    }

                    for (int dy = 0; dy < bgStep && py + dy < imgHeight; dy++) {
                        for (int dx = 0; dx < bgStep && px + dx < imgWidth; dx++) {
                            image.setRGB(px + dx, py + dy, rgb);
                        }
                    }
                }
            }

            // 2. Draw ALL Voronoi edges of the polygon
            double peakPx = mapX.apply((float) centerX);
            double peakPz = mapZ.apply((float) centerZ);

            for (int i = 0; i < voronoi.edgeCount; i++) {
                float vAx = (voronoi.mx[i] + voronoi.lo[i] * voronoi.tx[i]) / frequency;
                float vAz = (voronoi.my[i] + voronoi.lo[i] * voronoi.ty[i]) / frequency;
                float vBx = (voronoi.mx[i] + voronoi.hi[i] * voronoi.tx[i]) / frequency;
                float vBz = (voronoi.my[i] + voronoi.hi[i] * voronoi.ty[i]) / frequency;

                double ax = mapX.apply(vAx);
                double az = mapZ.apply(vAz);
                double bx = mapX.apply(vBx);
                double bz = mapZ.apply(vBz);

                if (voronoi.isTectonicActive[i]) {
                    // Tectonic active border (peak-to-peak connection saddle - zero rivers generated)
                    g2.setStroke(new BasicStroke(2.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{6.0F, 6.0F}, 0.0F));
                    g2.setColor(new Color(239, 68, 68, 220)); // Bright red dashed line
                } else if (voronoi.isBridged[i]) {
                    // Bridged tectonic border (supercontinent landmass - rivers spawn and end in lakes before border)
                    g2.setStroke(new BasicStroke(2.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{8.0F, 8.0F}, 0.0F));
                    g2.setColor(new Color(251, 146, 60, 200)); // Amber-orange dashed line
                } else {
                    // Coastal boundary to ocean
                    g2.setStroke(new BasicStroke(1.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{4.0F, 4.0F}, 0.0F));
                    g2.setColor(new Color(255, 255, 255, 80)); // White dashed line
                }
                g2.draw(new Line2D.Double(ax, az, bx, bz));
            }

            // Draw radial face boundary rays from peak to ALL Voronoi vertices
            g2.setStroke(new BasicStroke(1.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{4.0F, 6.0F}, 0.0F));
            g2.setColor(new Color(255, 255, 255, 65));
            for (int i = 0; i < voronoi.edgeCount; i++) {
                float vAx = (voronoi.mx[i] + voronoi.lo[i] * voronoi.tx[i]) / frequency;
                float vAz = (voronoi.my[i] + voronoi.lo[i] * voronoi.ty[i]) / frequency;
                double v1x = mapX.apply(vAx);
                double v1z = mapZ.apply(vAz);
                g2.draw(new Line2D.Double(peakPx, peakPz, v1x, v1z));
            }

            // 3. Draw row terrace contours (the plateaus of ContinentalHydrology)
            g2.setStroke(new BasicStroke(1.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{2.0F, 4.0F}, 0.0F));
            g2.setColor(new Color(130, 200, 255, 45));
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph fg : graphs) {
                for (int r = 0; r < fg.rows.size(); r++) {
                    List<TriSubdivisionRiverGenerator.DrainageNode> row = fg.rows.get(r);
                    if (row.size() >= 2) {
                        for (int c = 0; c < row.size() - 1; c++) {
                            double xA = mapX.apply(row.get(c).x);
                            double zA = mapZ.apply(row.get(c).z);
                            double xB = mapX.apply(row.get(c + 1).x);
                            double zB = mapZ.apply(row.get(c + 1).z);
                            g2.draw(new Line2D.Double(xA, zA, xB, zB));
                        }
                    }
                }
            }

            // 4. Draw Warped River Flow Channels (continuous rounded meandering water paths)
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph fg : graphs) {
                long faceWarpSeed = ((long) fg.faceIndex * 123456789L) ^ worldSeed;

                for (TriSubdivisionRiverGenerator.RiverConnection conn : fg.connections) {
                    Path2D.Double warpedPath = new Path2D.Double();
                    int samples = 40;
                    for (int s = 0; s <= samples; s++) {
                        float t = s / (float) samples;
                        double[] pt = TriSubdivisionRiverGenerator.sampleRoundedSpline(conn, fg.outgoing, fg.incoming, t, faceWarpSeed);
                        double px = mapX.apply((float) pt[0]);
                        double pz = mapZ.apply((float) pt[1]);
                        if (s == 0) {
                            warpedPath.moveTo(px, pz);
                        } else {
                            warpedPath.lineTo(px, pz);
                        }
                    }

                    // Render warped river line (lake-to-lake rivers deferred to pass 6.5 so they overlay on top of purple lake nodes)
                    boolean isLakeToLake = (conn.from.isLake && conn.to.isLake) || conn.isLateral;
                    if (isLakeToLake) {
                        continue;
                    } else if (conn.isMain) {
                        g2.setStroke(new BasicStroke(4.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.setColor(new Color(0, 229, 255, 230)); // Bright cyan trunk
                    } else if (conn.isHeadwater) {
                        g2.setStroke(new BasicStroke(2.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.setColor(new Color(255, 215, 0, 230)); // Gold headwater
                    } else {
                        g2.setStroke(new BasicStroke(2.8F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.setColor(new Color(128, 222, 234, 210)); // Sky cyan branch
                    }
                    g2.draw(warpedPath);
                }
            }

            // 5. Draw Straight Drainage Graph Skeleton (semi-transparent overlay)
            g2.setStroke(new BasicStroke(1.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{2.0F, 3.0F}, 0.0F));
            g2.setColor(new Color(255, 255, 255, 80));
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph fg : graphs) {
                for (TriSubdivisionRiverGenerator.RiverConnection conn : fg.connections) {
                    double x1 = mapX.apply(conn.from.x);
                    double z1 = mapZ.apply(conn.from.z);
                    double x2 = mapX.apply(conn.to.x);
                    double z2 = mapZ.apply(conn.to.z);
                    boolean isLakeToLake = (conn.from.isLake && conn.to.isLake) || conn.isLateral;
                    if (isLakeToLake) {
                        g2.setColor(new Color(168, 85, 247, 160)); // Purple line for lake-to-lake
                    } else {
                        g2.setColor(new Color(255, 255, 255, 80));
                    }
                    g2.draw(new Line2D.Double(x1, z1, x2, z2));

                    // Direction indicator arrow/dot along river
                    double midDotX = (x1 + x2) * 0.5;
                    double midDotZ = (z1 + z2) * 0.5;
                    if (isLakeToLake) {
                        g2.setColor(new Color(216, 180, 254, 220));
                    } else {
                        g2.setColor(new Color(255, 255, 255, 180));
                    }
                    g2.fill(new Ellipse2D.Double(midDotX - 2.0, midDotZ - 2.0, 4.0, 4.0));
                }
            }

            // 6. Draw drainage graph nodes color-coded smoothly across all 15 ContinentalHydrology terraces
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph fg : graphs) {
                int totalR = fg.rows.size();
                for (int r = 0; r < totalR; r++) {
                    List<TriSubdivisionRiverGenerator.DrainageNode> row = fg.rows.get(r);
                    float frac = (float) r / (float) Math.max(1, totalR - 1);
                    // Smooth spectral shift from high peak (orange-red) to ocean mouth (cyan-blue)
                    Color nodeCol = Color.getHSBColor(0.04F + frac * 0.58F, 0.85F, 0.95F);

                    for (TriSubdivisionRiverGenerator.DrainageNode node : row) {
                        double nx = mapX.apply(node.x);
                        double nz = mapZ.apply(node.z);

                        if (node.isCulled) {
                            // Culled candidate node (below sea level / ocean) - do not draw
                            continue;
                        }

                        if (node.isLake && !node.isPopulated) {
                            // Unconnected valley lake spawn (Purple)
                            double lakeR = 5.0;
                            g2.setColor(new Color(168, 85, 247)); // Purple-500 fill
                            g2.fill(new Ellipse2D.Double(nx - lakeR, nz - lakeR, lakeR * 2.0, lakeR * 2.0));
                            g2.setStroke(new BasicStroke(1.5F));
                            g2.setColor(new Color(233, 213, 255)); // Purple-200 outline
                            g2.draw(new Ellipse2D.Double(nx - lakeR, nz - lakeR, lakeR * 2.0, lakeR * 2.0));
                            continue;
                        }

                        if (!node.isPopulated) {
                            if (node.moistureCulled) {
                                // Headwater culled by climate moisture rule: place small white 'x' on the node
                                if (node.isMountain) {
                                    g2.setColor(new Color(239, 68, 68, 140));
                                    g2.fill(new Ellipse2D.Double(nx - 2.5, nz - 2.5, 5.0, 5.0));
                                } else {
                                    g2.setColor(new Color(148, 163, 184, 180));
                                    g2.fill(new Ellipse2D.Double(nx - 2.0, nz - 2.0, 4.0, 4.0));
                                }
                                g2.setColor(Color.WHITE);
                                g2.setStroke(new BasicStroke(1.8F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                double arm = 3.5;
                                g2.draw(new Line2D.Double(nx - arm, nz - arm, nx + arm, nz + arm));
                                g2.draw(new Line2D.Double(nx - arm, nz + arm, nx + arm, nz - arm));
                                continue;
                            }

                            // Unconnected candidate node (subdivision grid slot)
                            if (node.isMountain) {
                                g2.setColor(new Color(239, 68, 68, 140)); // Red for mountain candidate
                                g2.fill(new Ellipse2D.Double(nx - 2.5, nz - 2.5, 5.0, 5.0));
                            } else {
                                g2.setColor(new Color(148, 163, 184, 180));
                                g2.fill(new Ellipse2D.Double(nx - 2.0, nz - 2.0, 4.0, 4.0));
                            }
                            continue;
                        }

                        double radius = (node.feederCount >= 2) ? 6.5 : (node.feederCount == 1 ? 4.5 : 3.5);
                        if (node.moistureBoosted) {
                            // Headwater generated by climate moisture rule: solid white circle!
                            double hRadius = 5.0;
                            g2.setColor(Color.WHITE);
                            g2.fill(new Ellipse2D.Double(nx - hRadius, nz - hRadius, hRadius * 2.0, hRadius * 2.0));
                            g2.setStroke(new BasicStroke(1.2F));
                            g2.setColor(new Color(220, 220, 220));
                            g2.draw(new Ellipse2D.Double(nx - hRadius, nz - hRadius, hRadius * 2.0, hRadius * 2.0));
                        } else if (node.isLake) {
                            // Terminal mountain lake spawn (Deep violet purple with lavender glow)
                            double lakeR = 7.0;
                            g2.setColor(new Color(147, 51, 234));
                            g2.fill(new Ellipse2D.Double(nx - lakeR, nz - lakeR, lakeR * 2.0, lakeR * 2.0));
                            g2.setStroke(new BasicStroke(2.2F));
                            g2.setColor(new Color(245, 208, 254));
                            g2.draw(new Ellipse2D.Double(nx - lakeR, nz - lakeR, lakeR * 2.0, lakeR * 2.0));
                        } else if (node.isMountain) {
                            g2.setColor(new Color(239, 68, 68)); // Vivid red for mountain nodes
                            g2.fill(new Ellipse2D.Double(nx - radius, nz - radius, radius * 2.0, radius * 2.0));
                        } else {
                            g2.setColor(nodeCol);
                            g2.fill(new Ellipse2D.Double(nx - radius, nz - radius, radius * 2.0, radius * 2.0));
                        }

                        // Confluence marker: bright cyan ring for merged nodes
                        if (node.feederCount >= 2) {
                            g2.setStroke(new BasicStroke(2.0F));
                            g2.setColor(new Color(0, 229, 255));
                            g2.draw(new Ellipse2D.Double(nx - radius - 2.0, nz - radius - 2.0, (radius + 2.0) * 2.0, (radius + 2.0) * 2.0));
                        } else if (node.feederCount == 0 && !node.isLake && !node.moistureBoosted) {
                            // Outer white ring for normal baseline headwaters
                            g2.setStroke(new BasicStroke(1.5F));
                            g2.setColor(Color.WHITE);
                            g2.draw(new Ellipse2D.Double(nx - radius - 1.5, nz - radius - 1.5, (radius + 1.5) * 2.0, (radius + 1.5) * 2.0));
                        }
                    }
                }
            }

            // 6.5 Draw Lake-Connecting Rivers in Bright White OVERLAID ON TOP of purple lake nodes
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph fg : graphs) {
                long faceWarpSeed = ((long) fg.faceIndex * 123456789L) ^ worldSeed;

                for (TriSubdivisionRiverGenerator.RiverConnection conn : fg.connections) {
                    boolean isLakeToLake = (conn.from.isLake && conn.to.isLake) || conn.isLateral;
                    if (!isLakeToLake) {
                        continue;
                    }

                    Path2D.Double warpedPath = new Path2D.Double();
                    int samples = 40;
                    for (int s = 0; s <= samples; s++) {
                        float t = s / (float) samples;
                        double[] pt = TriSubdivisionRiverGenerator.sampleRoundedSpline(conn, fg.outgoing, fg.incoming, t, faceWarpSeed);
                        double px = mapX.apply((float) pt[0]);
                        double pz = mapZ.apply((float) pt[1]);
                        if (s == 0) {
                            warpedPath.moveTo(px, pz);
                        } else {
                            warpedPath.lineTo(px, pz);
                        }
                    }

                    // Render bright white line overlaid on top of purple lake nodes
                    g2.setStroke(new BasicStroke(3.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(Color.WHITE); // 100% solid bright white
                    g2.draw(warpedPath);
                }
            }

            // 6.6 Draw coastal river nodes and ocean mouths
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph fg : graphs) {
                for (TriSubdivisionRiverGenerator.DrainageNode node : fg.coastalNodes) {
                    double nx = mapX.apply(node.x);
                    double nz = mapZ.apply(node.z);
                    boolean isMouth = !fg.outgoing.containsKey(node);
                    if (isMouth) {
                        // Ocean mouth marker (Cyan-teal circle at the coastline)
                        double mouthR = 5.5;
                        g2.setColor(new Color(6, 182, 212));
                        g2.fill(new Ellipse2D.Double(nx - mouthR, nz - mouthR, mouthR * 2.0, mouthR * 2.0));
                        g2.setStroke(new BasicStroke(1.8F));
                        g2.setColor(Color.WHITE);
                        g2.draw(new Ellipse2D.Double(nx - mouthR, nz - mouthR, mouthR * 2.0, mouthR * 2.0));
                    } else {
                        // Intermediate coastal node
                        double radius = 3.5;
                        g2.setColor(new Color(56, 189, 248)); // Sky-400
                        g2.fill(new Ellipse2D.Double(nx - radius, nz - radius, radius * 2.0, radius * 2.0));
                    }
                }
            }

            // 7. Draw Centroid Peak
            double peakR = 11.0;
            g2.setColor(new Color(255, 215, 0)); // Gold
            Path2D.Double diamond = new Path2D.Double();
            diamond.moveTo(peakPx, peakPz - peakR);
            diamond.lineTo(peakPx + peakR, peakPz);
            diamond.lineTo(peakPx, peakPz + peakR);
            diamond.lineTo(peakPx - peakR, peakPz);
            diamond.closePath();
            g2.fill(diamond);
            g2.setStroke(new BasicStroke(2.0F));
            g2.setColor(Color.WHITE);
            g2.draw(diamond);

            // 8. Legend and Diagnostic Overlay
            int legBoxX = 35;
            int legBoxY = 1000;
            g2.setColor(new Color(15, 23, 42, 235)); // Slate dark background
            g2.fillRoundRect(legBoxX, legBoxY, 570, 560, 16, 16);
            g2.setStroke(new BasicStroke(1.5F));
            g2.setColor(new Color(255, 255, 255, 90));
            g2.drawRoundRect(legBoxX, legBoxY, 570, 560, 16, 16);

            g2.setFont(new Font("SansSerif", Font.BOLD, 17));
            g2.setColor(Color.WHITE);
            g2.drawString("FreeTerraForged - TriSubdivision River Network", legBoxX + 20, legBoxY + 31);

            g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g2.setColor(new Color(203, 213, 225));
            g2.drawString(String.format("Centroid Peak: (%d, %d) | Seed: %d", centerX, centerZ, worldSeed), legBoxX + 20, legBoxY + 55);
            g2.drawString(String.format("Active Faces: %d (Ocean: %d, Bridged: %d) | Terraces: %d (15 steps)", graphs.size(), oceanFaces, bridgedFaces, ContinentalHydrology.NUM_STEPS), legBoxX + 20, legBoxY + 73);
            g2.drawString(String.format("Candidate Nodes: %d (Culled: %d, Land: %d) | Populated: %d (Peak Row: %d/%d)", totalCandidateNodes, totalCulledNodes, totalCandidateNodes - totalCulledNodes, totalPopulatedNodes, topRowPopulated, topRowCandidates), legBoxX + 20, legBoxY + 91);
            g2.drawString(String.format("River Segments: %d (Lateral Lake: %d) | Confluences: %d | Headwaters: %d (Boosted: %d, Culled: %d)", totalConnections, lateralConnections, confluences, headwaters, moistureBoostedHeadwaters, moistureCulledHeadwaters), legBoxX + 20, legBoxY + 109);
            g2.drawString(String.format("Mountain Nodes (Red): %d (Populated: %d) | Valley/Lowland: %d", mountainNodes, populatedMountainNodes, (totalCandidateNodes - totalCulledNodes) - mountainNodes), legBoxX + 20, legBoxY + 127);
            g2.drawString(String.format("Lake Spawns (Purple): %d (Mountain Terminal: %d, Valley: %d) | Lake Chance: %.0f%% (Halved Default)", totalLakes, terminalMountainLakes, valleyLakes, gen.getLakeChance() * 100.0F), legBoxX + 20, legBoxY + 145);

            // Legend swatches
            int legendY = legBoxY + 169;

            // Peak
            g2.setColor(new Color(255, 215, 0));
            g2.fillRect(legBoxX + 20, legendY - 8, 12, 12);
            g2.setColor(Color.WHITE);
            g2.drawString("Voronoi Centroid Peak (Elevation 1.0)", legBoxX + 43, legendY + 2);

            // Main River
            legendY += 22;
            g2.setStroke(new BasicStroke(4.0F));
            g2.setColor(new Color(0, 229, 255));
            g2.drawLine(legBoxX + 20, legendY - 2, legBoxX + 35, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Main River Trunk (High flow / Confluence)", legBoxX + 43, legendY + 2);

            // Headwater / Branch
            legendY += 22;
            g2.setStroke(new BasicStroke(2.2F));
            g2.setColor(new Color(255, 215, 0));
            g2.drawLine(legBoxX + 20, legendY - 2, legBoxX + 35, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Headwater Origin (Empty node on terrace row)", legBoxX + 43, legendY + 2);

            // Moisture-Boosted Headwater (Solid White Circle)
            legendY += 22;
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Double(legBoxX + 22, legendY - 6, 8, 8));
            g2.setStroke(new BasicStroke(1.2F));
            g2.setColor(new Color(220, 220, 220));
            g2.draw(new Ellipse2D.Double(legBoxX + 22, legendY - 6, 8, 8));
            g2.setColor(Color.WHITE);
            g2.drawString("Moisture-Boosted Headwater (Solid white circle - high moisture spawn)", legBoxX + 43, legendY + 2);

            // Moisture-Culled Headwater (Small White X)
            legendY += 22;
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(legBoxX + 23, legendY - 5, legBoxX + 29, legendY + 1);
            g2.drawLine(legBoxX + 23, legendY + 1, legBoxX + 29, legendY - 5);
            g2.drawString("Moisture-Culled Headwater (White x - culled by low moisture)", legBoxX + 43, legendY + 2);

            // Lake Spawn Node (Purple)
            legendY += 22;
            g2.setColor(new Color(168, 85, 247));
            g2.fill(new Ellipse2D.Double(legBoxX + 22, legendY - 6, 8, 8));
            g2.setStroke(new BasicStroke(1.5F));
            g2.setColor(new Color(233, 213, 255));
            g2.draw(new Ellipse2D.Double(legBoxX + 22, legendY - 6, 8, 8));
            g2.setColor(Color.WHITE);
            g2.drawString("Lake Spawn Node (Purple - terminal mountain lake / unconnected valley lake)", legBoxX + 43, legendY + 2);

            // Lake-to-Lake River (Bright White line overlaid on purple lake nodes)
            legendY += 22;
            g2.setStroke(new BasicStroke(3.0F));
            g2.setColor(Color.WHITE);
            g2.drawLine(legBoxX + 20, legendY - 2, legBoxX + 35, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Lake-Connecting River (Bright white line overlaid on purple lake nodes)", legBoxX + 43, legendY + 2);

            // Mountain Terrain Node (Red)
            legendY += 22;
            g2.setColor(new Color(239, 68, 68));
            g2.fill(new Ellipse2D.Double(legBoxX + 22, legendY - 6, 8, 8));
            g2.setColor(Color.WHITE);
            g2.drawString("Mountain Terrain Node (Red - 80% non-mtn bias, suppressed headwaters)", legBoxX + 43, legendY + 2);

            // Confluence
            legendY += 22;
            g2.setColor(new Color(0, 229, 255));
            g2.fill(new Ellipse2D.Double(legBoxX + 22, legendY - 6, 8, 8));
            g2.setColor(Color.WHITE);
            g2.drawString("Confluence Node (Multiple upstream nodes merged into 1)", legBoxX + 43, legendY + 2);

            // Warped River Flow
            legendY += 22;
            g2.setStroke(new BasicStroke(2.5F));
            g2.setColor(new Color(128, 222, 234));
            g2.drawLine(legBoxX + 20, legendY - 2, legBoxX + 35, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Warped River Flow (Domain simplex frequency warp)", legBoxX + 43, legendY + 2);

            // Terrace Rows
            legendY += 22;
            g2.setStroke(new BasicStroke(1.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{3.0F, 4.0F}, 0.0F));
            g2.setColor(new Color(130, 200, 255));
            g2.drawLine(legBoxX + 20, legendY - 2, legBoxX + 35, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("ContinentalHydrology Terraces (15 Rows & Flatness Darkening Fade)", legBoxX + 43, legendY + 2);

            // Unconnected Candidate Node
            legendY += 22;
            g2.setColor(new Color(148, 163, 184, 180));
            g2.fill(new Ellipse2D.Double(legBoxX + 24, legendY - 4, 4, 4));
            g2.setColor(Color.WHITE);
            g2.drawString("Unconnected Candidate Node (Terrace grid slot - not connected)", legBoxX + 43, legendY + 2);

            // Tectonic Active Border
            legendY += 22;
            g2.setStroke(new BasicStroke(2.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{6.0F, 6.0F}, 0.0F));
            g2.setColor(new Color(239, 68, 68));
            g2.drawLine(legBoxX + 20, legendY - 2, legBoxX + 35, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Tectonic Active Border (Peak connection saddle - 0 rivers)", legBoxX + 43, legendY + 2);

            // Bridged Tectonic Border
            legendY += 22;
            g2.setStroke(new BasicStroke(2.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{8.0F, 8.0F}, 0.0F));
            g2.setColor(new Color(251, 146, 60));
            g2.drawLine(legBoxX + 20, legendY - 2, legBoxX + 35, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Bridged Tectonic Border (Rivers end in lakes before border)", legBoxX + 43, legendY + 2);

            // Coastal Ocean Boundary
            legendY += 22;
            g2.setStroke(new BasicStroke(1.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{4.0F, 4.0F}, 0.0F));
            g2.setColor(new Color(255, 255, 255));
            g2.drawLine(legBoxX + 20, legendY - 2, legBoxX + 35, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Open Coastal Ocean Boundary", legBoxX + 43, legendY + 2);

            // Sector Guide
            legendY += 22;
            g2.setFont(new Font("SansSerif", Font.BOLD, 12));
            g2.setColor(new Color(248, 113, 113));
            g2.drawString("Sectors: A (Tectonic Active) | B, C, G (Bridged) | D, E, F (Ocean)", legBoxX + 20, legendY + 2);

            // 9. Draw Sector Labels (A, B, C, D, E, F, G) matching user diagram
            java.util.Map<Integer, String> sectorLabels = new java.util.HashMap<>();
            sectorLabels.put(3, "A");
            sectorLabels.put(5, "B");
            sectorLabels.put(6, "C");
            sectorLabels.put(4, "D");
            sectorLabels.put(2, "E");
            sectorLabels.put(0, "F");
            sectorLabels.put(1, "G");

            g2.setFont(new Font("SansSerif", Font.BOLD, 48));
            FontMetrics fm = g2.getFontMetrics();

            for (int i = 0; i < voronoi.edgeCount; i++) {
                String label = sectorLabels.get(i);
                if (label == null) continue;

                float vAx = (voronoi.mx[i] + voronoi.lo[i] * voronoi.tx[i]) / frequency;
                float vAz = (voronoi.my[i] + voronoi.lo[i] * voronoi.ty[i]) / frequency;
                float vBx = (voronoi.mx[i] + voronoi.hi[i] * voronoi.tx[i]) / frequency;
                float vBz = (voronoi.my[i] + voronoi.hi[i] * voronoi.ty[i]) / frequency;
                float mX = (vAx + vBx) * 0.5F;
                float mZ = (vAz + vBz) * 0.5F;

                float dirX = mX - centerX;
                float dirZ = mZ - centerZ;
                float dist = NoiseUtil.sqrt(dirX * dirX + dirZ * dirZ);
                float uX = dirX / dist;
                float uZ = dirZ / dist;

                // Place outside border by 450m
                float labelWx = mX + uX * 450.0F;
                float labelWz = mZ + uZ * 450.0F;

                double lx = mapX.apply(labelWx);
                double lz = mapZ.apply(labelWz);

                int textW = fm.stringWidth(label);
                int textH = fm.getAscent();

                float drawX = (float) (lx - textW * 0.5);
                float drawY = (float) (lz + textH * 0.38);


                // Drop shadow
                g2.setColor(new Color(0, 0, 0, 240));
                g2.drawString(label, drawX + 3, drawY + 3);
                g2.drawString(label, drawX - 1, drawY - 1);
                // Vivid Red
                g2.setColor(new Color(239, 68, 68));
                g2.drawString(label, drawX, drawY);
            }

            g2.dispose();

            // Save output image to build/reports/preset-test-heightmaps
            Path outputDir = resolveOutputDir();
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve("tri_subdivision_continent_review.png");
            ImageIO.write(image, "PNG", outputFile.toFile());
            System.out.printf("[Test] Saved 2D river network review image to: %s%n", outputFile.toAbsolutePath());
            assertTrue(Files.exists(outputFile), "Output image file must be successfully created");

            // Also copy to subproject build dir if different, to ensure it's easily found everywhere
            Path subDir = Path.of("common", "build", "reports", "preset-test-heightmaps");
            if (Files.exists(subDir.getParent())) {
                Files.createDirectories(subDir);
                Files.copy(outputFile, subDir.resolve("tri_subdivision_continent_review.png"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Test
    @DisplayName("Verify node population control at riverCount = 0 and riverCount = 30")
    void testNodePopulationBoundaryConditions() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);
            PlateUpliftBridge bridge = uplift.getPlateUpliftBridge();
            float frequency = uplift.getFrequency();
            PlateUpliftBridge.CellVoronoi voronoi = TriSubdivisionRiverGenerator.findVoronoiCell(bridge, frequency, centerX, centerZ);
            assertNotNull(voronoi, "Voronoi cell for continent must exist");

            // 1. Test riverCount = 0: no nodes populated, 0 connections
            Random random0 = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs0 = gen.buildDrainageGraphs(voronoi, frequency, random0, 0);
            int populated0 = 0;
            int connections0 = 0;
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs0) {
                connections0 += g.connections.size();
                for (List<TriSubdivisionRiverGenerator.DrainageNode> row : g.rows) {
                    for (TriSubdivisionRiverGenerator.DrainageNode n : row) {
                        if (n.isPopulated) populated0++;
                    }
                }
            }
            assertEquals(0, populated0, "At riverCount = 0, no nodes should be populated");
            assertEquals(0, connections0, "At riverCount = 0, no connections should exist");

            // 2. Test riverCount = 30: every unculled node populated, every unculled node connected
            Random random30 = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs30 = gen.buildDrainageGraphs(voronoi, frequency, random30, 30);
            int candidate30 = 0;
            int culled30 = 0;
            int populated30 = 0;
            int connections30 = 0;
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs30) {
                connections30 += g.connections.size();
                for (List<TriSubdivisionRiverGenerator.DrainageNode> row : g.rows) {
                    candidate30 += row.size();
                    for (TriSubdivisionRiverGenerator.DrainageNode n : row) {
                        if (n.isCulled) {
                            culled30++;
                        } else if (n.isPopulated) {
                            populated30++;
                            // Populated nodes either connect downward or terminate in a lake
                            assertTrue(g.outgoing.containsKey(n) || n.isLake,
                                    "Every unculled populated land node must have an outgoing connection or be a lake");
                        }
                    }
                }
            }
            assertTrue(candidate30 > 0, "Candidate nodes must exist");
            assertTrue(culled30 > 0, "Ocean candidate nodes must be culled");
            int landCandidates30 = candidate30 - culled30;
            assertEquals(landCandidates30, populated30, "At riverCount = 30, every unculled land node must be populated");
            assertTrue(connections30 > 0, "At riverCount = 30, river connections must exist: " + connections30);
        }
    }

    @Test
    @DisplayName("Verify Tectonic Active Border (Peak to Peak Saddle) Sectors Never Generate Candidate Nodes")
    void testTectonicActiveBordersNeverHaveNodes() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);

        long[] testSeeds = {42L, 100L, 2024L, 99999L};
        int validatedTectonicCount = 0;
        for (long worldSeed : testSeeds) {
            try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
                UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
                long centerPacked = uplift.getNearestCenter(0, 0);
                int centerX = PosUtil.unpackLeft(centerPacked);
                int centerZ = PosUtil.unpackRight(centerPacked);

                TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);
                PlateUpliftBridge bridge = uplift.getPlateUpliftBridge();
                float frequency = uplift.getFrequency();
                PlateUpliftBridge.CellVoronoi voronoi = TriSubdivisionRiverGenerator.findVoronoiCell(bridge, frequency, centerX, centerZ);
                assertNotNull(voronoi, "Voronoi cell for continent must exist");

                Random random = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
                List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs = gen.buildDrainageGraphs(voronoi, frequency, random);

                // Identify all tectonic active edges on this continent Voronoi cell
                java.util.Set<Integer> tectonicActiveEdgeIndices = new java.util.HashSet<>();
                for (int i = 0; i < voronoi.edgeCount; i++) {
                    if (voronoi.isTectonicActive[i]) {
                        tectonicActiveEdgeIndices.add(i);
                        validatedTectonicCount++;
                    }
                }

                // If this seed has tectonic active borders, verify zero graphs and zero candidate nodes are ever generated for them
                if (!tectonicActiveEdgeIndices.isEmpty()) {
                    for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                        assertFalse(tectonicActiveEdgeIndices.contains(g.faceIndex),
                                String.format("Seed %d: Tectonic active border (edge %d) must never have a drainage graph or candidate nodes",
                                        worldSeed, g.faceIndex));
                    }
                }

                // Also test with maximum riverCount (30) to ensure high density cannot force candidate node generation on tectonic active borders
                List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphsMax = gen.buildDrainageGraphs(voronoi, frequency, random, 30);
                if (!tectonicActiveEdgeIndices.isEmpty()) {
                    for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphsMax) {
                        assertFalse(tectonicActiveEdgeIndices.contains(g.faceIndex),
                                String.format("Seed %d (riverCount=30): Tectonic active border (edge %d) must never have a drainage graph or candidate nodes",
                                        worldSeed, g.faceIndex));
                    }
                }
            }
        }
        assertTrue(validatedTectonicCount > 0, "Test suite must have validated at least one tectonic active border across test seeds");
    }

    @Test
    @DisplayName("Inspect NE Gap nodes and connections")
    void testInspectNEGap() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);
            PlateUpliftBridge bridge = uplift.getPlateUpliftBridge();
            float frequency = uplift.getFrequency();
            PlateUpliftBridge.CellVoronoi voronoi = TriSubdivisionRiverGenerator.findVoronoiCell(bridge, frequency, centerX, centerZ);
            Random random = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs = gen.buildDrainageGraphs(voronoi, frequency, random);

            for (int i = 0; i < voronoi.edgeCount; i++) {
                float vAx = (voronoi.mx[i] + voronoi.lo[i] * voronoi.tx[i]) / frequency;
                float vAz = (voronoi.my[i] + voronoi.lo[i] * voronoi.ty[i]) / frequency;
                float vBx = (voronoi.mx[i] + voronoi.hi[i] * voronoi.tx[i]) / frequency;
                float vBz = (voronoi.my[i] + voronoi.hi[i] * voronoi.ty[i]) / frequency;
                float mX = (vAx + vBx) * 0.5F;
                float mZ = (vAz + vBz) * 0.5F;
                double angle = Math.toDegrees(Math.atan2(mZ - centerZ, mX - centerX));
                System.out.printf("Edge %d: angle=%.1f deg, mid=(%.1f, %.1f), tectonic=%b, bridged=%b%n",
                        i, angle, mX, mZ, voronoi.isTectonicActive[i], voronoi.isBridged[i]);
            }
        }
    }

    @Test
    @DisplayName("Print all river terminal points on all faces")
    void testPrintAllTerminals() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);
            PlateUpliftBridge bridge = uplift.getPlateUpliftBridge();
            float frequency = uplift.getFrequency();
            PlateUpliftBridge.CellVoronoi voronoi = TriSubdivisionRiverGenerator.findVoronoiCell(bridge, frequency, centerX, centerZ);
            Random random = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            for (int i = 0; i < voronoi.edgeCount; i++) {
                System.out.printf("Voronoi Edge %d: bridged=%b, tectonicActive=%b%n", i, voronoi.isBridged[i], voronoi.isTectonicActive[i]);
            }
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs = gen.buildDrainageGraphs(voronoi, frequency, random);

            for (int f = 0; f < graphs.size(); f++) {
                TriSubdivisionRiverGenerator.FaceDrainageGraph g = graphs.get(f);
                System.out.printf("=== Face %d (bridged=%b) ===%n", f, g.isBridged);
                for (TriSubdivisionRiverGenerator.RiverConnection conn : g.connections) {
                    if (conn.to.x > centerX && conn.to.z < centerZ) {
                        boolean isTerminal = !g.outgoing.containsKey(conn.to);
                        if (isTerminal) {
                            System.out.printf("   [NE TERMINAL] Face %d: conn (%s) from (%.1f, %.1f, r=%d) to (%.1f, %.1f, r=%d): isLake=%b, isCoastal=%b, isBelowSea=%b, edgeVal=%.4f%n",
                                    f, conn.isMain ? "main" : "fork",
                                    conn.from.x, conn.from.z, conn.from.row,
                                    conn.to.x, conn.to.z, conn.to.row,
                                    conn.to.isLake, conn.isCoastal,
                                    gen.isBelowSeaLevel(conn.to.x, conn.to.z),
                                    uplift.getEdgeValue(conn.to.x, conn.to.z));
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Trace river path to ocean to inspect carving continuity")
    void testTraceRiverToOcean() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);
            PlateUpliftBridge bridge = uplift.getPlateUpliftBridge();
            float frequency = uplift.getFrequency();
            PlateUpliftBridge.CellVoronoi voronoi = TriSubdivisionRiverGenerator.findVoronoiCell(bridge, frequency, centerX, centerZ);
            Random random = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            for (int i = 0; i < voronoi.edgeCount; i++) {
                System.out.printf("Voronoi Edge %d: bridged=%b, tectonicActive=%b%n", i, voronoi.isBridged[i], voronoi.isTectonicActive[i]);
            }
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs = gen.buildDrainageGraphs(voronoi, frequency, random);

            // Find an open ocean face and follow one river branch from row 0 to its ocean mouth
            TriSubdivisionRiverGenerator.FaceDrainageGraph oceanGraph = null;
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                if (!g.isBridged) {
                    oceanGraph = g;
                    break;
                }
            }
            assertNotNull(oceanGraph, "Must have ocean graph");

            // Trace path from the first populated row 0 node down outgoing
            TriSubdivisionRiverGenerator.DrainageNode curr = null;
            for (TriSubdivisionRiverGenerator.DrainageNode n : oceanGraph.rows.get(0)) {
                if (n.isPopulated && oceanGraph.outgoing.containsKey(n)) {
                    curr = n;
                    break;
                }
            }
            if (curr == null) {
                for (int r = 1; r < oceanGraph.rows.size(); r++) {
                    for (TriSubdivisionRiverGenerator.DrainageNode n : oceanGraph.rows.get(r)) {
                        if (n.isPopulated && oceanGraph.outgoing.containsKey(n)) {
                            curr = n;
                            break;
                        }
                    }
                    if (curr != null) break;
                }
            }
            assertNotNull(curr, "Must find start node");

            System.out.println("=== TRACING RIVER CHAIN TO OCEAN ===");
            Cell cell = new Cell();
            while (curr != null) {
                ctx.lookup.applyCell(cell.reset(), (int) curr.x, (int) curr.z, false);
                System.out.printf("Node r%d c%d at (%.1f, %.1f): waterTable=%.4f, height=%.4f, terrain=%s, isWatery=%b, isBelowSea=%b%n",
                    curr.row, curr.col, curr.x, curr.z, cell.waterTable, cell.height, cell.terrain.getName(),
                    cell.terrain.isWateryButNotOcean(), gen.isBelowSeaLevel(curr.x, curr.z));

                TriSubdivisionRiverGenerator.RiverConnection conn = oceanGraph.outgoing.get(curr);
                if (conn != null) {
                    // Sample 5 points along the connection
                    for (int s = 1; s <= 4; s++) {
                        float t = s / 5.0f;
                        float sx = conn.from.x + t * (conn.to.x - conn.from.x);
                        float sz = conn.from.z + t * (conn.to.z - conn.from.z);
                        ctx.lookup.applyCell(cell.reset(), (int) sx, (int) sz, false);
                        System.out.printf("   step t=%.2f at (%.1f, %.1f): waterTable=%.4f, height=%.4f, terrain=%s, isWatery=%b, riverZone=%s%n",
                            t, sx, sz, cell.waterTable, cell.height, cell.terrain.getName(), cell.terrain.isWateryButNotOcean(), cell.riverZone);
                    }
                    curr = conn.to;
                } else {
                    break;
                }
            }
        }
    }

    @Test
    @DisplayName("Verify organic presence of isolated plateau lakes and absence of floating lateral river segments")
    void testVerifyNoIsolatedLakesOrFloatingSegments() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);
            PlateUpliftBridge bridge = uplift.getPlateUpliftBridge();
            float frequency = uplift.getFrequency();
            PlateUpliftBridge.CellVoronoi voronoi = TriSubdivisionRiverGenerator.findVoronoiCell(bridge, frequency, centerX, centerZ);
            Random random = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs = gen.buildDrainageGraphs(voronoi, frequency, random);

            int isolatedLakes = 0;
            int floatingLaterals = 0;

            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                for (List<TriSubdivisionRiverGenerator.DrainageNode> row : g.rows) {
                    for (TriSubdivisionRiverGenerator.DrainageNode n : row) {
                        if (n.isLake && n.feederCount == 0 && !g.outgoing.containsKey(n)) {
                            isolatedLakes++;
                            System.out.printf("[ISOLATED LAKE] Face %d (r=%d, c=%d) at (%.1f, %.1f)%n", g.faceIndex, n.row, n.col, n.x, n.z);
                        }
                    }
                }
                for (TriSubdivisionRiverGenerator.RiverConnection conn : g.connections) {
                    if (conn.isLateral) {
                        // All lateral connections must link adjacent lake nodes on the same row
                        assertTrue(conn.from.isLake && conn.to.isLake, "Lateral connections must link adjacent lake nodes");
                        assertEquals(conn.from.row, conn.to.row, "Lateral connections must link nodes on the same row");
                        if (!conn.from.isLake || !conn.to.isLake) {
                            floatingLaterals++;
                            System.out.printf("[INVALID LATERAL] Face %d from (%.1f, %.1f) to (%.1f, %.1f)%n", g.faceIndex, conn.from.x, conn.from.z, conn.to.x, conn.to.z);
                        }
                    }
                }
            }

            assertTrue(isolatedLakes > 0, "Isolated lakes with 0 feeders and 0 outflow should naturally spawn across upland plateaus: " + isolatedLakes);
            assertEquals(0, floatingLaterals, "Invalid non-lake lateral river segments must be strictly 0");
        }
    }

    @Test
    @DisplayName("Verify River Water Level Monotonicity Along Warped Spline")
    void testSplineWaterLevelMonotonicity() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);
            PlateUpliftBridge bridge = uplift.getPlateUpliftBridge();
            float frequency = uplift.getFrequency();
            PlateUpliftBridge.CellVoronoi voronoi = TriSubdivisionRiverGenerator.findVoronoiCell(bridge, frequency, centerX, centerZ);
            Random random = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            for (int i = 0; i < voronoi.edgeCount; i++) {
                System.out.printf("Voronoi Edge %d: bridged=%b, tectonicActive=%b%n", i, voronoi.isBridged[i], voronoi.isTectonicActive[i]);
            }
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs = gen.buildDrainageGraphs(voronoi, frequency, random);

            Cell cell = new Cell();
            int uphillCount = 0;
            double maxUphillRise = 0.0;

            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                long faceWarpSeed = ((long) g.faceIndex * 123456789L) ^ worldSeed;
                for (TriSubdivisionRiverGenerator.RiverConnection conn : g.connections) {
                    if (conn.isLateral) continue; // Lateral connections share the same plateau

                    float segDx = conn.to.x - conn.from.x;
                    float segDz = conn.to.z - conn.from.z;
                    float segLen = (float) Math.sqrt(segDx * segDx + segDz * segDz);
                    float unitSegX = segDx / segLen;
                    float unitSegZ = segDz / segLen;

                    // Verify forward projected segment vectors are strictly positive along entire spline
                    for (int s = 0; s < 50; s++) {
                        float t = s / 50.0f;
                        float dt = 0.001f;
                        double[] p0 = TriSubdivisionRiverGenerator.sampleRoundedSpline(conn, g.outgoing, g.incoming, t, faceWarpSeed);
                        double[] p1 = TriSubdivisionRiverGenerator.sampleRoundedSpline(conn, g.outgoing, g.incoming, t + dt, faceWarpSeed);
                        double vx = (p1[0] - p0[0]) / dt;
                        double vz = (p1[1] - p0[1]) / dt;
                        double forwardProj = vx * unitSegX + vz * unitSegZ;
                        assertTrue(forwardProj > 0.0, String.format("Forward projected vector must be strictly positive: got %.4f at t=%.3f for conn (%d,%d)->(%d,%d)",
                                forwardProj, t, conn.from.row, conn.from.col, conn.to.row, conn.to.col));
                    }

                    float prevWaterY = Float.MAX_VALUE;
                    for (int s = 0; s <= 20; s++) {
                        float t = s / 20.0f;
                        double[] pt = TriSubdivisionRiverGenerator.sampleRoundedSpline(conn, g.outgoing, g.incoming, t, faceWarpSeed);
                        ctx.lookup.applyCell(cell.reset(), (int) pt[0], (int) pt[1], false);
                        float waterLevel = (ContinentalHydrology.getComplexWaterHeight(
                                cell.waterTable,
                                cell.globalContinentScale,
                                cell.continentSizeModifier)
                        ) + ctx.levels.water;
                        float waterY = ctx.levels.scale(waterLevel);

                        if (waterY > prevWaterY + 0.1f) {
                            double rise = waterY - prevWaterY;
                            maxUphillRise = Math.max(maxUphillRise, rise);
                            uphillCount++;
                            System.out.printf("[UPHILL FLOW] Face %d conn r%d c%d -> r%d c%d at t=%.2f: prevY=%.2f, currY=%.2f (rise=%.2f) waterTable=%.4f%n",
                                g.faceIndex, conn.from.row, conn.from.col, conn.to.row, conn.to.col, t, prevWaterY, waterY, rise, cell.waterTable);
                        }
                        prevWaterY = waterY;
                    }
                }
            }
            System.out.printf("Total uphill flow violations: %d, Max uphill rise: %.2f blocks%n", uphillCount, maxUphillRise);
            assertEquals(0, uphillCount, "Total uphill flow violations must be strictly zero");
            assertEquals(0.0, maxUphillRise, 1.0e-3, "Max uphill rise must be strictly 0.0 blocks");
        }
    }

    @Test
    @DisplayName("Generate simulated CONTINENT_UPLIFT preview image")
    void testContinentUpliftPreview() throws IOException {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            int imgSize = 400;
            float zoom = 32.0F; // Zoom factor covering ~12,800 blocks
            float startX = centerX - (imgSize * zoom * 0.5F);
            float startZ = centerZ - (imgSize * zoom * 0.5F);

            BufferedImage img = new BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_RGB);
            Cell cell = new Cell();

            for (int z = 0; z < imgSize; z++) {
                float wz = startZ + z * zoom;
                for (int x = 0; x < imgSize; x++) {
                    float wx = startX + x * zoom;
                    ctx.lookup.applyCell(cell.reset(), (int) wx, (int) wz, false);

                    // Replicate CONTINENT_UPLIFT coloring
                    int rgb;
                    if (cell.terrain.isWateryButNotOcean()) {
                        rgb = 0x288CC8; // Blue river/lake
                    } else if (cell.height <= ctx.levels.water) {
                        rgb = 0x111111; // Ocean black/grey
                    } else {
                        float edgeValue = NoiseUtil.clamp(cell.waterTable, 0.0F, 1.0F);
                        int gray = (int) (edgeValue * 255.0F);
                        rgb = (gray << 16) | (gray << 8) | gray;
                    }
                    img.setRGB(x, z, rgb);
                }
            }

            Path outPath = resolveOutputDir().resolve("simulated_continent_uplift_preview.png");
            ImageIO.write(img, "png", outPath.toFile());
            System.out.println("[Test] Saved simulated CONTINENT_UPLIFT preview to: " + outPath);

            BufferedImage flatnessImg = new BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_RGB);
            for (int z = 0; z < imgSize; z++) {
                float wz = startZ + z * zoom;
                for (int x = 0; x < imgSize; x++) {
                    float wx = startX + x * zoom;
                    ctx.lookup.applyCell(cell.reset(), (int) wx, (int) wz, false);
                    int color = RenderMode.FLATNESS_FACTOR.getColor(cell, ctx.levels, 1.0F, 0.0F);
                    int r = color & 0xFF;
                    int g = (color >> 8) & 0xFF;
                    int b = (color >> 16) & 0xFF;
                    flatnessImg.setRGB(x, z, (r << 16) | (g << 8) | b);
                }
            }
            Path flatnessOutPath = resolveOutputDir().resolve("flatness_factor_continent_preview.png");
            ImageIO.write(flatnessImg, "png", flatnessOutPath.toFile());
            System.out.println("[Test] Saved simulated FLATNESS_FACTOR preview to: " + flatnessOutPath);
        }
    }

    @Test
    @DisplayName("Verify Node Jitter Invariants: Strict Lateral Separation and Uplift Flatness > 0.5")
    void testNodeJitterInvariants() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);
            PlateUpliftBridge bridge = uplift.getPlateUpliftBridge();
            float frequency = uplift.getFrequency();
            PlateUpliftBridge.CellVoronoi voronoi = TriSubdivisionRiverGenerator.findVoronoiCell(bridge, frequency, centerX, centerZ);
            Random random = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs = gen.buildDrainageGraphs(voronoi, frequency, random);

            Cell scratch = new Cell();
            int validatedUpliftNodes = 0;
            int validatedRowPairs = 0;

            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                int upliftRowCount = ContinentalHydrology.NUM_STEPS; // 15
                for (int r = 0; r < g.rows.size(); r++) {
                    List<TriSubdivisionRiverGenerator.DrainageNode> row = g.rows.get(r);
                    boolean isUplift = (r < upliftRowCount);

                    // 1. Invariant: Strict lateral non-overlap between adjacent neighbours on the same row
                    for (int c = 0; c < row.size() - 1; c++) {
                        TriSubdivisionRiverGenerator.DrainageNode left = row.get(c);
                        TriSubdivisionRiverGenerator.DrainageNode right = row.get(c + 1);

                        float dx = right.x - left.x;
                        float dz = right.z - left.z;
                        float dist = (float) Math.sqrt(dx * dx + dz * dz);
                        assertTrue(dist > 20.0F, String.format(
                                "Adjacent nodes on row %d (cols %d and %d) must have positive separation > 20m, got %.2fm",
                                r, c, c + 1, dist));
                        validatedRowPairs++;
                    }

                    // 2. Invariant: A jittered node on non-lowlands areas must never leave flatness band (flatness > 0.5)
                    if (isUplift) {
                        for (TriSubdivisionRiverGenerator.DrainageNode node : row) {
                            if (!node.isCulled) {
                                uplift.apply(scratch.reset(), node.x, node.z);
                                float flatness = ContinentalHydrology.getFlatnessFactor(scratch.waterTable);
                                assertTrue(flatness > 0.5F, String.format(
                                        "Uplift node on row %d, col %d at (%.1f, %.1f) must stay in flatness > 0.5, got %.4f (waterTable=%.4f)",
                                        r, node.col, node.x, node.z, flatness, scratch.waterTable));
                                validatedUpliftNodes++;
                            }
                        }
                    }
                }
            }

            System.out.printf("[Test] Successfully verified %d non-lowland nodes with flatness > 0.5 and %d adjacent row pairs with strict lateral separation%n",
                    validatedUpliftNodes, validatedRowPairs);
            assertTrue(validatedUpliftNodes > 500, "Must validate a substantial number of uplift nodes");
            assertTrue(validatedRowPairs > 500, "Must validate a substantial number of adjacent node pairs");
        }
    }


    private static void collectBuilders(Network.Builder builder, List<Network.Builder> out) {
        out.add(builder);
        for (Network.Builder child : builder.children) {
            collectBuilders(child, out);
        }
    }
}
