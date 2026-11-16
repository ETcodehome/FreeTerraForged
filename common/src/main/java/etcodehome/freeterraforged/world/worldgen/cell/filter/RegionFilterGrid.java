package etcodehome.freeterraforged.world.worldgen.cell.filter;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.RawChunkCache;

/**
 * Multi-chunk filter grid that aligns to fixed 4x4 chunk regions (64x64 blocks).
 *
 * <p>Filtering 4x4 chunks in a single continuous 88x88 grid with a 12-block border evaluates
 * 7,744 cells across 16 chunks (~484 cells/chunk), compared to evaluating 1,600 cells per chunk
 * (25,600 cells for 16 chunks) in per-chunk grids. This provides a 3.3x reduction in cell evaluations
 * and erosion filter executions, eliminates redundant border overlapping between neighbor chunks,
 * and ensures 100% world-coordinate alignment and determinism regardless of chunk generation order.
 */
public class RegionFilterGrid implements FilterGrid {
    public static final int REGION_CHUNKS = 4;
    public static final int CHUNK_SIZE = 16;
    public static final int REGION_BLOCKS = REGION_CHUNKS * CHUNK_SIZE; // 64
    public static final int BORDER = 12;
    public static final int TOTAL_SIZE = REGION_BLOCKS + BORDER * 2; // 88
    public static final int TOTAL_CELLS = TOTAL_SIZE * TOTAL_SIZE; // 7,744
    public static final int PADDED_SIZE = CHUNK_SIZE + 2; // 18
    public static final int PADDED_OFFSET = CHUNK_SIZE * CHUNK_SIZE; // 256

    public static final ThreadLocal<RegionFilterGrid> THREAD_LOCAL = ThreadLocal.withInitial(RegionFilterGrid::new);

    private int blockX;
    private int blockZ;
    private int regionChunkX;
    private int regionChunkZ;
    private final Cell[] cells;

    public RegionFilterGrid() {
        this.cells = new Cell[TOTAL_CELLS];
        for (int i = 0; i < TOTAL_CELLS; i++) {
            this.cells[i] = new Cell();
        }
    }

    public void init(int regionChunkX, int regionChunkZ, GeneratorContext context) {
        this.regionChunkX = regionChunkX;
        this.regionChunkZ = regionChunkZ;
        int minBlockX = regionChunkX << 4;
        int minBlockZ = regionChunkZ << 4;
        this.blockX = minBlockX - BORDER;
        this.blockZ = minBlockZ - BORDER;
        int maxBlockX = this.blockX + TOTAL_SIZE - 1;
        int maxBlockZ = this.blockZ + TOTAL_SIZE - 1;

        RawChunkCache rawCache = context != null ? context.rawChunkCache : null;
        if (rawCache == null) {
            return;
        }

        int startChunkX = regionChunkX - 1;
        int endChunkX = regionChunkX + REGION_CHUNKS;
        int startChunkZ = regionChunkZ - 1;
        int endChunkZ = regionChunkZ + REGION_CHUNKS;

        for (int cz = startChunkZ; cz <= endChunkZ; cz++) {
            int chunkMinZ = cz << 4;
            int chunkMaxZ = chunkMinZ + 15;
            int startBz = Math.max(chunkMinZ, this.blockZ);
            int endBz = Math.min(chunkMaxZ, maxBlockZ);
            if (startBz > endBz) continue;

            int startLz = startBz - chunkMinZ;
            int startGz = startBz - this.blockZ;
            int lenZ = endBz - startBz + 1;

            for (int cx = startChunkX; cx <= endChunkX; cx++) {
                int chunkMinX = cx << 4;
                int chunkMaxX = chunkMinX + 15;
                int startBx = Math.max(chunkMinX, this.blockX);
                int endBx = Math.min(chunkMaxX, maxBlockX);
                if (startBx > endBx) continue;

                int startLx = startBx - chunkMinX;
                int startGx = startBx - this.blockX;
                int lenX = endBx - startBx + 1;

                Cell[] raw = rawCache.getRawChunk(cx, cz);

                for (int z = 0; z < lenZ; z++) {
                    int srcRow = (startLz + z) * CHUNK_SIZE + startLx;
                    int dstRow = (startGz + z) * TOTAL_SIZE + startGx;
                    for (int x = 0; x < lenX; x++) {
                        this.cells[dstRow + x].copyFrom(raw[srcRow + x]);
                    }
                }
            }
        }
    }

    /**
     * Extracts the 18x18 padded cells (580 cells) for chunk {@code (localChunkX, localChunkZ)}
     * where local chunk coordinates are in {@code [0..REGION_CHUNKS-1]}.
     */
    public Cell[] extractChunkCells(int localChunkX, int localChunkZ) {
        Cell[] chunkCells = ChunkFilterGrid.allocateHolderCells();
        int chunkBaseX = BORDER + localChunkX * CHUNK_SIZE;
        int chunkBaseZ = BORDER + localChunkZ * CHUNK_SIZE;

        for (int pz = 0; pz < PADDED_SIZE; pz++) {
            int lz = pz - 1;
            int srcRow = (chunkBaseZ + lz) * TOTAL_SIZE + (chunkBaseX - 1);
            int paddedRow = PADDED_OFFSET + pz * PADDED_SIZE;
            boolean zInterior = lz >= 0 && lz < CHUNK_SIZE;
            for (int px = 0; px < PADDED_SIZE; px++) {
                int lx = px - 1;
                Cell cell = chunkCells[paddedRow + px];
                cell.copyFrom(this.cells[srcRow + px]);
                if (zInterior && lx >= 0 && lx < CHUNK_SIZE) {
                    chunkCells[lz * CHUNK_SIZE + lx] = cell;
                }
            }
        }
        return chunkCells;
    }

    @Override
    public int getBlockX() {
        return this.blockX;
    }

    @Override
    public int getBlockZ() {
        return this.blockZ;
    }

    @Override
    public int getSize() {
        return REGION_BLOCKS;
    }

    @Override
    public int getBorder() {
        return BORDER;
    }

    @Override
    public int getTotalSize() {
        return TOTAL_SIZE;
    }

    @Override
    public Cell[] getBacking() {
        return this.cells;
    }

    @Override
    public Cell getCellRaw(int x, int z) {
        if (x < 0 || x >= TOTAL_SIZE || z < 0 || z >= TOTAL_SIZE) {
            return Cell.empty();
        }
        return this.cells[z * TOTAL_SIZE + x];
    }
}
