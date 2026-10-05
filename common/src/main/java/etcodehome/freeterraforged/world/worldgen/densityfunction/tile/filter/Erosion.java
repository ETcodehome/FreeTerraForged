package etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter;

import java.util.function.IntFunction;

import etcodehome.freeterraforged.data.worldgen.preset.settings.FilterSettings;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Size;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.util.Seed;

/**
 * Single-pass procedural erosion filter based on Runevision's Phacelle Noise algorithm.
 * Replaces iterative hydraulic droplet simulations with direct analytical evaluation.
 */
public class Erosion implements Filter {
    private final int seed;
    private final int mapSize;
    private final Modifier modifier;

    // Filter Tuning Parameters derived from settings
    private final float scale;
    private final float strength;
    private final float gullyWeight;
    private final float detail;
    private final int octaves;
    private final float lacunarity;
    private final float gain;

    public Erosion(final int seed, final int mapSize, final FilterSettings.Erosion settings, final Modifier modifier) {
        this.seed = seed;
        this.mapSize = mapSize;
        this.modifier = modifier;

        // Map settings to procedural erosion controls
        this.scale = 0.04f;
        this.strength = settings.erosionRate * 0.35f;
        this.gullyWeight = 0.85f;
        this.detail = 1.4f;
        this.octaves = 5;
        this.lacunarity = 2.0f;
        this.gain = 0.5f;
    }

    public int getSize() {
        return this.mapSize;
    }

    @Override
    public void apply(Filterable map, int regionX, int regionZ, int iterationsPerChunk) {
        final Size size = map.getBlockSize();
        final int mapSize = size.total();
        final Cell[] cells = map.getBacking();

        // Step 1: Pre-extract initial height state into flat primitive array
        final float[] heights = new float[cells.length];
        for (int i = 0; i < cells.length; ++i) {
            heights[i] = cells[i].height;
        }

        // Step 2: Compute initial slopes via central differences
        final float[] slopeX = new float[cells.length];
        final float[] slopeZ = new float[cells.length];

        for (int z = 1; z < mapSize - 1; ++z) {
            final int row = z * mapSize;
            for (int x = 1; x < mapSize - 1; ++x) {
                final int idx = row + x;
                slopeX[idx] = (heights[idx + 1] - heights[idx - 1]) * 0.5f;
                slopeZ[idx] = (heights[idx + mapSize] - heights[idx - mapSize]) * 0.5f;
            }
        }

        // Temporary thread-safe result buffer to avoid allocations inside loop
        final float[] phacelleOut = new float[4];

        final int worldBlockX = map.getBlockX();
        final int worldBlockZ = map.getBlockZ();

        // Step 3: Single procedural pass over the cell grid
        for (int z = 1; z < mapSize - 1; ++z) {
            final int row = z * mapSize;
            final float worldZ = (float) (worldBlockZ + z);

            for (int x = 1; x < mapSize - 1; ++x) {
                final int idx = row + x;
                final Cell cell = cells[idx];

                if (cell.erosionMask) {
                    continue;
                }

                final float baseHeight = heights[idx];
                final float worldX = (float) (worldBlockX + x);

                float curSlopeX = slopeX[idx];
                float curSlopeZ = slopeZ[idx];

                // Peak/Valley fade target indicator in range [-1.0, 1.0]
                float fadeTarget = NoiseUtil.clamp((baseHeight - 0.5f) * 2.0f, -1.0f, 1.0f);

                float currentStrength = this.strength * this.scale;
                float freq = 1.0f / (this.scale * 0.7f);

                float slopeLen = (float) Math.sqrt(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1e-10f);

                float roundingForInput = lerp(0.2f, 0.5f, NoiseUtil.clamp(fadeTarget + 0.5f, 0.0f, 1.0f));
                float combiMask = easeOut(smoothStart(slopeLen * 1.25f, roundingForInput * 1.25f));

                float ridgeMapCombiMask = easeOut(slopeLen * 2.8f);
                float ridgeMapFadeTarget = fadeTarget;

                float gullySlopeX = curSlopeX;
                float gullySlopeZ = curSlopeZ;

                float accumulatedHeightDelta = 0.0f;
                float roundingMult = 1.0f;

                // Multi-octave flow erosion iteration
                for (int octave = 0; octave < this.octaves; ++octave) {
                    float gullyLen = (float) Math.sqrt(gullySlopeX * gullySlopeX + gullySlopeZ * gullySlopeZ);
                    float normGullyX = gullyLen > 1e-10f ? gullySlopeX / gullyLen : 0.0f;
                    float normGullyZ = gullyLen > 1e-10f ? gullySlopeZ / gullyLen : 0.0f;

                    samplePhacelleNoise(
                            worldX * freq, worldZ * freq,
                            normGullyX, normGullyZ,
                            0.7f, 0.25f, 0.5f, this.seed + octave,
                            phacelleOut
                    );

                    float phacelleX = phacelleOut[0];
                    float phacelleY = phacelleOut[1];
                    float phacelleZ = phacelleOut[2] * -freq;
                    float phacelleW = phacelleOut[3] * -freq;

                    float sloping = Math.abs(phacelleY);

                    // Update gully directions for upcoming octaves
                    float gullySign = Math.signum(phacelleY);
                    gullySlopeX += gullySign * phacelleZ * currentStrength * this.gullyWeight;
                    gullySlopeZ += gullySign * phacelleW * currentStrength * this.gullyWeight;

                    float fadedGullyVal = lerp(fadeTarget, phacelleX * this.gullyWeight, combiMask);

                    accumulatedHeightDelta += fadedGullyVal * currentStrength;
                    fadeTarget = fadedGullyVal;

                    float roundingOctave = lerp(0.2f, 0.5f, NoiseUtil.clamp(phacelleX + 0.5f, 0.0f, 1.0f)) * roundingMult;
                    float newMask = easeOut(smoothStart(sloping * 1.25f, roundingOctave * 1.25f));
                    combiMask = powInv(combiMask, this.detail) * newMask;

                    ridgeMapFadeTarget = lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
                    ridgeMapCombiMask *= easeOut(sloping * 1.5f);

                    currentStrength *= this.gain;
                    freq *= this.lacunarity;
                    roundingMult *= 0.8f;
                }

                // Apply erosion height changes and store ridge/drainage channel features
                float change = this.modifier.modify(cell, accumulatedHeightDelta);
                cell.height += change;
                cell.heightErosion += change;
                cell.sediment += ridgeMapFadeTarget * (1.0f - ridgeMapCombiMask);
            }
        }
    }

    /**
     * Evaluates 2D Phacelle Noise aligned with direction vector (normDirX, normDirZ).
     */
    private static void samplePhacelleNoise(
            float px, float pz,
            float normDirX, float normDirZ,
            float freq, float offset, float normalization, int seed,
            float[] result
    ) {
        final float TAU = 6.283185307179586f;
        float sideDirX = -normDirZ * freq * TAU;
        float sideDirZ = normDirX * freq * TAU;
        offset *= TAU;

        int pIntX = (int) Math.floor(px);
        int pIntZ = (int) Math.floor(pz);
        float pFracX = px - pIntX;
        float pFracZ = pz - pIntZ;

        float phaseDirX = 0.0f;
        float phaseDirZ = 0.0f;
        float weightSum = 0.0f;

        for (int i = -1; i <= 2; ++i) {
            for (int j = -1; j <= 2; ++j) {
                int cellX = pIntX + i;
                int cellZ = pIntZ + j;

                float hashX = hash(cellX, cellZ, seed) * 0.5f;
                float hashZ = hash(cellX, cellZ, seed + 1013) * 0.5f;

                float vecX = pFracX - i - hashX;
                float vecZ = pFracZ - j - hashZ;

                float sqrDist = vecX * vecX + vecZ * vecZ;
                float weight = Math.max(0.0f, (float) Math.exp(-sqrDist * 2.0f) - 0.01111f);

                weightSum += weight;
                float waveInput = vecX * sideDirX + vecZ * sideDirZ + offset;
                phaseDirX += (float) Math.cos(waveInput) * weight;
                phaseDirZ += (float) Math.sin(waveInput) * weight;
            }
        }

        float invWeight = 1.0f / Math.max(weightSum, 1e-5f);
        float interpX = phaseDirX * invWeight;
        float interpZ = phaseDirZ * invWeight;

        float magnitude = (float) Math.sqrt(interpX * interpX + interpZ * interpZ);
        magnitude = Math.max(1.0f - normalization, magnitude);

        result[0] = interpX / magnitude;
        result[1] = interpZ / magnitude;
        result[2] = sideDirX;
        result[3] = sideDirZ;
    }

    private static float hash(int x, int z, int seed) {
        int n = x * 374761393 + z * 668265263 + seed * 144677219;
        n = (n ^ (n >> 13)) * 1274126177;
        return ((n ^ (n >> 16)) & 0x7FFFFFFF) / (float) 0x7FFFFFFF - 0.5f;
    }

    private static float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    private static float easeOut(float t) {
        float v = 1.0f - NoiseUtil.clamp(t, 0.0f, 1.0f);
        return 1.0f - v * v;
    }

    private static float powInv(float t, float power) {
        return 1.0f - (float) Math.pow(1.0f - NoiseUtil.clamp(t, 0.0f, 1.0f), power);
    }

    private static float smoothStart(float t, float smoothing) {
        if (t >= smoothing) {
            return t - 0.5f * smoothing;
        }
        return 0.5f * t * t / Math.max(smoothing, 1e-5f);
    }

    public static IntFunction<Erosion> factory(final GeneratorContext context) {
        return new Factory(Seed.toInt(context.seed.root()), context.preset.filters(), context.levels);
    }

    private static class Factory implements IntFunction<Erosion> {
        private static final int SEED_OFFSET = 12768;
        private final int seed;
        private final Modifier modifier;
        private final FilterSettings.Erosion settings;

        private Factory(final int seed, final FilterSettings filters, final Levels levels) {
            this.seed = seed + SEED_OFFSET;
            this.settings = filters.erosion.copy();
            this.modifier = Modifier.range(levels.ground, levels.ground(15));
        }

        @Override
        public Erosion apply(final int size) {
            return new Erosion(this.seed, size, this.settings, this.modifier);
        }
    }
}