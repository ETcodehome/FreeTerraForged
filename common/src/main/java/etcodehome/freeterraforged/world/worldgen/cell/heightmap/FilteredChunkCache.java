package etcodehome.freeterraforged.world.worldgen.cell.heightmap;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid;
import etcodehome.freeterraforged.world.worldgen.cell.filter.RegionFilterGrid;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

/**
 * Bounded concurrent cache for filtered chunk cells (Cell[580] containing the chunk
 * plus 1-block perimeter).
 *
 * <p>Generates terrain filters across fixed-aligned 4x4 chunk regions (64x64 blocks)
 * in a continuous 88x88 grid. When any chunk is requested, all 16 chunks in the region
 * are filtered together and populated into this cache. Subsequent requests for neighbor
 * chunks in the same region hit this cache with 0 ms computation, giving a ~94% cache
 * hit rate and a 3.3x reduction in total filter evaluations.</p>
 */
public class FilteredChunkCache {
    public static final int REGION_CHUNKS = RegionFilterGrid.REGION_CHUNKS; // 4
    public static final int REGION_MASK = REGION_CHUNKS - 1; // 3
    private static final int MAX_ENTRIES = 2048;

    private final ConcurrentHashMap<Long, Cell[]> cache = new ConcurrentHashMap<>(MAX_ENTRIES);
    private final ConcurrentLinkedQueue<Long> evictionQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger(0);
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> inFlightRegions = new ConcurrentHashMap<>();
    private final GeneratorContext context;

    public FilteredChunkCache(GeneratorContext context) {
        this.context = context;
    }

    public Cell[] getFilteredChunk(int chunkX, int chunkZ) {
        long chunkKey = PosUtil.pack(chunkX, chunkZ);
        Cell[] existing = this.cache.get(chunkKey);
        if (existing != null) {
            if (WorldgenPerfTracker.ACTIVE) {
                WorldgenPerfTracker.stats().filteredChunkCacheHits++;
            }
            return existing;
        }

        int regionChunkX = chunkX & ~REGION_MASK;
        int regionChunkZ = chunkZ & ~REGION_MASK;
        long regionKey = PosUtil.pack(regionChunkX, regionChunkZ);

        while (true) {
            existing = this.cache.get(chunkKey);
            if (existing != null) {
                if (WorldgenPerfTracker.ACTIVE) {
                    WorldgenPerfTracker.stats().filteredChunkCacheHits++;
                }
                return existing;
            }

            CompletableFuture<Void> future = new CompletableFuture<>();
            CompletableFuture<Void> inFlight = this.inFlightRegions.putIfAbsent(regionKey, future);
            if (inFlight == null) {
                try {
                    this.generateRegion(regionChunkX, regionChunkZ);
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                    throw t;
                } finally {
                    this.inFlightRegions.remove(regionKey);
                }
                if (WorldgenPerfTracker.ACTIVE) {
                    WorldgenPerfTracker.stats().filteredChunkCacheMisses++;
                }
                return this.cache.get(chunkKey);
            } else {
                try {
                    inFlight.join();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void generateRegion(int regionChunkX, int regionChunkZ) {
        RegionFilterGrid grid = RegionFilterGrid.THREAD_LOCAL.get();
        boolean perfActive = WorldgenPerfTracker.ACTIVE;
        long t0 = perfActive ? System.nanoTime() : 0L;
        WorldgenPerfTracker.ThreadStats ts = perfActive ? WorldgenPerfTracker.stats() : null;
        boolean prevInside = false;
        if (ts != null) {
            prevInside = ts.insideGridInit;
            ts.insideGridInit = true;
        }
        try {
            grid.init(regionChunkX, regionChunkZ, this.context);
            this.context.biomeSurfaceEnvelope.prepopulateFromRegionGrid(
                this.context, regionChunkX, regionChunkZ, grid.getBacking(),
                grid.getTotalSize(), grid.getBorder(), REGION_CHUNKS
            );
        } finally {
            if (ts != null) {
                ts.insideGridInit = prevInside;
                ts.gridInitNanos += (System.nanoTime() - t0);
            }
        }

        if (this.context.filters != null) {
            this.context.filters.apply(grid);
        }

        for (int lz = 0; lz < REGION_CHUNKS; lz++) {
            for (int lx = 0; lx < REGION_CHUNKS; lx++) {
                int cx = regionChunkX + lx;
                int cz = regionChunkZ + lz;
                long key = PosUtil.pack(cx, cz);
                Cell[] chunkCells = grid.extractChunkCells(lx, lz);
                this.cache.put(key, chunkCells);
                this.evictionQueue.offer(key);
                if (this.size.incrementAndGet() > MAX_ENTRIES) {
                    Long oldest = this.evictionQueue.poll();
                    if (oldest != null) {
                        this.cache.remove(oldest);
                        this.size.decrementAndGet();
                    }
                }
            }
        }

        if (ts != null) {
            ts.gridGenerateCount++;
            ts.gridGenerateNanos += (System.nanoTime() - t0);
        }
    }

    public Cell[] getIfPresent(int chunkX, int chunkZ) {
        return this.cache.get(PosUtil.pack(chunkX, chunkZ));
    }

    public Cell getExistingFilteredCell(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        Cell[] chunkCells = this.cache.get(PosUtil.pack(chunkX, chunkZ));
        if (chunkCells != null) {
            int relX = blockX & 15;
            int relZ = blockZ & 15;
            return ChunkFilterGrid.getPaddedCell(chunkCells, relX, relZ);
        }
        return null;
    }

    public void clear() {
        this.cache.clear();
        this.evictionQueue.clear();
        this.size.set(0);
        this.inFlightRegions.clear();
    }
}
