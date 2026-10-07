package etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter;

import java.util.function.IntFunction;

import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ErosionFilterSettings;
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

    // Per thread scratch buffers, allocated once and reused across apply() calls.
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final int seed;
    private final int mapSize;
    private final Modifier modifier;
    private final float maxFloatHeight;

    private final float scale;
    private final float strength;
    private final float gullyWeight;
    private final float detail;
    private final int octaves;
    private final float lacunarity;
    private final float gain;
    private final ErosionFilterSettings s;

    public Erosion(
            final int seed,
            final int mapSize,
            final Modifier modifier,
            final Levels levels
    ) {
        this.seed = seed;
        this.mapSize = mapSize;
        this.modifier = modifier;

        // Calculate maximum allowed float height derived from terrainScaler
        int terrainScaler = Math.max(1, Math.min(levels.worldHeight, 256));
        this.maxFloatHeight = levels.worldHeight / (float) terrainScaler;

        this.s = PresetManager.PM.erosionFilterSettings;
        this.scale = s.scale;
        this.strength = 0.20F * s.strengthMultiplier;
        this.gullyWeight = s.gullySharpness;
        this.detail = s.gullySlopeAdhesion;
        this.octaves = s.flowOctaves;
        this.lacunarity = s.lacunarity;
        this.gain = s.gain;
    }

    public int getSize() {
        return this.mapSize;
    }

    @Override
    public void apply(Filterable map, int regionX, int regionZ, int iterationsPerChunk) {
        final Size size = map.getBlockSize();
        final int mapSize = size.total();
        final Cell[] cells = map.getBacking();

        // Fetch this thread's scratch buffers, growing them only if this map is larger than any seen so far.
        // NOTE: the arrays may be longer than cells.length, so all loops below are bounded by cells.length / mapSize,
        // never by array.length. Every index in [0, cells.length) is fully written before it is read.
        final Scratch scratch = SCRATCH.get();
        scratch.reserve(cells.length);
        final float[] heights = scratch.heights;
        final float[] slopeX = scratch.slopeX;
        final float[] slopeZ = scratch.slopeZ;
        final float[] phacelleOut = scratch.phacelleOut;

        // Step 1: Pre-extract initial height state into flat primitive array
        for (int i = 0; i < cells.length; ++i) {
            heights[i] = cells[i].height;
        }

        // Step 2: Compute initial slopes with clamped boundary checks
        for (int z = 0; z < mapSize; ++z) {
            final int zPrev = Math.max(0, z - 1);
            final int zNext = Math.min(mapSize - 1, z + 1);
            final float dz = (float) (zNext - zPrev);
            final int row = z * mapSize;

            for (int x = 0; x < mapSize; ++x) {
                final int xPrev = Math.max(0, x - 1);
                final int xNext = Math.min(mapSize - 1, x + 1);
                final float dx = (float) (xNext - xPrev);

                final int idx = row + x;
                slopeX[idx] = (heights[row + xNext] - heights[row + xPrev]) / dx;
                slopeZ[idx] = (heights[zNext * mapSize + x] - heights[zPrev * mapSize + x]) / dz;
            }
        }

        final int worldBlockX = map.getBlockX();
        final int worldBlockZ = map.getBlockZ();

        // Step 3: Single procedural pass over all cells
        for (int z = 0; z < mapSize; ++z) {
            final int row = z * mapSize;
            final float worldZ = (float) (worldBlockZ + z);

            for (int x = 0; x < mapSize; ++x) {
                final int idx = row + x;
                final Cell cell = cells[idx];

                if (cell.erosionMask) {
                    continue;
                }

                final float baseHeight = heights[idx];
                if (Float.isNaN(baseHeight) || Float.isInfinite(baseHeight)) {
                    continue;
                }

                final float worldX = (float) (worldBlockX + x);

                float curSlopeX = slopeX[idx];
                float curSlopeZ = slopeZ[idx];

                // baseHeight is in terrainScaler float units (0.5f ~ midpoint)
                float fadeTarget = NoiseUtil.clamp((baseHeight - 0.5f) * 2.0f, -1.0f, 1.0f);

                float currentStrength = this.strength * this.scale;
                float freq = 1.0f / (this.scale * s.phacelleScale);

                float slopeLen = (float) Math.sqrt(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1e-10f);

                float roundingForInput = lerp(
                        s.roundingMin,
                        s.roundingMax,
                        NoiseUtil.clamp(fadeTarget + 0.5f, 0.0f, 1.0f));
                float combiMask = easeOut(
                        smoothStart(
                                slopeLen * s.slopeOnsetBase,
                                roundingForInput * s.slopeOnsetBase));

                float ridgeMapCombiMask = easeOut(slopeLen * s.slopeOnsetRidge);
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
                            s.phacelleScale,
                            s.phacelleOffset,
                            s.phacelleNormalization,
                            this.seed + octave,
                            phacelleOut
                    );

                    float phacelleX = phacelleOut[0];
                    float phacelleY = phacelleOut[1];
                    float phacelleZ = phacelleOut[2];
                    float phacelleW = phacelleOut[3];

                    float sloping = Math.abs(phacelleY);

                    // Accumulate slope directions for subsequent octaves
                    float gullySign = Math.signum(phacelleY);
                    gullySlopeX += gullySign * phacelleZ * currentStrength * this.gullyWeight;
                    gullySlopeZ += gullySign * phacelleW * currentStrength * this.gullyWeight;

                    float fadedGullyVal = lerp(fadeTarget, phacelleX * this.gullyWeight, combiMask);

                    accumulatedHeightDelta += fadedGullyVal * currentStrength;
                    fadeTarget = fadedGullyVal;

                    float roundingOctave = lerp(
                            s.roundingMin,
                            s.roundingMax,
                            NoiseUtil.clamp(phacelleX + 0.5f, 0.0f, 1.0f)) * roundingMult;
                    float newMask = easeOut(smoothStart(sloping * s.slopeOnsetBase, roundingOctave * s.slopeOnsetBase));
                    combiMask = powInv(combiMask, this.detail) * newMask;

                    ridgeMapFadeTarget = lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
                    ridgeMapCombiMask *= easeOut(sloping * s.slopeOnsetOctave);

                    currentStrength *= this.gain;
                    freq *= this.lacunarity;
                    roundingMult *= s.roundingDecay;
                }

                if (Float.isNaN(accumulatedHeightDelta) || Float.isInfinite(accumulatedHeightDelta)) {
                    continue;
                }

                // Apply terrain changes and clamp within valid float range [0.0f, maxFloatHeight]
                cell.sediment = NoiseUtil.clamp(cell.sediment + ridgeMapFadeTarget * (1.0f - ridgeMapCombiMask), -1.0f, 1.0f);
                float sedimentModifier = 1.0F - cell.sediment;

                float change = this.modifier.modify(cell, accumulatedHeightDelta) * sedimentModifier;
                float newHeight = cell.height + change;
                cell.heightErosion += (newHeight - cell.height);
                cell.height = newHeight;
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
        result[2] = sideDirX / (freq * TAU + 1e-5f);
        result[3] = sideDirZ / (freq * TAU + 1e-5f);
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

    // Per thread scratch storage.
    private static final class Scratch {
        float[] heights = new float[0];
        float[] slopeX = new float[0];
        float[] slopeZ = new float[0];
        final float[] phacelleOut = new float[4];

        /** Grow-only: reallocates only when a larger map than any previously seen arrives on this thread. */
        void reserve(final int n) {
            if (this.heights.length < n) {
                this.heights = new float[n];
                this.slopeX = new float[n];
                this.slopeZ = new float[n];
            }
        }
    }

    public static IntFunction<Erosion> factory(final GeneratorContext context) {
        return new Factory(Seed.toInt(context.seed.root()), context.levels);
    }

    private static class Factory implements IntFunction<Erosion> {
        private static final int SEED_OFFSET = 12768;
        private final int seed;
        private final Modifier modifier;
        private final Levels levels;

        private Factory(final int seed, final Levels levels) {
            this.seed = seed + SEED_OFFSET;
            this.modifier = Modifier.range(levels.ground, levels.ground(15));
            this.levels = levels;
        }

        @Override
        public Erosion apply(final int size) {
            return new Erosion(this.seed, size, this.modifier, this.levels);
        }
    }
}