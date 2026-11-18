package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.PlateUpliftBridge;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LakeElevationTest {

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

    private static class LakeRecord {
        final int faceIndex;
        final int row;
        final int col;
        final float nodeX;
        final float nodeZ;
        final int minWaterY;
        final int maxWaterY;
        final int lakeCellCount;

        LakeRecord(int faceIndex, int row, int col, float nodeX, float nodeZ, int minWaterY, int maxWaterY, int lakeCellCount) {
            this.faceIndex = faceIndex;
            this.row = row;
            this.col = col;
            this.nodeX = nodeX;
            this.nodeZ = nodeZ;
            this.minWaterY = minWaterY;
            this.maxWaterY = maxWaterY;
            this.lakeCellCount = lakeCellCount;
        }

        boolean isStepped() {
            return minWaterY != maxWaterY;
        }
    }

    @Test
    @DisplayName("Verify Every Lake Surface Has Uniform, Single-Elevation Water Level (Zero Steps)")
    void testEveryLakeSurfaceIsSingleElevation() throws IOException {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            Levels levels = ctx.levels;
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

            Cell cell = new Cell();
            List<LakeRecord> lakeRecords = new ArrayList<>();
            int totalLakeNodes = 0;

            for (TriSubdivisionRiverGenerator.FaceDrainageGraph g : graphs) {
                for (int r = 0; r < g.rows.size(); r++) {
                    List<TriSubdivisionRiverGenerator.DrainageNode> row = g.rows.get(r);
                    for (int col = 0; col < row.size(); col++) {
                        TriSubdivisionRiverGenerator.DrainageNode node = row.get(col);
                        if (!node.isLake) {
                            continue;
                        }

                        totalLakeNodes++;
                        int minWaterY = Integer.MAX_VALUE;
                        int maxWaterY = Integer.MIN_VALUE;
                        int lakeCellCount = 0;

                        // Flood-fill contiguous lake water starting from the lake center
                        int lakeCenterX = Math.round(node.x);
                        int lakeCenterZ = Math.round(node.z);
                        ctx.lookup.applyCell(cell.reset(), lakeCenterX, lakeCenterZ, false, false);
                        if (!cell.terrain.isLake() || cell.height > cell.riverWaterLevel) {
                            for (int dz = -12; dz <= 12 && (!cell.terrain.isLake() || cell.height > cell.riverWaterLevel); dz += 2) {
                                for (int dx = -12; dx <= 12 && (!cell.terrain.isLake() || cell.height > cell.riverWaterLevel); dx += 2) {
                                    ctx.lookup.applyCell(cell.reset(), lakeCenterX + dx, lakeCenterZ + dz, false, false);
                                    if (cell.terrain.isLake() && cell.height <= cell.riverWaterLevel) {
                                        lakeCenterX += dx;
                                        lakeCenterZ += dz;
                                        break;
                                    }
                                }
                            }
                        }

                        if (cell.terrain.isLake() && cell.height <= cell.riverWaterLevel) {
                            java.util.Set<Long> visited = new java.util.HashSet<>();
                            java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                            long startPos = PosUtil.pack(lakeCenterX, lakeCenterZ);
                            queue.add(startPos);
                            visited.add(startPos);

                            while (!queue.isEmpty()) {
                                long pos = queue.poll();
                                int px = PosUtil.unpackLeft(pos);
                                int pz = PosUtil.unpackRight(pos);
                                float dSq = (px - node.x) * (px - node.x) + (pz - node.z) * (pz - node.z);
                                if (dSq > 100.0F * 100.0F) continue;

                                ctx.lookup.applyCell(cell.reset(), px, pz, false, false);
                                if (cell.terrain.isLake() && cell.height <= cell.riverWaterLevel) {
                                    int wy = levels.scale(cell.riverWaterLevel);
                                    minWaterY = Math.min(minWaterY, wy);
                                    maxWaterY = Math.max(maxWaterY, wy);
                                    lakeCellCount++;

                                    int[] dxs = {2, -2, 0, 0};
                                    int[] dzs = {0, 0, 2, -2};
                                    for (int d = 0; d < 4; d++) {
                                        long nextPos = PosUtil.pack(px + dxs[d], pz + dzs[d]);
                                        if (visited.add(nextPos)) {
                                            queue.add(nextPos);
                                        }
                                    }
                                }
                            }
                        }

                        if (lakeCellCount > 0) {
                            lakeRecords.add(new LakeRecord(g.faceIndex, r, col, node.x, node.z, minWaterY, maxWaterY, lakeCellCount));
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("================================================================================\n");
            sb.append("FREE TERRAFORGED: LAKE ELEVATION UNIFORMITY & FLATNESS REPORT\n");
            sb.append("================================================================================\n\n");
            sb.append(String.format("Found %d total lake nodes across default continent, %d carved lake basins sampled.%n%n", totalLakeNodes, lakeRecords.size()));

            System.out.printf("[LakeElevationTest] Found %d total lake nodes, %d carved lake basins.%n", totalLakeNodes, lakeRecords.size());
            int steppedCount = 0;
            for (LakeRecord record : lakeRecords) {
                if (record.isStepped()) {
                    steppedCount++;
                    String err = String.format("  [STEPPED LAKE ERROR] Face %d Node (r%d, c%d) at (%.1f, %.1f): size=%d samples, waterY=[%d .. %d] (delta=%d blocks)",
                            record.faceIndex, record.row, record.col, record.nodeX, record.nodeZ, record.lakeCellCount, record.minWaterY, record.maxWaterY, record.maxWaterY - record.minWaterY);
                    System.out.println(err);
                    sb.append(err).append("\n");
                } else {
                    String ok = String.format("  [FLAT LAKE OK] Face %d Node (r%d, c%d) at (%.1f, %.1f): size=%d samples, waterY=%d blocks",
                            record.faceIndex, record.row, record.col, record.nodeX, record.nodeZ, record.lakeCellCount, record.minWaterY);
                    System.out.println(ok);
                    sb.append(ok).append("\n");
                }
            }

            sb.append("\n================================================================================\n");
            sb.append(String.format("Summary: Total Sampled Basins: %d | Stepped Lakes: %d | Flat Planar Lakes: %d%n",
                    lakeRecords.size(), steppedCount, lakeRecords.size() - steppedCount));
            sb.append(String.format("Outcome: %s%n", (steppedCount == 0) ? "ALL LAKE SURFACES FLAT & UNIFORM (ZERO STEPS)" : "STEPPED LAKE DEFECT DETECTED"));
            sb.append("================================================================================\n");

            Path reportDir = resolveOutputDir();
            Files.createDirectories(reportDir);
            Path reportFile = reportDir.resolve("lake_elevation_report.txt");
            Files.writeString(reportFile, sb.toString());
            System.out.println("[LakeElevationTest] Saved lake elevation report to: " + reportFile);

            assertTrue(lakeRecords.size() > 0, "Should have discovered carved lakes across the continent");
            assertEquals(0, steppedCount, "Every lake surface must be flat with 0 elevation steps across its entire body! Found " + steppedCount + " stepped lakes.");
        }
    }
}
