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

    // =========================================================================
    // EXPLICIT TUNING KNOBS (GLOBAL CONSTANTS)
    // =========================================================================

    /** Spatial scale/frequency of gully features (smaller = denser, finer gullies). */
    private static final float EROSION_SCALE = 0.04f;

    /** Global strength multiplier applied to FilterSettings.erosionRate. */
    private static final float EROSION_STRENGTH_MULT = 0.35f;

    /** Carving strength/weight of directional gullies (0.0 = smooth peaks, 1.0 = deep sharp gullies). */
    private static final float GULLY_WEIGHT = 0.85f;

    /** Detail exponent restricting high-frequency gullies to steep slopes (higher = steeper only). */
    private static final float GULLY_DETAIL_EXPONENT = 1.4f;

    /** Total number of flow noise octaves layered per cell. */
    private static final int OCTAVES = 5;

    /** Frequency multiplication factor applied per octave. */
    private static final float LACUNARITY = 2.0f;

    /** Amplitude decay factor applied per octave. */
    private static final float GAIN = 0.5f;

    /** Relative cell scale inside Phacelle cellular noise wave grid. */
    private static final float PHACELLE_CELL_SCALE = 0.7f;

    /** Phase wave offset inside Phacelle wave calculation. */
    private static final float PHACELLE_PHASE_OFFSET = 0.25f;

    /** Phacelle vector normalization factor [0.0 = unnormalized, 1.0 = fully normalized]. */
    private static final float PHACELLE_NORMALIZATION = 0.5f;

    /** Minimum slope threshold factor needed to trigger primary gully carving. */
    private static final float SLOPE_ONSET_BASE = 1.25f;

    /** Slope sensitivity multiplier for ridge line network and drainage channel calculation. */
    private static final float SLOPE_ONSET_RIDGE = 2.8f;

    /** Slope threshold multiplier applied across individual octaves. */
    private static final float SLOPE_ONSET_OCTAVE = 1.5f;

    /** Minimum crease/valley floor rounding factor. */
    private static final float ROUNDING_MIN = 0.2f;

    /** Maximum mountain peak/ridge line rounding factor. */
    private static final float ROUNDING_MAX = 0.5f;

    /** Decay multiplier applied to ridge rounding across octaves. */
    private static final float ROUNDING_DECAY = 0.8f;

    // =========================================================================
    // INSTANCE FIELDS
    // =========================================================================

    private final int seed;
    private final int mapSize;
    private final Modifier modifier;

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

        // Map tuned defaults and preset settings
        this.scale = EROSION_SCALE;
        this.strength = settings.erosionRate * EROSION_STRENGTH_MULT;
        this.gullyWeight = GULLY_WEIGHT;
        this.detail = GULLY_DETAIL_EXPONENT;
        this.octaves = OCTAVES;
        this.lacunarity = LACUNARITY;
        this.gain = GAIN;
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

        // Buffer for zero-allocation Phacelle noise output (x, y, dx, dz)
        final float[] phacelleOut = new float[4];

        final int worldBlockX = map.getBlockX();
        final int worldBlockZ = map.getBlockZ();

        // Step 3: Single procedural pass over cell grid
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

                // Relative peak/valley indicator in range [-1.0, 1.0]
                float fadeTarget = NoiseUtil.clamp((baseHeight - 0.5f) * 2.0f, -1.0f, 1.0f);

                float currentStrength = this.strength * this.scale;
                float freq = 1.0f / (this.scale * PHACELLE_CELL_SCALE);

                float slopeLen = (float) Math.sqrt(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1e-10f);

                float roundingForInput = lerp(ROUNDING_MIN, ROUNDING_MAX, NoiseUtil.clamp(fadeTarget + 0.5f, 0.0f, 1.0f));
                float combiMask = easeOut(smoothStart(slopeLen * SLOPE_ONSET_BASE, roundingForInput * SLOPE_ONSET_BASE));

                float ridgeMapCombiMask = easeOut(slopeLen * SLOPE_ONSET_RIDGE);
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
                            PHACELLE_CELL_SCALE, PHACELLE_PHASE_OFFSET, PHACELLE_NORMALIZATION,
                            this.seed + octave,
                            phacelleOut
                    );

                    float phacelleX = phacelleOut[0];
                    float phacelleY = phacelleOut[1];
                    float phacelleZ = phacelleOut[2] * -freq;
                    float phacelleW = phacelleOut[3] * -freq;

                    float sloping = Math.abs(phacelleY);

                    // Accumulate slope directions for subsequent octaves
                    float gullySign = Math.signum(phacelleY);
                    gullySlopeX += gullySign * phacelleZ * currentStrength * this.gullyWeight;
                    gullySlopeZ += gullySign * phacelleW * currentStrength * this.gullyWeight;

                    float fadedGullyVal = lerp(fadeTarget, phacelleX * this.gullyWeight, combiMask);

                    accumulatedHeightDelta += fadedGullyVal * currentStrength;
                    fadeTarget = fadedGullyVal;

                    float roundingOctave = lerp(ROUNDING_MIN, ROUNDING_MAX, NoiseUtil.clamp(phacelleX + 0.5f, 0.0f, 1.0f)) * roundingMult;
                    float newMask = easeOut(smoothStart(sloping * SLOPE_ONSET_BASE, roundingOctave * SLOPE_ONSET_BASE));
                    combiMask = powInv(combiMask, this.detail) * newMask;

                    ridgeMapFadeTarget = lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
                    ridgeMapCombiMask *= easeOut(sloping * SLOPE_ONSET_OCTAVE);

                    currentStrength *= this.gain;
                    freq *= this.lacunarity;
                    roundingMult *= ROUNDING_DECAY;
                }

                // Apply terrain changes and output ridge/drainage channel features
                float change = this.modifier.modify(cell, accumulatedHeightDelta);
                cell.height += change;
                cell.heightErosion += change;
                cell.sediment += ridgeMapFadeTarget * (1.0f - ridgeMapCombiMask);
            }
        }
    }

    /**
     * Samples 2D Phacelle Noise vector aligned with flow direction (normDirX, normDirZ).
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