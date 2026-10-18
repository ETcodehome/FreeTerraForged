package etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter;

import java.util.function.IntFunction;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Size;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;

/**
 * Double-buffered (Jacobi) cliff relaxation pre-pass. Smooths spikes, pits and sheer cliff faces so the
 * Erosion filter that runs after it operates on already-smoothed terrain.
 *
 * Uses local relief (max - min over the cell and its 4 neighbors) for the mask so spikes and pits are
 * detected, and a smoothstep ramp so the mask has no hard kinks. Heights are committed to the cells once,
 * after all passes.
 *
 * This filter must run BEFORE {@link Erosion}. It also records {@code Cell.preErosionHeight} (the height
 * before any relaxation or erosion), which Erosion uses for its waterline check.
 *
 * Units: relief thresholds are in BLOCKS and converted to normalized height using worldHeight, so the
 * result is independent of world height.
 */
public class CliffRelaxation implements Filter {

    // RATE must stay < 0.5 or the filter can oscillate/amplify noise.
    private static final int   CLIFF_PASSES     = 3;
    private static final float CLIFF_RELAX_RATE = 0.4f;
    private static final float MIN_CLIFF_BLOCKS = 3.0f;
    private static final float MAX_CLIFF_BLOCKS = 8.0f;

    // Relaxation fades in from ground level up to ground + this many levels (see Modifier.range / Modifier.modify).
    // Kept separate from Erosion's ramp so the two can be tuned independently; defaults match.
    private static final int RELAX_RAMP_LEVELS = 15;

    // Per thread scratch buffers, allocated once and reused across apply() calls.
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final int mapSize;
    private final Modifier modifier;
    private final float worldHeight; // blocks

    public CliffRelaxation(
            final int mapSize,
            final Modifier modifier,
            final Levels levels
    ) {
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
        scratch.reserve(cells.length);
        final float[] heights = scratch.heights;
        final float[] next = scratch.next;
        final float[] mods = scratch.mods;

        // Store the pre-erosion heights (before relaxation), used by Erosion's waterline check.
        for (Cell cell : cells) {
            cell.preErosionHeight = cell.height;
        }

        // Pre-extract initial height state into a flat primitive array.
        for (int i = 0; i < cells.length; ++i) {
            heights[i] = cells[i].height;
        }

        final float minRelief = MIN_CLIFF_BLOCKS / this.worldHeight;
        final float maxRelief = MAX_CLIFF_BLOCKS / this.worldHeight;
        final float invRange = 1.0f / Math.max(1e-5f, maxRelief - minRelief);

        // Modifier.modify depends only on per-cell state, and cell.height is not committed until after all
        // passes, so its factor is identical in every pass. Evaluate it once here instead of once per pass.
        // 0 marks cells that are masked, non-finite, or have a zero factor: they never change, so the passes
        // skip them without reading their neighbors.
        for (int i = 0; i < total; ++i) {
            final Cell cell = cells[i];
            mods[i] = (cell.erosionMask || !Float.isFinite(heights[i])) ? 0.0f : this.modifier.modify(cell, 1.0F);
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

                    final float t = NoiseUtil.clamp((hi - lo - minRelief) * invRange, 0.0f, 1.0f);
                    final float mask = t * t * (3.0f - 2.0f * t); // smoothstep, no hard kinks

                    final float delta = (avg - c) * mask * CLIFF_RELAX_RATE;
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

    // Per thread scratch storage.
    private static final class Scratch {
        float[] heights = new float[0];
        float[] next = new float[0];  // Jacobi back-buffer
        float[] mods = new float[0];  // Modifier factor per cell, constant across relaxation passes

        /** Grow-only: reallocates only when a larger map than any previously seen arrives on this thread. */
        void reserve(final int n) {
            if (this.heights.length < n) {
                this.heights = new float[n];
                this.next = new float[n];
                this.mods = new float[n];
            }
        }
    }

    public static IntFunction<CliffRelaxation> factory(final GeneratorContext context) {
        return new Factory(context.levels);
    }

    private static class Factory implements IntFunction<CliffRelaxation> {
        private final Modifier modifier;
        private final Levels levels;

        private Factory(final Levels levels) {
            this.modifier = Modifier.range(levels.ground, levels.ground(RELAX_RAMP_LEVELS));
            this.levels = levels;
        }

        @Override
        public CliffRelaxation apply(final int size) {
            return new CliffRelaxation(size, this.modifier, this.levels);
        }
    }
}