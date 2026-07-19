package raccoonman.reterraforged.world.worldgen.densityfunction;

import it.unimi.dsi.fastutil.HashCommon;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;

import java.util.Arrays;

public final class PointCellCache {
    private static final ThreadLocal<PointCellCache> LOCAL_CACHE = ThreadLocal.withInitial(PointCellCache::new);
    private static final int CACHE_SIZE = 4096; // Must be a power of two
    private static final int MASK = CACHE_SIZE - 1;

    private WorldLookup boundLookup;
    private final long[] keys = new long[CACHE_SIZE];
    private final Cell[] cells = new Cell[CACHE_SIZE];

    public static Cell get(WorldLookup lookup, int blockX, int blockZ) {
        return LOCAL_CACHE.get().getOrCreate(lookup, blockX, blockZ);
    }

    private Cell getOrCreate(WorldLookup lookup, int blockX, int blockZ) {
        if (this.boundLookup != lookup) {
            this.rebind(lookup);
        }

        // Quart-snap coordinates (matching RTF's original Cache2d alignment)
        int qx = blockX & ~3;
        int qz = blockZ & ~3;
        long key = ((long) qx << 32) | (qz & 0xFFFFFFFFL);
        int idx = (int) (HashCommon.mix(key) & MASK);

        Cell cell = this.cells[idx];
        if (cell != null && this.keys[idx] == key) {
            return cell; // Cache Hit!
        }

        // Cache Miss
        if (cell == null) {
            cell = new Cell();
            this.cells[idx] = cell;
        }

        // Evaluate the heavy pipeline natively
        lookup.applyCell(cell.reset(), qx, qz, true);
        this.keys[idx] = key;
        return cell;
    }

    private void rebind(WorldLookup lookup) {
        Arrays.fill(this.cells, null); // Clear old entries safely
        this.boundLookup = lookup;
    }

    private PointCellCache() {}
}