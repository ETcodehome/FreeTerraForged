package etcodehome.freeterraforged.world.worldgen.cell.heightmap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

public class RawChunkCache {
    private static final int MAX_ENTRIES = 2048;

    private static final class ThreadMru {
        long chunkKey = Long.MIN_VALUE;
        Cell[] chunkCells;
    }
    private static final ThreadLocal<ThreadMru> THREAD_MRU = ThreadLocal.withInitial(ThreadMru::new);

    public static void clearThreadCaches() {
        ThreadMru mru = THREAD_MRU.get();
        mru.chunkKey = Long.MIN_VALUE;
        mru.chunkCells = null;
    }

    private final ConcurrentHashMap<Long, Cell[]> cache = new ConcurrentHashMap<>(MAX_ENTRIES);
    private final ConcurrentLinkedQueue<Long> evictionQueue = new ConcurrentLinkedQueue<>();
    private final GeneratorContext context;

    public RawChunkCache(GeneratorContext context) {
        this.context = context;
    }

    public Cell[] getRawChunk(int chunkX, int chunkZ) {
        long key = PosUtil.pack(chunkX, chunkZ);
        ThreadMru mru = THREAD_MRU.get();
        if (mru.chunkKey == key && mru.chunkCells != null) {
            if (WorldgenPerfTracker.ACTIVE) {
                WorldgenPerfTracker.stats().rawChunkCacheHits++;
            }
            return mru.chunkCells;
        }

        Cell[] existing = this.cache.get(key);
        if (existing != null) {
            mru.chunkKey = key;
            mru.chunkCells = existing;
            if (WorldgenPerfTracker.ACTIVE) {
                WorldgenPerfTracker.stats().rawChunkCacheHits++;
            }
            return existing;
        }

        // Compute 256 cells OUTSIDE any lock to prevent ConcurrentHashMap deadlocks
        Cell[] cells = new Cell[256];
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        Heightmap heightmap = this.context.heightmap;
        for (int lz = 0; lz < 16; lz++) {
            int worldZ = minZ + lz;
            int row = lz * 16;
            for (int lx = 0; lx < 16; lx++) {
                int worldX = minX + lx;
                Cell cell = new Cell();
                heightmap.apply(cell, worldX, worldZ, true);
                cells[row + lx] = cell;
            }
        }

        Cell[] raced = this.cache.putIfAbsent(key, cells);
        if (raced != null) {
            mru.chunkKey = key;
            mru.chunkCells = raced;
            if (WorldgenPerfTracker.ACTIVE) {
                WorldgenPerfTracker.stats().rawChunkCacheHits++;
            }
            return raced;
        }

        mru.chunkKey = key;
        mru.chunkCells = cells;

        if (WorldgenPerfTracker.ACTIVE) {
            WorldgenPerfTracker.stats().rawChunkCacheMisses++;
        }

        this.evictionQueue.offer(key);
        while (this.cache.size() > MAX_ENTRIES) {
            Long oldest = this.evictionQueue.poll();
            if (oldest == null) {
                break;
            }
            this.cache.remove(oldest);
        }
        return cells;
    }

    public int size() {
        return this.cache.size();
    }

    public void clear() {
        this.cache.clear();
        this.evictionQueue.clear();
        clearThreadCaches();
    }
}
