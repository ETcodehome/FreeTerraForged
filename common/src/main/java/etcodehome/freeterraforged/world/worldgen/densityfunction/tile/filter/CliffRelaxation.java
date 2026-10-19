package etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter;

import java.util.function.IntFunction;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Size;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.util.Seed;

/**
 * Double-buffered (Jacobi) cliff relaxation. Smooths spikes, pits and sheer cliff faces.
 *
 * Uses local relief (max - min over the cell and its 4 neighbors) for the mask so spikes and pits are
 * detected, and a smoothstep ramp so the mask has no hard kinks. Heights are committed to the cells once,
 * after all passes.
 *
 * LATERAL VARIATION: plain relaxation produces the same ramp width along an entire face, which reads as
 * clean and repetitive. Two smooth world-space noise fields vary the filter along the face:
 *  - STRENGTH: scales the relax rate, so a face is softened heavily in one stretch and lightly in the next.
 *  - THRESHOLD: scales the relief thresholds, so some stretches stay sheer while others get relaxed.
 * Both fields use only a broad octave plus a weaker mid octave (no fine grain), so the result is organic
 * variation rather than noise. Noise is sampled in WORLD coordinates, so it is seamless across tiles and
 * deterministic. When the filter runs more than once in the pipeline, give each instance a different salt
 * so the variation patterns do not line up.
 *
 * Self-contained: it only reads and writes cell heights.
 *
 * Units: relief thresholds are in BLOCKS and converted to normalized height using worldHeight, so the
 * result is independent of world height.
 */
public class CliffRelaxation implements Filter {

    // RATE must stay < 0.5 or the filter can oscillate/amplify noise. The varied rate is clamped to MAX_RATE.
    private static final int   CLIFF_PASSES     = 3;
    private static final float CLIFF_RELAX_RATE = 0.4f;
    private static final float MAX_RATE         = 0.5f;
    private static final float MIN_CLIFF_BLOCKS = 3.0f;
    private static final float MAX_CLIFF_BLOCKS = 8.0f;

    // Lateral variation. Set a *_VARIATION to 0 to disable that field.
    /** Wavelength (blocks) of the broad variation octave. Larger = longer stretches of consistent character. */
    private static final float VARIATION_WAVELENGTH_BLOCKS = 28.0f;
    /** Wavelength (blocks) of the finer octave. Keep well above a few blocks or it reads as noise. */
    private static final float DETAIL_WAVELENGTH_BLOCKS = 11.0f;
    /** Weight of the finer octave relative to the broad one (0 = broad only). */
    private static final float DETAIL_WEIGHT = 0.35f;
    /** Relax rate varies by +/- this fraction (0.5 = rate between 50% and 150% of CLIFF_RELAX_RATE). */
    private static final float STRENGTH_VARIATION = 0.5f;
    /** Relief thresholds vary by +/- this fraction (0.35 = thresholds between 65% and 135%). */
    private static final float THRESHOLD_VARIATION = 0.35f;

    // Relaxation fades in from ground level up to ground + this many levels (see Modifier.range / Modifier.modify).
    private static final int RELAX_RAMP_LEVELS = 15;

    private static final int SEED_OFFSET = 7919;

    // Per thread scratch buffers, allocated once and reused across apply() calls.
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final int seed;
    private final int mapSize;
    private final Modifier modifier;
    private final float worldHeight; // blocks

    public CliffRelaxation(
            final int seed,
            final int mapSize,
            final Modifier modifier,
            final Levels levels
    ) {
        this.seed = seed;
        this.mapSize = mapSize;
        this.modifier = modifier;
        this.worldHeight = (float) levels.worldHeight;
    }

    public int getSize() {
        return this.mapSize;
    }

    @Override
    public void apply(Filterable map, int regionX, int regionZ, int iterationsPerChunk) {
        final Size size = map.getBlockSize();
        final int tileSize = size.total();
        final Cell[] cells = map.getBacking();
        final int total = tileSize * tileSize;

        final Scratch scratch = SCRATCH.get();
        scratch.reserve(total);
        final float[] heights = scratch.heights;
        final float[] next = scratch.next;
        final float[] mods = scratch.mods;
        final float[] rateMul = scratch.rateMul;
        final float[] reliefMul = scratch.reliefMul;

        // Pre-extract initial height state into a flat primitive array.
        for (int i = 0; i < total; ++i) {
            heights[i] = cells[i].height;
        }

        final float minRelief = MIN_CLIFF_BLOCKS / this.worldHeight;
        final float maxRelief = MAX_CLIFF_BLOCKS / this.worldHeight;
        final float reliefSpan = Math.max(1e-5f, maxRelief - minRelief);

        // Modifier.modify depends only on per-cell state, and cell.height is not committed until after all
        // passes, so its factor is identical in every pass. Evaluate it once here.
        // 0 marks cells that are masked, non-finite, or have a zero factor: they never change, so the passes
        // skip them without reading their neighbors.
        for (int i = 0; i < total; ++i) {
            final Cell cell = cells[i];
            mods[i] = (cell.erosionMask || !Float.isFinite(heights[i])) ? 0.0f : this.modifier.modify(cell, 1.0F);
        }

        // Per-cell lateral variation, computed once (constant across passes). World-space so tiles match.
        // Only cells that can change need it.
        final int worldBlockX = map.getBlockX();
        final int worldBlockZ = map.getBlockZ();
        final float broadFreq = 1.0f / Math.max(1.0f, VARIATION_WAVELENGTH_BLOCKS);
        final float detailFreq = 1.0f / Math.max(1.0f, DETAIL_WAVELENGTH_BLOCKS);
        for (int z = 0; z < tileSize; ++z) {
            final int row = z * tileSize;
            final float wz = (float) (worldBlockZ + z);
            for (int x = 0; x < tileSize; ++x) {
                final int idx = row + x;
                if (mods[idx] == 0.0f) {
                    rateMul[idx] = 1.0f;
                    reliefMul[idx] = 1.0f;
                    continue;
                }
                final float wx = (float) (worldBlockX + x);
                final float strengthN = centeredNoise(wx, wz, broadFreq, detailFreq, this.seed);
                final float thresholdN = centeredNoise(wx, wz, broadFreq, detailFreq, this.seed + 6007);
                rateMul[idx] = 1.0f + STRENGTH_VARIATION * strengthN;
                reliefMul[idx] = 1.0f + THRESHOLD_VARIATION * thresholdN;
            }
        }

        for (int pass = 0; pass < CLIFF_PASSES; ++pass) {
            for (int z = 0; z < tileSize; ++z) {
                final int row = z * tileSize;
                final int zPrevRow = Math.max(0, z - 1) * tileSize;
                final int zNextRow = Math.min(tileSize - 1, z + 1) * tileSize;

                for (int x = 0; x < tileSize; ++x) {
                    final int idx = row + x;
                    final float c = heights[idx];
                    final float m = mods[idx];

                    if (m == 0.0f) {
                        next[idx] = c;
                        continue;
                    }

                    final float l = heights[row + Math.max(0, x - 1)];
                    final float r = heights[row + Math.min(tileSize - 1, x + 1)];
                    final float d = heights[zPrevRow + x];
                    final float u = heights[zNextRow + x];

                    final float avg = 0.25f * (l + r + d + u);

                    // Symmetric local relief: includes the center, so spikes and pits are detected too.
                    final float hi = Math.max(Math.max(c, l), Math.max(Math.max(r, d), u));
                    final float lo = Math.min(Math.min(c, l), Math.min(Math.min(r, d), u));

                    // Laterally varied thresholds: scaling both ends keeps the ramp shape, shifts where it bites.
                    final float rm = reliefMul[idx];
                    final float t = NoiseUtil.clamp((hi - lo - minRelief * rm) / (reliefSpan * rm), 0.0f, 1.0f);
                    final float mask = t * t * (3.0f - 2.0f * t); // smoothstep, no hard kinks

                    final float rate = Math.min(MAX_RATE, CLIFF_RELAX_RATE * rateMul[idx]);
                    final float delta = (avg - c) * mask * rate;
                    next[idx] = c + m * delta;
                }
            }
            System.arraycopy(next, 0, heights, 0, total);
        }

        // Commit to cells once, after all passes.
        // Cells with a 0 factor were never changed, so there is nothing to commit for them.
        for (int i = 0; i < total; ++i) {
            final float h = heights[i];
            if (mods[i] == 0.0f || !Float.isFinite(h)) {
                continue;
            }
            final Cell cell = cells[i];
            cell.heightErosion += (h - cell.height);
            cell.height = h;
        }
    }

    /**
     * Two-octave smooth noise (broad + weaker finer), returned roughly centered on 0 within [-1, 1].
     */
    private static float centeredNoise(final float wx, final float wz,
                                       final float broadFreq, final float detailFreq, final int seed) {
        final float broad = valueNoise(wx * broadFreq, wz * broadFreq, seed);
        final float detail = valueNoise(wx * detailFreq, wz * detailFreq, seed + 101);
        final float n = (broad + DETAIL_WEIGHT * detail) / (1.0f + DETAIL_WEIGHT); // [0, 1]
        return (n - 0.5f) * 2.0f;
    }

    /** Smooth (smoothstep-interpolated) 2D value noise in [0, 1]. */
    private static float valueNoise(final float px, final float pz, final int seed) {
        final int xi = (int) Math.floor(px);
        final int zi = (int) Math.floor(pz);
        float fx = px - xi;
        float fz = pz - zi;
        fx = fx * fx * (3.0f - 2.0f * fx);
        fz = fz * fz * (3.0f - 2.0f * fz);

        final float v00 = hash01(xi, zi, seed);
        final float v10 = hash01(xi + 1, zi, seed);
        final float v01 = hash01(xi, zi + 1, seed);
        final float v11 = hash01(xi + 1, zi + 1, seed);

        final float a = v00 + fx * (v10 - v00);
        final float b = v01 + fx * (v11 - v01);
        return a + fz * (b - a);
    }

    private static float hash01(final int x, final int z, final int seed) {
        int n = x * 374761393 + z * 668265263 + seed * 144677219;
        n = (n ^ (n >> 13)) * 1274126177;
        n ^= (n >> 16);
        return (n & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    // Per thread scratch storage.
    private static final class Scratch {
        float[] heights = new float[0];
        float[] next = new float[0];      // Jacobi back-buffer
        float[] mods = new float[0];      // Modifier factor per cell, constant across passes
        float[] rateMul = new float[0];   // Per-cell relax rate multiplier (lateral strength variation)
        float[] reliefMul = new float[0]; // Per-cell relief threshold multiplier (lateral threshold variation)

        /** Grow-only: reallocates only when a larger map than any previously seen arrives on this thread. */
        void reserve(final int n) {
            if (this.heights.length < n) {
                this.heights = new float[n];
                this.next = new float[n];
                this.mods = new float[n];
                this.rateMul = new float[n];
                this.reliefMul = new float[n];
            }
        }
    }

    /** Single-instance convenience: salt 0. */
    public static IntFunction<CliffRelaxation> factory(final GeneratorContext context) {
        return factory(context, 0);
    }

    /**
     * @param salt distinguishes multiple instances in one pipeline (e.g. 1 for before erosion, 2 for after)
     *             so their variation patterns do not line up.
     */
    public static IntFunction<CliffRelaxation> factory(final GeneratorContext context, final int salt) {
        return new Factory(Seed.toInt(context.seed.root()) + SEED_OFFSET + salt * 31337, context.levels);
    }

    private static class Factory implements IntFunction<CliffRelaxation> {
        private final int seed;
        private final Modifier modifier;
        private final Levels levels;

        private Factory(final int seed, final Levels levels) {
            this.seed = seed;
            this.modifier = Modifier.range(levels.ground, levels.ground(RELAX_RAMP_LEVELS));
            this.levels = levels;
        }

        @Override
        public CliffRelaxation apply(final int size) {
            return new CliffRelaxation(this.seed, size, this.modifier, this.levels);
        }
    }
}