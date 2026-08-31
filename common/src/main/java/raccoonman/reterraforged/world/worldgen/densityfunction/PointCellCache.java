package raccoonman.reterraforged.world.worldgen.densityfunction;

import it.unimi.dsi.fastutil.HashCommon;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;

import java.util.Arrays;
import java.lang.ref.WeakReference;

/**
 * A strictly zero-allocation, thread-local cache for ReTerraForged cell math.
 */
public final class PointCellCache {
    private static final ThreadLocal<PointCellCache> LOCAL_CACHE = ThreadLocal.withInitial(PointCellCache::new);
    private static final int CACHE_SIZE = 4096; // Must be a power of two
    private static final int MASK = CACHE_SIZE - 1;

    private WeakReference<WorldLookup> boundLookup = new WeakReference<>(null);
    private final long[] keys = new long[CACHE_SIZE];
    private final Cell[] cells = new Cell[CACHE_SIZE];

    private PointCellCache() {
        for (int i = 0; i < CACHE_SIZE; i++) {
            this.cells[i] = new Cell();
        }
        Arrays.fill(this.keys, Long.MIN_VALUE);
    }

    public static void fill(WorldLookup lookup, int blockX, int blockZ, Cell target) {
        LOCAL_CACHE.get().fillInternal(lookup, blockX, blockZ, target);
    }

    private void fillInternal(WorldLookup lookup, int blockX, int blockZ, Cell target) {
        // Validates environment to prevent state-leaking across restarts
        if (this.boundLookup.get() != lookup) {
            this.rebind(lookup);
        }

        // Removed the ~3 masking. We now use EXACT 1:1 coordinates.
        long key = ((long) blockX << 32) | (blockZ & 0xFFFFFFFFL);
        int idx = (int) (HashCommon.mix(key) & MASK);

        Cell cachedCell = this.cells[idx];

        // Cache Miss
        if (this.keys[idx] != key) {
            // Evaluates using exact coordinates, mirroring RTF's native getAndUpdate
            lookup.applyCell(cachedCell.reset(), blockX, blockZ, true);
            this.keys[idx] = key;
        }

        target.copyFrom(cachedCell);
    }

    private void rebind(WorldLookup lookup) {
        Arrays.fill(this.keys, Long.MIN_VALUE);
        this.boundLookup = new WeakReference<>(lookup);
    }
}
