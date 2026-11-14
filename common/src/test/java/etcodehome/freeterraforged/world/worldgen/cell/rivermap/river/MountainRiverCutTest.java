package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;

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
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainMapGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainMapGenerator.SampledArea;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MountainRiverCutTest {

    private static Path resolveOutputDir() {
        String prop = System.getProperty("freeterraforged.test.heightmap.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        String env = System.getenv("FTF_TEST_OUTPUT_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of("build", "reports", "preset-test-heightmaps").toAbsolutePath().normalize();
    }

    @BeforeAll
    static void setUpRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("Generate Shaded Relief Heightmap of Mountain Range Cut by River")
    void testGenerateMountainRiverCutImage() throws IOException {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();

        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long seed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, seed)) {
            Levels levels = ctx.levels;
            Cell cell = new Cell();

            // Find an impressive mountain range cut by a river
            int bestX = 0;
            int bestZ = 0;
            double maxScore = -1.0;

            long centerPacked = ctx.heightmap.continent().getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);
            System.out.printf("[Test] Continent center: (%d, %d)%n", centerX, centerZ);

            // Rivers radiate outward from continent center towards ocean.
            // Scan around the continent center out to 3000 blocks to find where a river cuts through mountains.
            int step = 32;
            int scanRadius = 3500;
            int riverSampleCount = 0;
            int mountainSampleCount = 0;

            for (int rz = -scanRadius; rz <= scanRadius; rz += step) {
                int z = centerZ + rz;
                for (int rx = -scanRadius; rx <= scanRadius; rx += step) {
                    int x = centerX + rx;
                    ctx.lookup.applyCell(cell.reset(), x, z, false, false);
                    boolean isRiver = cell.terrain.isRiver()
                            || cell.riverZone == RiverCarverSettings.RiverZone.Riverbed
                            || cell.riverZone == RiverCarverSettings.RiverZone.Banks
                            || cell.riverWaterLevel > levels.water;
                    boolean isMtn = cell.terrain.isMountain() || levels.scale(cell.height) > 130;

                    if (isRiver) riverSampleCount++;
                    if (isMtn) mountainSampleCount++;

                    if (isRiver) {
                        int riverY = levels.scale(cell.height);
                        int maxNeighborY = 0;

                        for (int dz = -300; dz <= 300; dz += 50) {
                            for (int dx = -300; dx <= 300; dx += 50) {
                                Cell neighbor = new Cell();
                                ctx.lookup.applyCell(neighbor, x + dx, z + dz, false, false);
                                int ny = levels.scale(neighbor.height);
                                if (ny > maxNeighborY) {
                                    maxNeighborY = ny;
                                }
                            }
                        }

                        int drop = maxNeighborY - riverY;
                        if (drop > maxScore) {
                            maxScore = drop;
                            bestX = x;
                            bestZ = z;
                        }
                    }
                }
            }

            System.out.printf("[Test] Scanned area: river samples=%d, mountain samples=%d, best drop=%.1f at (%d, %d)%n",
                    riverSampleCount, mountainSampleCount, maxScore, bestX, bestZ);

            System.out.printf("[Test] Best mountain river cut found at (%d, %d) with elevation drop of %.1f blocks%n", bestX, bestZ, maxScore);
            assertTrue(maxScore > 50, "Should find a significant mountain river cut");

            ctx.lookup.applyCell(cell.reset(), bestX, bestZ, false, false);
            System.out.printf("[Test] At best cut (%d, %d): height=%d, riverZone=%s, riverMask=%.3f%n",
                    bestX, bestZ, levels.scale(cell.height), cell.riverZone, cell.riverMask);

            // Sample a 512x512 area centered on this mountain river cut
            int sampleRadius = 256;
            int minX = bestX - sampleRadius;
            int minZ = bestZ - sampleRadius;
            int maxX = bestX + sampleRadius - 1;
            int maxZ = bestZ + sampleRadius - 1;

            SampledArea area = TerrainMapGenerator.sampleArea(ctx, minX, minZ, maxX, maxZ);
            System.out.printf("[Test Large BankWidth=45] Terrain counts: %s%n", area.terrainCounts());
            BufferedImage relief = area.toReliefMap(levels.waterLevel);
            BufferedImage raw = area.toRawHeightmap();

            assertNotNull(relief);
            assertNotNull(raw);

            // Save images to output directory
            Path outputDir = resolveOutputDir();
            Files.createDirectories(outputDir);

            File reliefFile = outputDir.resolve("mountain_river_cut_relief.png").toFile();
            File rawFile = outputDir.resolve("mountain_river_cut_raw.png").toFile();

            ImageIO.write(relief, "png", reliefFile);
            ImageIO.write(raw, "png", rawFile);

            System.out.printf("[Test] Saved relief map to: %s%n", reliefFile.getAbsolutePath());
            System.out.printf("[Test] Saved raw heightmap to: %s%n", rawFile.getAbsolutePath());

            assertTrue(reliefFile.exists() && reliefFile.length() > 0, "Relief map image file must exist");
            assertTrue(rawFile.exists() && rawFile.length() > 0, "Raw heightmap image file must exist");

            // Verify continuity: check that there are no vertical seam cliffs (> 35 block single-block steps)
            int maxStepDelta = 0;
            int maxStepX = 0, maxStepZ = 0;
            int[][] elevations = area.blockElevations();
            int w = area.width();
            int h = area.height();

            for (int rz = 0; rz < h; rz++) {
                for (int rx = 0; rx < w; rx++) {
                    int cur = elevations[rx][rz];
                    if (rx + 1 < w) {
                        int diff = Math.abs(cur - elevations[rx + 1][rz]);
                        if (diff > maxStepDelta) {
                            maxStepDelta = diff;
                            maxStepX = minX + rx;
                            maxStepZ = minZ + rz;
                        }
                    }
                    if (rz + 1 < h) {
                        int diff = Math.abs(cur - elevations[rx][rz + 1]);
                        if (diff > maxStepDelta) {
                            maxStepDelta = diff;
                            maxStepX = minX + rx;
                            maxStepZ = minZ + rz;
                        }
                    }
                }
            }

            ctx.lookup.applyCell(cell.reset(), maxStepX, maxStepZ, false, false);
            System.out.printf("[Test] Area min elevation: %d, max elevation: %d, max single-block step: %d blocks at (%d, %d) [terrain=%s, riverZone=%s, riverMask=%.3f, y=%d]%n",
                    area.minBlockElevation(), area.maxBlockElevation(), maxStepDelta,
                    maxStepX, maxStepZ, cell.terrain.getName(), cell.riverZone, cell.riverMask, levels.scale(cell.height));

            int maxValleyFloorStep = 0;
            int maxCarvedStepDelta = 0;
            int maxCarvedX = 0, maxCarvedZ = 0;
            for (int rz = 0; rz < h; rz++) {
                for (int rx = 0; rx < w; rx++) {
                    int x = minX + rx;
                    int z = minZ + rz;
                    Cell c = new Cell();
                    ctx.lookup.applyCell(c.reset(), x, z, false, false);
                    if (c.riverZone != RiverCarverSettings.RiverZone.None) {
                        int cur = elevations[rx][rz];
                        if (rx + 1 < w) {
                            int diff = Math.abs(cur - elevations[rx + 1][rz]);
                            if (diff > maxCarvedStepDelta) {
                                maxCarvedStepDelta = diff;
                                maxCarvedX = x;
                                maxCarvedZ = z;
                            }
                            if (c.riverZone != RiverCarverSettings.RiverZone.ValleyFadeout) {
                                maxValleyFloorStep = Math.max(maxValleyFloorStep, diff);
                            }
                        }
                        if (rz + 1 < h) {
                            int diff = Math.abs(cur - elevations[rx][rz + 1]);
                            if (diff > maxCarvedStepDelta) {
                                maxCarvedStepDelta = diff;
                                maxCarvedX = x;
                                maxCarvedZ = z;
                            }
                            if (c.riverZone != RiverCarverSettings.RiverZone.ValleyFadeout) {
                                maxValleyFloorStep = Math.max(maxValleyFloorStep, diff);
                            }
                        }
                    }
                }
            }
            System.out.printf("[Test] Max single-block step strictly within carved river zones: %d blocks at (%d, %d), valley floor/banks max step: %d blocks%n",
                    maxCarvedStepDelta, maxCarvedX, maxCarvedZ, maxValleyFloorStep);

            // With smooth slope-enforced fadeout, steps across the area do not exceed natural terrain slopes (<= 35 blocks)
            // and within the riverbed/banks/valley floor remain completely continuous (<= 15 blocks).
            assertTrue(maxStepDelta <= 35, "Single block step delta should not exceed natural slopes (found " + maxStepDelta + ")");
            assertTrue(maxValleyFloorStep <= 15, "Riverbed, banks, and valley floor must remain continuous and walkable (found " + maxValleyFloorStep + ")");
        }
    }

    @Test
    @DisplayName("Verify BankWidth > 35 Produces Seamless Mountain Cuts (Immune to Issue)")
    void testLargeBankWidthSeamContinuity() throws IOException {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();

        // Set bank width to 45 (well above 35 threshold that previously caused vertical seams)
        preset.rivers().mainRivers.bankWidth = 45;
        preset.rivers().branchRivers.bankWidth = 35;

        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long seed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, seed)) {
            Levels levels = ctx.levels;

            // Sample an area with a large bank width
            int minX = -400;
            int minZ = -400;
            int maxX = 400;
            int maxZ = 400;

            SampledArea area = TerrainMapGenerator.sampleArea(ctx, minX, minZ, maxX, maxZ);
            System.out.printf("[Test Large BankWidth=45] Terrain counts: %s%n", area.terrainCounts());

            int maxStepDelta = 0;
            int[][] elevations = area.blockElevations();
            int w = area.width();
            int h = area.height();

            for (int rz = 0; rz < h; rz++) {
                for (int rx = 0; rx < w; rx++) {
                    int cur = elevations[rx][rz];
                    if (rx + 1 < w) {
                        maxStepDelta = Math.max(maxStepDelta, Math.abs(cur - elevations[rx + 1][rz]));
                    }
                    if (rz + 1 < h) {
                        maxStepDelta = Math.max(maxStepDelta, Math.abs(cur - elevations[rx][rz + 1]));
                    }
                }
            }

            System.out.printf("[Test Large BankWidth=45] Max single-block step: %d blocks%n", maxStepDelta);
            // Must have NO vertical seam steps (> 35 blocks)
            assertTrue(maxStepDelta <= 15, "Large bankWidth=45 must remain completely seamless (found max step " + maxStepDelta + ")");

            // Save relief image for bankWidth=45 as well
            BufferedImage relief = area.toReliefMap(levels.waterLevel);
            Path outputDir = resolveOutputDir();
            Files.createDirectories(outputDir);
            File largeBankFile = outputDir.resolve("large_bank_width_45_relief.png").toFile();
            ImageIO.write(relief, "png", largeBankFile);
            System.out.printf("[Test] Saved large bankWidth=45 relief map to: %s%n", largeBankFile.getAbsolutePath());
        }
    }

    @Test
    @DisplayName("Verify Extreme BankWidth = 200 Produces Completely Seamless Generation Without Clipping")
    void testExtremeBankWidth200SeamContinuity() throws IOException {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();

        // Test extreme bank width = 200
        preset.rivers().mainRivers.bankWidth = 200;
        preset.rivers().branchRivers.bankWidth = 100;

        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long seed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, seed)) {
            Levels levels = ctx.levels;

            // Sample an area with bankWidth = 200
            int minX = -400;
            int minZ = -400;
            int maxX = 400;
            int maxZ = 400;

            SampledArea area = TerrainMapGenerator.sampleArea(ctx, minX, minZ, maxX, maxZ);
            System.out.printf("[Test BankWidth=200] Terrain counts: %s%n", area.terrainCounts());

            int maxStepDelta = 0;
            int[][] elevations = area.blockElevations();
            int w = area.width();
            int h = area.height();

            for (int rz = 0; rz < h; rz++) {
                for (int rx = 0; rx < w; rx++) {
                    int cur = elevations[rx][rz];
                    if (rx + 1 < w) {
                        maxStepDelta = Math.max(maxStepDelta, Math.abs(cur - elevations[rx + 1][rz]));
                    }
                    if (rz + 1 < h) {
                        maxStepDelta = Math.max(maxStepDelta, Math.abs(cur - elevations[rx][rz + 1]));
                    }
                }
            }

            System.out.printf("[Test BankWidth=200] Max single-block step: %d blocks%n", maxStepDelta);
            // Must have NO vertical seam steps (> 35 blocks)
            assertTrue(maxStepDelta <= 15, "Extreme bankWidth=200 must remain completely seamless (found max step " + maxStepDelta + ")");

            // Save relief image for bankWidth=200 as well
            BufferedImage relief = area.toReliefMap(levels.waterLevel);
            Path outputDir = resolveOutputDir();
            Files.createDirectories(outputDir);
            File bank200File = outputDir.resolve("bank_width_200_relief.png").toFile();
            ImageIO.write(relief, "png", bank200File);
            System.out.printf("[Test] Saved bankWidth=200 relief map to: %s%n", bank200File.getAbsolutePath());
        }
    }
}
