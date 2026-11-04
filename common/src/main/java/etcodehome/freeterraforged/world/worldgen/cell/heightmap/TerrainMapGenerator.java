package etcodehome.freeterraforged.world.worldgen.cell.heightmap;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

public final class TerrainMapGenerator {

    public static final int DEFAULT_CHUNK_SIZE = 512;

    public enum MapType {
        RAW_HEIGHTMAP,
        SHADED_RELIEF
    }

    public record ChunkProgress(int chunkX, int chunkZ, int totalChunksX, int totalChunksZ) {}

    public record ImageValidationReport(
        boolean passed,
        int width,
        int height,
        int minSample,
        int maxSample,
        double meanSample,
        int dynamicRange,
        int distinctColors,
        int nonZeroPixels,
        double nonZeroFraction,
        List<String> violations
    ) {}

    public record SampledArea(
        int minX, int minZ, int maxX, int maxZ,
        int width, int height,
        int[][] blockElevations,
        float[][] normElevations,
        Map<String, Integer> terrainCounts,
        int minBlockElevation,
        int maxBlockElevation,
        double meanBlockElevation,
        int nanOrInfCount
    ) {
        public BufferedImage toRawHeightmap() {
            return renderRawHeightmap(normElevations, width, height);
        }

        public BufferedImage toReliefMap(int waterLevel) {
            return renderReliefImage(blockElevations, waterLevel, width, height);
        }
    }

    private TerrainMapGenerator() {}

    /**
     * Core sampling method to extract terrain elevation, normalized heights, and composition
     * across an arbitrary bounding box using the active GeneratorContext.
     */
    public static SampledArea sampleArea(GeneratorContext ctx, int minX, int minZ, int maxX, int maxZ) {
        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;

        float[][] normElevations = new float[width][height];
        int[][] blockElevations = new int[width][height];
        Map<String, Integer> terrainCounts = new HashMap<>();

        int nanOrInfCount = 0;
        int minBlockElevation = Integer.MAX_VALUE;
        int maxBlockElevation = Integer.MIN_VALUE;
        double sumBlockElevation = 0.0;

        Cell cell = new Cell();
        for (int z = 0; z < height; z++) {
            int worldZ = minZ + z;
            for (int x = 0; x < width; x++) {
                int worldX = minX + x;
                ctx.lookup.applyCell(cell.reset(), worldX, worldZ, false, false);
                terrainCounts.merge(cell.terrain.getName(), 1, Integer::sum);

                float norm = ctx.levels.getNormalizedElevation(cell.height);
                if (Float.isNaN(norm) || Float.isInfinite(norm)) {
                    nanOrInfCount++;
                    norm = 0.0F;
                }

                float clampedNorm = Math.clamp(norm, 0.0F, 1.0F);
                normElevations[x][z] = clampedNorm;

                int blockY = ctx.levels.scale(cell.height);
                blockElevations[x][z] = blockY;

                minBlockElevation = Math.min(minBlockElevation, blockY);
                maxBlockElevation = Math.max(maxBlockElevation, blockY);
                sumBlockElevation += blockY;
            }
        }

        double meanBlockElevation = (width * height > 0) ? (sumBlockElevation / (width * height)) : 0.0;

        return new SampledArea(
            minX, minZ, maxX, maxZ, width, height,
            blockElevations, normElevations, terrainCounts,
            minBlockElevation, maxBlockElevation, meanBlockElevation, nanOrInfCount
        );
    }

    /**
     * Renders a 16-bit raw grayscale heightmap image from normalized elevation array.
     */
    public static BufferedImage renderRawHeightmap(float[][] normElevations, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster raster = img.getRaster();
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int gray = (int) (Math.clamp(normElevations[x][z], 0.0F, 1.0F) * 65535.0F);
                raster.setSample(x, z, 0, gray);
            }
        }
        return img;
    }

    /**
     * Computes shaded hypsometric relief RGB for a single cell given its elevation, neighbor heights,
     * and active world water level.
     */
    public static int computeReliefRGB(int y, int left, int right, int up, int down, int waterLevel) {
        float dx = (right - left) * 0.5F;
        float dz = (down - up) * 0.5F;
        // Directional hillshade from North-West (-1, -1)
        float shade = (float) ((-dx - dz) / Math.sqrt(dx * dx + dz * dz + 4.0) * 0.35 + 1.0);
        shade = Math.clamp(shade, 0.5F, 1.5F);

        int r, g, b;
        if (y <= waterLevel) {
            // Water depth gradient
            float depth = Math.clamp((waterLevel - y) / 30.0F, 0.0F, 1.0F);
            r = (int) ((30 * depth + 45 * (1.0F - depth)) * shade);
            g = (int) ((70 * depth + 115 * (1.0F - depth)) * shade);
            b = (int) ((140 * depth + 195 * (1.0F - depth)) * shade);
        } else if (y <= waterLevel + 4) {
            // Coastal sandy beach
            r = (int) (210 * shade);
            g = (int) (200 * shade);
            b = (int) (140 * shade);
        } else if (y <= waterLevel + 60) {
            // Lowlands & lush green plains
            float t = (y - (waterLevel + 4)) / 56.0F;
            r = (int) ((60 * (1.0F - t) + 110 * t) * shade);
            g = (int) ((150 * (1.0F - t) + 160 * t) * shade);
            b = (int) ((50 * (1.0F - t) + 70 * t) * shade);
        } else if (y <= waterLevel + 160) {
            // Foothills, highlands & plateaus
            float t = (y - (waterLevel + 60)) / 100.0F;
            r = (int) ((110 * (1.0F - t) + 140 * t) * shade);
            g = (int) ((160 * (1.0F - t) + 130 * t) * shade);
            b = (int) ((70 * (1.0F - t) + 100 * t) * shade);
        } else {
            // Mountain rock to snow-capped peaks
            float t = Math.clamp((y - (waterLevel + 160)) / 80.0F, 0.0F, 1.0F);
            r = (int) ((130 * (1.0F - t) + 240 * t) * shade);
            g = (int) ((125 * (1.0F - t) + 245 * t) * shade);
            b = (int) ((120 * (1.0F - t) + 250 * t) * shade);
        }

        r = Math.clamp(r, 0, 255);
        g = Math.clamp(g, 0, 255);
        b = Math.clamp(b, 0, 255);

        return (r << 16) | (g << 8) | b;
    }

    /**
     * Renders a 24-bit RGB shaded relief map from a block elevation grid with continuous edge clamping.
     */
    public static BufferedImage renderReliefImage(int[][] blockElevations, int waterLevel, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int y = blockElevations[x][z];
                int left = blockElevations[Math.max(0, x - 1)][z];
                int right = blockElevations[Math.min(width - 1, x + 1)][z];
                int up = blockElevations[x][Math.max(0, z - 1)];
                int down = blockElevations[x][Math.min(height - 1, z + 1)];
                img.setRGB(x, z, computeReliefRGB(y, left, right, up, down, waterLevel));
            }
        }
        return img;
    }

    /**
     * Chunked 16-bit raw heightmap generation pipeline supporting arbitrary bounding boxes and progress notification.
     */
    public static BufferedImage generateHeightmapImage(
            GeneratorContext ctx,
            int minX, int minZ, int maxX, int maxZ,
            int chunkSize,
            Consumer<ChunkProgress> progressConsumer
    ) {
        int totalW = maxX - minX + 1;
        int totalH = maxZ - minZ + 1;
        int chunksX = (totalW + chunkSize - 1) / chunkSize;
        int chunksZ = (totalH + chunkSize - 1) / chunkSize;

        BufferedImage full = new BufferedImage(totalW, totalH, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster fullRaster = full.getRaster();
        Cell cell = new Cell();

        for (int cx = 0; cx < chunksX; cx++) {
            for (int cz = 0; cz < chunksZ; cz++) {
                int startX = minX + cx * chunkSize;
                int startZ = minZ + cz * chunkSize;
                int w = Math.min(chunkSize, totalW - cx * chunkSize);
                int h = Math.min(chunkSize, totalH - cz * chunkSize);

                for (int x = 0; x < w; x++) {
                    int worldX = startX + x;
                    int targetX = cx * chunkSize + x;
                    for (int z = 0; z < h; z++) {
                        int worldZ = startZ + z;
                        int targetZ = cz * chunkSize + z;
                        ctx.lookup.applyCell(cell.reset(), worldX, worldZ, false, false);
                        float height = ctx.levels.getNormalizedElevation(cell.height);
                        int gray = (int) (Math.clamp(height, 0.0F, 1.0F) * 65535.0F);
                        fullRaster.setSample(targetX, targetZ, 0, gray);
                    }
                }

                if (progressConsumer != null) {
                    progressConsumer.accept(new ChunkProgress(cx, cz, chunksX, chunksZ));
                }
            }
        }

        return full;
    }

    /**
     * Chunked 24-bit RGB shaded relief map generation pipeline with seamless 1-block neighbor sampling
     * across chunk boundaries for continuous gradient shading.
     */
    public static BufferedImage generateReliefImage(
            GeneratorContext ctx,
            int minX, int minZ, int maxX, int maxZ,
            int chunkSize,
            Consumer<ChunkProgress> progressConsumer
    ) {
        int totalW = maxX - minX + 1;
        int totalH = maxZ - minZ + 1;
        int chunksX = (totalW + chunkSize - 1) / chunkSize;
        int chunksZ = (totalH + chunkSize - 1) / chunkSize;

        BufferedImage full = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        int waterLevel = ctx.levels.waterLevel;
        Cell cell = new Cell();

        for (int cx = 0; cx < chunksX; cx++) {
            for (int cz = 0; cz < chunksZ; cz++) {
                int startX = minX + cx * chunkSize;
                int startZ = minZ + cz * chunkSize;
                int w = Math.min(chunkSize, totalW - cx * chunkSize);
                int h = Math.min(chunkSize, totalH - cz * chunkSize);

                // Sample 1-block neighbor margin across chunk bounds for continuous gradient
                int sampleMinX = Math.max(minX, startX - 1);
                int sampleMaxX = Math.min(maxX, startX + w);
                int sampleMinZ = Math.max(minZ, startZ - 1);
                int sampleMaxZ = Math.min(maxZ, startZ + h);

                int sampleW = sampleMaxX - sampleMinX + 1;
                int sampleH = sampleMaxZ - sampleMinZ + 1;
                int[][] heights = new int[sampleW][sampleH];

                for (int sx = 0; sx < sampleW; sx++) {
                    int worldX = sampleMinX + sx;
                    for (int sz = 0; sz < sampleH; sz++) {
                        int worldZ = sampleMinZ + sz;
                        ctx.lookup.applyCell(cell.reset(), worldX, worldZ, false, false);
                        heights[sx][sz] = ctx.levels.scale(cell.height);
                    }
                }

                for (int x = 0; x < w; x++) {
                    int worldX = startX + x;
                    int targetX = cx * chunkSize + x;
                    int lx = worldX - sampleMinX;
                    int leftLx = Math.max(minX, worldX - 1) - sampleMinX;
                    int rightLx = Math.min(maxX, worldX + 1) - sampleMinX;

                    for (int z = 0; z < h; z++) {
                        int worldZ = startZ + z;
                        int targetZ = cz * chunkSize + z;
                        int lz = worldZ - sampleMinZ;
                        int upLz = Math.max(minZ, worldZ - 1) - sampleMinZ;
                        int downLz = Math.min(maxZ, worldZ + 1) - sampleMinZ;

                        int y = heights[lx][lz];
                        int left = heights[leftLx][lz];
                        int right = heights[rightLx][lz];
                        int up = heights[lx][upLz];
                        int down = heights[lx][downLz];

                        int rgb = computeReliefRGB(y, left, right, up, down, waterLevel);
                        full.setRGB(targetX, targetZ, rgb);
                    }
                }

                if (progressConsumer != null) {
                    progressConsumer.accept(new ChunkProgress(cx, cz, chunksX, chunksZ));
                }
            }
        }

        return full;
    }

    /**
     * Deterministically asserts that an image is valid, non-null, matches expected dimensions,
     * and is neither completely pitch-black, blank, nor a solid monochromatic artifact.
     */
    public static ImageValidationReport validateImageContent(
            BufferedImage image,
            MapType type,
            int expectedWidth,
            int expectedHeight
    ) {
        List<String> violations = new ArrayList<>();
        if (image == null) {
            violations.add("Image is null (decoding failed or image could not be loaded)");
            return new ImageValidationReport(false, 0, 0, 0, 0, 0.0, 0, 0, 0, 0.0, violations);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        if (width != expectedWidth || height != expectedHeight) {
            violations.add(String.format("Dimension mismatch: expected %dx%d, got %dx%d", expectedWidth, expectedHeight, width, height));
        }

        int totalPixels = width * height;
        if (totalPixels == 0) {
            violations.add("Image has 0 total pixels");
            return new ImageValidationReport(false, width, height, 0, 0, 0.0, 0, 0, 0, 0.0, violations);
        }

        int minSample = Integer.MAX_VALUE;
        int maxSample = Integer.MIN_VALUE;
        double sumSample = 0.0;
        int nonZeroPixels = 0;
        Set<Integer> uniqueColors = new HashSet<>();

        if (type == MapType.RAW_HEIGHTMAP) {
            WritableRaster raster = image.getRaster();
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    int gray = raster.getSample(x, z, 0);
                    if (gray > 0) {
                        nonZeroPixels++;
                    }
                    minSample = Math.min(minSample, gray);
                    maxSample = Math.max(maxSample, gray);
                    sumSample += gray;
                    uniqueColors.add(gray);
                }
            }
        } else {
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    int rgb = image.getRGB(x, z) & 0x00FFFFFF;
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    int luminance = (int) (0.299 * r + 0.587 * g + 0.114 * b);

                    if (rgb != 0) {
                        nonZeroPixels++;
                    }
                    minSample = Math.min(minSample, luminance);
                    maxSample = Math.max(maxSample, luminance);
                    sumSample += luminance;
                    uniqueColors.add(rgb);
                }
            }
        }

        double meanSample = sumSample / totalPixels;
        int dynamicRange = maxSample - minSample;
        double nonZeroFraction = (double) nonZeroPixels / totalPixels;

        // Assertion 1: Black image detection
        if (nonZeroPixels == 0) {
            violations.add("Failed image validation: Image is completely pitch black (100% 0-value pixels)");
        } else if (nonZeroFraction < 0.50) {
            violations.add(String.format("Failed image validation: Abnormally high black pixel ratio (%.1f%% black pixels)", (1.0 - nonZeroFraction) * 100.0));
        }

        // Assertion 2: Minimum brightness threshold
        double minAllowableMean = (type == MapType.RAW_HEIGHTMAP) ? 1000.0 : 15.0;
        if (meanSample < minAllowableMean) {
            violations.add(String.format("Failed image validation: Mean brightness %.2f is below minimum threshold %.2f (underexposed/black)", meanSample, minAllowableMean));
        }

        // Assertion 3: Dynamic range / solid flat color detection
        if (dynamicRange == 0) {
            violations.add(String.format("Failed image validation: Flat solid uniform image with 0 dynamic range (uniform sample %d across all pixels)", minSample));
        } else if (uniqueColors.size() <= 1) {
            violations.add(String.format("Failed image validation: Monochromatic blank image with only %d distinct color(s)", uniqueColors.size()));
        }

        boolean passed = violations.isEmpty();
        return new ImageValidationReport(
            passed, width, height, minSample, maxSample, meanSample,
            dynamicRange, uniqueColors.size(), nonZeroPixels, nonZeroFraction, violations
        );
    }

    /**
     * Asserts bit-for-bit pixel parity between expected and actual images.
     */
    public static void assertImageParity(BufferedImage expected, BufferedImage actual, String context) {
        if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
            throw new AssertionError(String.format(
                "[%s] Dimension mismatch: expected %dx%d, got %dx%d",
                context, expected.getWidth(), expected.getHeight(), actual.getWidth(), actual.getHeight()
            ));
        }
        int w = expected.getWidth();
        int h = expected.getHeight();
        int mismatches = 0;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                if (expected.getRGB(x, z) != actual.getRGB(x, z)) {
                    mismatches++;
                }
            }
        }
        if (mismatches > 0) {
            throw new AssertionError(String.format(
                "[%s] Image parity failure: %d / %d pixels (%.2f%%) do not match expected core generator output!",
                context, mismatches, w * h, (mismatches * 100.0) / (w * h)
            ));
        }
    }
}
