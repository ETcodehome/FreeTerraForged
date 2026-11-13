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

    private static final String ARTIFACT_DIR = "C:/Users/Edward/.gemini/antigravity/brain/e4fadc74-fd9c-46da-8e59-353e78a5fc62";

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

            // Save images to artifact directory
            Path artifactPath = Paths.get(ARTIFACT_DIR);
            Files.createDirectories(artifactPath);

            File reliefFile = artifactPath.resolve("mountain_river_cut_relief.png").toFile();
            File rawFile = artifactPath.resolve("mountain_river_cut_raw.png").toFile();

            ImageIO.write(relief, "png", reliefFile);
            ImageIO.write(raw, "png", rawFile);

            System.out.printf("[Test] Saved relief map to: %s%n", reliefFile.getAbsolutePath());
            System.out.printf("[Test] Saved raw heightmap to: %s%n", rawFile.getAbsolutePath());

            // 1. Generate Spectator 3D Perspective View (100 blocks up looking down into the river canyon)
            File spectatorFile = artifactPath.resolve("mountain_river_cut_spectator_3d.png").toFile();
            BufferedImage spectatorView = renderSpectatorPerspective(area, levels.waterLevel);
            ImageIO.write(spectatorView, "png", spectatorFile);
            System.out.printf("[Test] Saved spectator 3D view to: %s%n", spectatorFile.getAbsolutePath());

            // 2. Generate 3D Isometric Voxel Model
            File isometricFile = artifactPath.resolve("mountain_river_cut_isometric_3d.png").toFile();
            BufferedImage isometricView = renderIsometric3D(area, levels.waterLevel);
            ImageIO.write(isometricView, "png", isometricFile);
            System.out.printf("[Test] Saved 3D isometric view to: %s%n", isometricFile.getAbsolutePath());

            assertTrue(reliefFile.exists() && reliefFile.length() > 0, "Relief map image file must exist");
            assertTrue(spectatorFile.exists() && spectatorFile.length() > 0, "Spectator 3D view file must exist");
            assertTrue(isometricFile.exists() && isometricFile.length() > 0, "Isometric 3D view file must exist");

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

            // With smooth slope-enforced fadeout and height-adaptive terracing, steps in river valleys are natural (< 25 blocks)
            assertTrue(maxStepDelta <= 25, "Single block step delta should not exceed natural slopes (found " + maxStepDelta + ")");
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
            Path artifactPath = Paths.get(ARTIFACT_DIR);
            File largeBankFile = artifactPath.resolve("large_bank_width_45_relief.png").toFile();
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
            Path artifactPath = Paths.get(ARTIFACT_DIR);
            File bank200File = artifactPath.resolve("bank_width_200_relief.png").toFile();
            ImageIO.write(relief, "png", bank200File);
            System.out.printf("[Test] Saved bankWidth=200 relief map to: %s%n", bank200File.getAbsolutePath());
        }
    }

    /**
     * Renders a 3D perspective spectator camera view looking down into the mountain river valley
     * from ~100 blocks above the river bed.
     */
    private static BufferedImage renderSpectatorPerspective(SampledArea area, int waterLevel) {
        int width = 960;
        int height = 540;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int w = area.width();
        int h = area.height();
        int[][] elevations = area.blockElevations();

        // Precompute relief shading colors
        int[][] colors = new int[w][h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int y = elevations[x][z];
                int left = elevations[Math.max(0, x - 1)][z];
                int right = elevations[Math.min(w - 1, x + 1)][z];
                int up = elevations[x][Math.max(0, z - 1)];
                int down = elevations[x][Math.min(h - 1, z + 1)];
                colors[x][z] = TerrainMapGenerator.computeReliefRGB(y, left, right, up, down, waterLevel);
            }
        }

        // Camera spectator position: 100 blocks above river, looking down the valley towards center
        double cx = 135.0;
        double cy = 168.0; // River floor is at Y ~62, so camera is ~106 blocks above river floor
        double cz = 135.0;

        double tx = 256.0;
        double ty = 65.0;
        double tz = 256.0;

        // Camera basis vectors
        double fwdX = tx - cx;
        double fwdY = ty - cy;
        double fwdZ = tz - cz;
        double fwdLen = Math.sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ);
        fwdX /= fwdLen; fwdY /= fwdLen; fwdZ /= fwdLen;

        // Right vector (cross product of forward and world up [0, 1, 0])
        double rX = fwdZ;
        double rY = 0;
        double rZ = -fwdX;
        double rLen = Math.sqrt(rX * rX + rZ * rZ);
        rX /= rLen; rZ /= rLen;

        // Up vector (cross product of right and forward)
        double uX = rY * fwdZ - rZ * fwdY;
        double uY = rZ * fwdX - rX * fwdZ;
        double uZ = rX * fwdY - rY * fwdX;

        double aspect = (double) width / height;
        double fovScale = Math.tan(Math.toRadians(75.0) / 2.0);

        for (int py = 0; py < height; py++) {
            double v = (0.5 - (py + 0.5) / height) * 2.0 * fovScale;
            // Sky gradient calculation
            float skyFactor = (float) Math.clamp((0.55 - (double) py / height), 0.0, 1.0);
            int skyR = (int) (140 * (1.0f - skyFactor) + 85 * skyFactor);
            int skyG = (int) (185 * (1.0f - skyFactor) + 135 * skyFactor);
            int skyB = (int) (245 * (1.0f - skyFactor) + 230 * skyFactor);
            int skyColor = (skyR << 16) | (skyG << 8) | skyB;

            for (int px = 0; px < width; px++) {
                double u = ((px + 0.5) / width - 0.5) * 2.0 * aspect * fovScale;

                double dX = fwdX + u * rX + v * uX;
                double dY = fwdY + u * rY + v * uY;
                double dZ = fwdZ + u * rZ + v * uZ;
                double dLen = Math.sqrt(dX * dX + dY * dY + dZ * dZ);
                dX /= dLen; dY /= dLen; dZ /= dLen;

                // Raymarch heightfield
                double t = 2.0;
                double maxT = 450.0;
                boolean hit = false;
                int hitX = 0, hitZ = 0;
                boolean hitWater = false;

                while (t < maxT) {
                    double sx = cx + dX * t;
                    double sy = cy + dY * t;
                    double sz = cz + dZ * t;

                    int ix = (int) Math.round(sx);
                    int iz = (int) Math.round(sz);

                    if (ix < 0 || ix >= w || iz < 0 || iz >= h) {
                        break;
                    }

                    int terrainY = elevations[ix][iz];

                    if (sy <= waterLevel && terrainY <= waterLevel) {
                        hit = true;
                        hitWater = true;
                        hitX = ix;
                        hitZ = iz;
                        break;
                    }

                    if (sy <= terrainY) {
                        hit = true;
                        hitX = ix;
                        hitZ = iz;
                        break;
                    }

                    double distAbove = sy - terrainY;
                    double step = Math.max(0.65, distAbove * 0.45);
                    t += step;
                }

                if (hit) {
                    int surfaceColor = hitWater ? 0x24588C : colors[hitX][hitZ];
                    // Atmospheric distance haze/fog
                    float fog = (float) Math.clamp((t - 120.0) / 280.0, 0.0, 1.0);
                    int r = (int) (((surfaceColor >> 16) & 0xFF) * (1.0f - fog) + skyR * fog);
                    int g = (int) (((surfaceColor >> 8) & 0xFF) * (1.0f - fog) + skyG * fog);
                    int b = (int) ((surfaceColor & 0xFF) * (1.0f - fog) + skyB * fog);
                    img.setRGB(px, py, (r << 16) | (g << 8) | b);
                } else {
                    img.setRGB(px, py, skyColor);
                }
            }
        }

        return img;
    }

    /**
     * Renders a 3D isometric voxel model of the sampled area showing the mountain river cut.
     */
    private static BufferedImage renderIsometric3D(SampledArea area, int waterLevel) {
        int width = 1024;
        int height = 768;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int w = area.width();
        int h = area.height();
        int[][] elevations = area.blockElevations();

        // Precompute relief colors
        int[][] colors = new int[w][h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int y = elevations[x][z];
                int left = elevations[Math.max(0, x - 1)][z];
                int right = elevations[Math.min(w - 1, x + 1)][z];
                int up = elevations[x][Math.max(0, z - 1)];
                int down = elevations[x][Math.min(h - 1, z + 1)];
                colors[x][z] = TerrainMapGenerator.computeReliefRGB(y, left, right, up, down, waterLevel);
            }
        }

        // Subsample by factor of 2 so 256x256 voxel blocks fit nicely in 1024x768
        int stride = 2;
        int halfW = 2;
        int halfH = 1;
        int blockW = 4;
        int blockH = 2;
        int centerX = width / 2;
        int centerY = height / 2 + 70;
        float heightScale = 1.35f;

        // Render back-to-front (isometric painter's algorithm)
        for (int z = 0; z < h; z += stride) {
            int dz = (z - h / 2) / stride;
            for (int x = 0; x < w; x += stride) {
                int dx = (x - w / 2) / stride;
                int elev = elevations[x][z];

                int isoX = centerX + (dx - dz) * halfW;
                int isoY = centerY + (dx + dz) * halfH;
                int renderY = isoY - Math.round((elev - waterLevel) * heightScale);

                int topColor = colors[x][z];
                int leftColor = darkenRgb(topColor, 0.72f);
                int rightColor = darkenRgb(topColor, 0.55f);

                // Top face
                fillRect(img, isoX, renderY, isoX + blockW, renderY + blockH, topColor);
                // Left face
                fillRect(img, isoX, renderY + blockH, isoX + halfW, isoY + blockH, leftColor);
                // Right face
                fillRect(img, isoX + halfW, renderY + blockH, isoX + blockW, isoY + blockH, rightColor);
            }
        }

        return img;
    }

    private static void fillRect(BufferedImage img, int x0, int y0, int x1, int y1, int rgb) {
        int minX = Math.max(0, x0);
        int maxX = Math.min(img.getWidth(), x1);
        int minY = Math.max(0, y0);
        int maxY = Math.min(img.getHeight(), y1);
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                img.setRGB(x, y, rgb);
            }
        }
    }

    private static int darkenRgb(int rgb, float factor) {
        int r = Math.clamp((int) (((rgb >> 16) & 0xFF) * factor), 0, 255);
        int g = Math.clamp((int) (((rgb >> 8) & 0xFF) * factor), 0, 255);
        int b = Math.clamp((int) ((rgb & 0xFF) * factor), 0, 255);
        return (r << 16) | (g << 8) | b;
    }
}
