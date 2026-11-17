package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.PlateUpliftBridge;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
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
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs = gen.buildDrainageGraphs(voronoi, frequency, random);

            assertFalse(graphs.isEmpty(), "At least one unbridged drainage face must be generated");
            System.out.printf("[Test] Generated %d drainage face graphs for continent%n", graphs.size());

            int totalCandidateNodes = 0;
            int totalPopulatedNodes = 0;
            int totalConnections = 0;
            int headwaters = 0;
            int confluences = 0;
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                assertEquals(ContinentalHydrology.NUM_STEPS, g.rows.size(), "Row count must strictly match ContinentalHydrology terracing steps (15)");
                for (List<TriSubdivisionRiverGenerator.DrainageNode> row : g.rows) {
                    totalCandidateNodes += row.size();
                    for (TriSubdivisionRiverGenerator.DrainageNode n : row) {
                        if (n.isPopulated) {
                            totalPopulatedNodes++;
                            if (n.feederCount >= 2) confluences++;
                        }
                    }
                }
                totalConnections += g.connections.size();
                for (TriSubdivisionRiverGenerator.RiverConnection conn : g.connections) {
                    if (conn.isHeadwater) headwaters++;

                    // Verify monotonic gradient descent: from.row < to.row and from.gradient >= to.gradient
                    assertTrue(conn.from.row < conn.to.row, "Successive nodes must strictly head down the row tiers");
                    assertTrue(conn.from.gradient >= conn.to.gradient, "Successive nodes must monotonically descend gradient");
                }
            }

            System.out.printf("[Test] Candidate Nodes: %d, Populated Nodes: %d, River Segments: %d, Headwaters: %d, Confluences: %d%n",
                    totalCandidateNodes, totalPopulatedNodes, totalConnections, headwaters, confluences);
            assertTrue(totalPopulatedNodes > 0, "Expected some nodes to be populated at riverCount = 8");
            assertTrue(totalPopulatedNodes < totalCandidateNodes, "Every node should NOT be connected at riverCount = 8");
            assertEquals(totalPopulatedNodes, totalConnections, "Every populated node should have exactly one downstream connection");
            assertTrue(confluences > 0, "Expected multiple upstream nodes to merge into single downstream nodes (confluences)");

            // Build full Rivermap to verify carver and warp integration
            Rivermap rivermap = gen.generateRivers(centerX, centerZ, PosUtil.pack(centerX, centerZ));
            assertNotNull(rivermap, "Rivermap generation must succeed");

            // Calculate precise bounding box framing the entire Voronoi polygon
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

            float pad = 600.0F;
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
            int bgStep = 6;
            for (int py = 0; py < imgHeight; py += bgStep) {
                float wz = minZ + (py / (float) imgHeight) * (maxZ - minZ);
                for (int px = 0; px < imgWidth; px += bgStep) {
                    float wx = minX + (px / (float) imgWidth) * (maxX - minX);
                    uplift.apply(cell, wx, wz);

                    int rgb;
                    if (cell.waterTable <= 0.001F) {
                        // Ocean
                        rgb = 0x08162B; // deep navy blue
                    } else {
                        // Land shaded by water table gradient (elevation)
                        float t = NoiseUtil.clamp(cell.waterTable, 0.0F, 1.0F);
                        int r = (int) (14 + t * 45);
                        int g = (int) (28 + t * 65);
                        int b = (int) (18 + t * 35);
                        rgb = (r << 16) | (g << 8) | b;
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

                if (voronoi.isBridged[i]) {
                    // Bridged border (land saddle to neighbor continent)
                    g2.setStroke(new BasicStroke(2.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{8.0F, 8.0F}, 0.0F));
                    g2.setColor(new Color(255, 110, 64, 140)); // Red-orange dashed line
                } else {
                    // Coastal boundary to ocean
                    g2.setStroke(new BasicStroke(1.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{4.0F, 4.0F}, 0.0F));
                    g2.setColor(new Color(255, 255, 255, 80)); // White dashed line
                }
                g2.draw(new Line2D.Double(ax, az, bx, bz));
            }

            // Draw radial face boundary rays from peak
            g2.setStroke(new BasicStroke(1.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{3.0F, 6.0F}, 0.0F));
            g2.setColor(new Color(255, 255, 255, 50));
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph fg : graphs) {
                double v1x = mapX.apply(fg.v1X);
                double v1z = mapZ.apply(fg.v1Z);
                double v2x = mapX.apply(fg.v2X);
                double v2z = mapZ.apply(fg.v2Z);
                g2.draw(new Line2D.Double(peakPx, peakPz, v1x, v1z));
                g2.draw(new Line2D.Double(peakPx, peakPz, v2x, v2z));
            }

            // 3. Draw row terrace contours (the plateaus of ContinentalHydrology)
            g2.setStroke(new BasicStroke(1.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{2.0F, 4.0F}, 0.0F));
            g2.setColor(new Color(130, 200, 255, 45));
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph fg : graphs) {
                for (int r = 0; r < fg.rows.size(); r++) {
                    List<TriSubdivisionRiverGenerator.DrainageNode> row = fg.rows.get(r);
                    if (row.size() >= 2) {
                        double xA = mapX.apply(row.get(0).x);
                        double zA = mapZ.apply(row.get(0).z);
                        double xB = mapX.apply(row.get(row.size() - 1).x);
                        double zB = mapZ.apply(row.get(row.size() - 1).z);
                        g2.draw(new Line2D.Double(xA, zA, xB, zB));
                    }
                }
            }

            // 4. Draw Warped River Flow Channels (continuous meandering water paths)
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph fg : graphs) {
                long faceWarpSeed = ((long) fg.faceIndex * 123456789L) ^ worldSeed;
                Random warpRandom = new Random(faceWarpSeed);

                for (TriSubdivisionRiverGenerator.RiverConnection conn : fg.connections) {
                    RiverConfig cfg = conn.isMain ? gen.main : gen.fork;
                    float boundingRadius = cfg.calculateBoundingRadius(ctx.levels);
                    River river = new River(conn.from.x, conn.from.z, conn.to.x, conn.to.z, boundingRadius);
                    RiverWarp riverWarp = RiverWarp.create(0.1F, 0.85F, warpRandom);

                    Path2D.Double warpedPath = new Path2D.Double();
                    int samples = 35;
                    for (int s = 0; s <= samples; s++) {
                        float t = s / (float) samples;
                        float unwarpedX = river.x1 + t * river.dx;
                        float unwarpedZ = river.z1 + t * river.dz;
                        long offset = riverWarp.getOffset(unwarpedX, unwarpedZ, t, river);
                        float wx = unwarpedX + PosUtil.unpackLeftf(offset);
                        float wz = unwarpedZ + PosUtil.unpackRightf(offset);

                        double px = mapX.apply(wx);
                        double pz = mapZ.apply(wz);
                        if (s == 0) {
                            warpedPath.moveTo(px, pz);
                        } else {
                            warpedPath.lineTo(px, pz);
                        }
                    }

                    // Render warped river line
                    if (conn.isMain) {
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
                    g2.draw(new Line2D.Double(x1, z1, x2, z2));

                    // Direction indicator arrow/dot along river
                    double midDotX = (x1 + x2) * 0.5;
                    double midDotZ = (z1 + z2) * 0.5;
                    g2.setColor(new Color(255, 255, 255, 180));
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

                        if (!node.isPopulated) {
                            // Unconnected candidate node (subdivision grid slot)
                            g2.setColor(new Color(148, 163, 184, 85));
                            g2.fill(new Ellipse2D.Double(nx - 2.0, nz - 2.0, 4.0, 4.0));
                            continue;
                        }

                        double radius = (node.feederCount >= 2) ? 6.5 : (node.feederCount == 1 ? 4.5 : 3.5);
                        g2.setColor(nodeCol);
                        g2.fill(new Ellipse2D.Double(nx - radius, nz - radius, radius * 2.0, radius * 2.0));

                        // Confluence marker: bright cyan ring for merged nodes
                        if (node.feederCount >= 2) {
                            g2.setStroke(new BasicStroke(2.0F));
                            g2.setColor(new Color(0, 229, 255));
                            g2.draw(new Ellipse2D.Double(nx - radius - 2.0, nz - radius - 2.0, (radius + 2.0) * 2.0, (radius + 2.0) * 2.0));
                        } else if (node.feederCount == 0) {
                            // Outer white ring for headwaters
                            g2.setStroke(new BasicStroke(1.5F));
                            g2.setColor(Color.WHITE);
                            g2.draw(new Ellipse2D.Double(nx - radius - 1.5, nz - radius - 1.5, (radius + 1.5) * 2.0, (radius + 1.5) * 2.0));
                        }
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
            g2.setColor(new Color(15, 23, 42, 235)); // Slate dark background
            g2.fillRoundRect(25, 25, 540, 380, 16, 16);
            g2.setStroke(new BasicStroke(1.5F));
            g2.setColor(new Color(255, 255, 255, 90));
            g2.drawRoundRect(25, 25, 540, 380, 16, 16);

            g2.setFont(new Font("SansSerif", Font.BOLD, 17));
            g2.setColor(Color.WHITE);
            g2.drawString("FreeTerraForged - TriSubdivision River Network", 45, 56);

            g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g2.setColor(new Color(203, 213, 225));
            g2.drawString(String.format("Centroid Peak: (%d, %d) | Seed: %d", centerX, centerZ, worldSeed), 45, 80);
            g2.drawString(String.format("Active Ocean Faces: %d | Terraces (Rows): %d (15 steps)", graphs.size(), ContinentalHydrology.NUM_STEPS), 45, 98);
            g2.drawString(String.format("Candidate Grid Nodes: %d | Populated River Nodes: %d (riverCount = %d)", totalCandidateNodes, totalPopulatedNodes, preset.rivers().riverCount), 45, 116);
            g2.drawString(String.format("River Segments: %d | Confluences: %d | Headwaters: %d", totalConnections, confluences, headwaters), 45, 134);

            // Legend swatches
            int legendY = 162;

            // Peak
            g2.setColor(new Color(255, 215, 0));
            g2.fillRect(45, legendY - 8, 12, 12);
            g2.setColor(Color.WHITE);
            g2.drawString("Voronoi Centroid Peak (Elevation 1.0)", 68, legendY + 2);

            // Main River
            legendY += 22;
            g2.setStroke(new BasicStroke(4.0F));
            g2.setColor(new Color(0, 229, 255));
            g2.drawLine(45, legendY - 2, 60, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Main River Trunk (High flow / Confluence)", 68, legendY + 2);

            // Headwater / Branch
            legendY += 22;
            g2.setStroke(new BasicStroke(2.2F));
            g2.setColor(new Color(255, 215, 0));
            g2.drawLine(45, legendY - 2, 60, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Headwater Origin (Empty node on terrace row)", 68, legendY + 2);

            // Confluence
            legendY += 22;
            g2.setColor(new Color(0, 229, 255));
            g2.fill(new Ellipse2D.Double(47, legendY - 6, 8, 8));
            g2.setColor(Color.WHITE);
            g2.drawString("Confluence Node (Multiple upstream nodes merged into 1)", 68, legendY + 2);

            // Warped River Flow
            legendY += 22;
            g2.setStroke(new BasicStroke(2.5F));
            g2.setColor(new Color(128, 222, 234));
            g2.drawLine(45, legendY - 2, 60, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Warped River Flow (Domain simplex frequency warp)", 68, legendY + 2);

            // Terrace Rows
            legendY += 22;
            g2.setStroke(new BasicStroke(1.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{3.0F, 4.0F}, 0.0F));
            g2.setColor(new Color(130, 200, 255));
            g2.drawLine(45, legendY - 2, 60, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("ContinentalHydrology Flat Plateau Terraces (15 Rows)", 68, legendY + 2);

            // Unconnected Candidate Node
            legendY += 22;
            g2.setColor(new Color(148, 163, 184, 180));
            g2.fill(new Ellipse2D.Double(49, legendY - 4, 4, 4));
            g2.setColor(Color.WHITE);
            g2.drawString("Unconnected Candidate Node (Terrace grid slot - not connected)", 68, legendY + 2);

            // Bridged Border
            legendY += 22;
            g2.setStroke(new BasicStroke(2.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{6.0F, 6.0F}, 0.0F));
            g2.setColor(new Color(255, 110, 64));
            g2.drawLine(45, legendY - 2, 60, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Bridged Tectonic Border (Connected supercontinent landmass)", 68, legendY + 2);

            // Coastal Ocean Boundary
            legendY += 22;
            g2.setStroke(new BasicStroke(1.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0F, new float[]{4.0F, 4.0F}, 0.0F));
            g2.setColor(new Color(255, 255, 255));
            g2.drawLine(45, legendY - 2, 60, legendY - 2);
            g2.setColor(Color.WHITE);
            g2.drawString("Open Coastal Ocean Boundary", 68, legendY + 2);

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

            // 2. Test riverCount = 30: every node populated, every node connected
            Random random30 = new Random(PosUtil.pack(centerX, centerZ) + gen.seed);
            List<TriSubdivisionRiverGenerator.FaceDrainageGraph> graphs30 = gen.buildDrainageGraphs(voronoi, frequency, random30, 30);
            int candidate30 = 0;
            int populated30 = 0;
            int connections30 = 0;
            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs30) {
                connections30 += g.connections.size();
                for (List<TriSubdivisionRiverGenerator.DrainageNode> row : g.rows) {
                    candidate30 += row.size();
                    for (TriSubdivisionRiverGenerator.DrainageNode n : row) {
                        if (n.isPopulated) populated30++;
                    }
                }
            }
            assertTrue(candidate30 > 0, "Candidate nodes must exist");
            assertEquals(candidate30, populated30, "At riverCount = 30, every node must be populated");
            assertEquals(candidate30, connections30, "At riverCount = 30, every node must have a connection");
        }
    }
}
