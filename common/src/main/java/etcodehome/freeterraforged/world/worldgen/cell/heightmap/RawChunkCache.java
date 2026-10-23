package etcodehome.freeterraforged.world.worldgen.cell.heightmap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

public class RawChunkCache {
    private static final int MAX_ENTRIES = 2048;
    private final ConcurrentHashMap<Long, Cell[]> cache = new ConcurrentHashMap<>(512);
    private final ConcurrentLinkedQueue<Long> evictionQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger(0);
    private final GeneratorContext context;

    public RawChunkCache(GeneratorContext context) {
        this.context = context;
    }

    public Cell[] getRawChunk(int chunkX, int chunkZ) {
        long key = PosUtil.pack(chunkX, chunkZ);
        Cell[] existing = this.cache.get(key);
        if (existing != null) {
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
            if (WorldgenPerfTracker.ACTIVE) {
                WorldgenPerfTracker.stats().rawChunkCacheHits++;
            }
            return raced;
        }

        if (WorldgenPerfTracker.ACTIVE) {
            WorldgenPerfTracker.stats().rawChunkCacheMisses++;
        }

        this.evictionQueue.offer(key);
        if (this.size.incrementAndGet() > MAX_ENTRIES) {
            Long oldest = this.evictionQueue.poll();
            if (oldest != null) {
                this.cache.remove(oldest);
                this.size.decrementAndGet();
            }
        }
        return cells;
    }

    public Cell getExistingRawCell(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        long key = PosUtil.pack(chunkX, chunkZ);
        Cell[] chunkCells = this.cache.get(key);
        if (chunkCells != null) {
            int localX = blockX & 15;
            int localZ = blockZ & 15;
            return chunkCells[localZ * 16 + localX];
        }
        return null;
    }

    public void clear() {
        this.cache.clear();
        this.evictionQueue.clear();
        this.size.set(0);
    }
}
