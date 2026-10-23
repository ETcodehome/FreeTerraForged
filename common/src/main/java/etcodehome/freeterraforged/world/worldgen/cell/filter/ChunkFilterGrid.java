package etcodehome.freeterraforged.world.worldgen.cell.filter;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.RawChunkCache;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;

public class ChunkFilterGrid implements FilterGrid {
    public static final int CHUNK_SIZE = 16;
    public static final int PADDED_SIZE = CHUNK_SIZE + 2; // 18: relX, relZ in [-1..16]
    public static final int PADDED_OFFSET = CHUNK_SIZE * CHUNK_SIZE; // 256
    public static final int HOLDER_ARRAY_LENGTH = PADDED_OFFSET + PADDED_SIZE * PADDED_SIZE; // 580
    public static final int BORDER = 12;
    public static final int TOTAL_SIZE = CHUNK_SIZE + BORDER * 2; // 40
    public static final int TOTAL_CELLS = TOTAL_SIZE * TOTAL_SIZE; // 1600

    private static final ThreadLocal<ChunkFilterGrid> THREAD_LOCAL = ThreadLocal.withInitial(ChunkFilterGrid::new);

    private int blockX;
    private int blockZ;
    private final Cell[] cells;

    public ChunkFilterGrid() {
        this.cells = new Cell[TOTAL_CELLS];
        for (int i = 0; i < TOTAL_CELLS; i++) {
            this.cells[i] = new Cell();
        }
    }

    public void init(int chunkX, int chunkZ, GeneratorContext context) {
        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;
        this.blockX = minBlockX - BORDER;
        this.blockZ = minBlockZ - BORDER;

        RawChunkCache rawCache = context != null ? context.rawChunkCache : null;
        if (rawCache == null) {
            this.initDirect(chunkX, chunkZ, context != null ? context.heightmap : null);
            return;
        }

        for (int dz = -1; dz <= 1; dz++) {
            int nz = chunkZ + dz;
            int startLz = (dz == -1) ? 4 : 0;
            int endLz = (dz == 1) ? 11 : 15;
            int startGz = (dz == -1) ? 0 : (dz == 0 ? 12 : 28);
            int lenZ = endLz - startLz + 1;

            for (int dx = -1; dx <= 1; dx++) {
                int nx = chunkX + dx;
                int startLx = (dx == -1) ? 4 : 0;
                int endLx = (dx == 1) ? 11 : 15;
                int startGx = (dx == -1) ? 0 : (dx == 0 ? 12 : 28);
                int lenX = endLx - startLx + 1;

                Cell[] raw = rawCache.getRawChunk(nx, nz);

                for (int z = 0; z < lenZ; z++) {
                    int lz = startLz + z;
                    int gz = startGz + z;
                    int srcRow = lz * CHUNK_SIZE + startLx;
                    int dstRow = gz * TOTAL_SIZE + startGx;
                    for (int x = 0; x < lenX; x++) {
                        this.cells[dstRow + x].copyFrom(raw[srcRow + x]);
                    }
                }
            }
        }
    }

    public void init(int chunkX, int chunkZ, Heightmap heightmap) {
        this.initDirect(chunkX, chunkZ, heightmap);
    }

    private void initDirect(int chunkX, int chunkZ, Heightmap heightmap) {
        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;
        this.blockX = minBlockX - BORDER;
        this.blockZ = minBlockZ - BORDER;

        for (int z = 0; z < TOTAL_SIZE; z++) {
            int worldZ = this.blockZ + z;
            int rowOffset = z * TOTAL_SIZE;
            boolean zInChunk = z >= BORDER && z < BORDER + CHUNK_SIZE;
            boolean zQuart = (z & 3) == 0;
            for (int x = 0; x < TOTAL_SIZE; x++) {
                int worldX = this.blockX + x;
                boolean applyClimate = (zInChunk && x >= BORDER && x < BORDER + CHUNK_SIZE) || (zQuart && (x & 3) == 0);
                Cell cell = this.cells[rowOffset + x];
                cell.reset();
                if (heightmap != null) {
                    heightmap.apply(cell, worldX, worldZ, applyClimate);
                }
            }
        }
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
        return CHUNK_SIZE;
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

    public Cell getChunkCell(int localX, int localZ) {
        int x = BORDER + localX;
        int z = BORDER + localZ;
        return this.cells[z * TOTAL_SIZE + x];
    }

    public void copyTo(Cell[] target) {
        if (target.length >= HOLDER_ARRAY_LENGTH) {
            for (int pz = 0; pz < PADDED_SIZE; pz++) {
                int lz = pz - 1;
                int srcRow = (BORDER + lz) * TOTAL_SIZE + (BORDER - 1);
                int paddedRow = PADDED_OFFSET + pz * PADDED_SIZE;
                boolean zInterior = lz >= 0 && lz < CHUNK_SIZE;
                for (int px = 0; px < PADDED_SIZE; px++) {
                    int lx = px - 1;
                    Cell cell = target[paddedRow + px];
                    cell.copyFrom(this.cells[srcRow + px]);
                    if (zInterior && lx >= 0 && lx < CHUNK_SIZE) {
                        target[lz * CHUNK_SIZE + lx] = cell;
                    }
                }
            }
            return;
        }
        for (int lz = 0; lz < CHUNK_SIZE; lz++) {
            int srcRow = (BORDER + lz) * TOTAL_SIZE + BORDER;
            int dstRow = lz * CHUNK_SIZE;
            for (int lx = 0; lx < CHUNK_SIZE; lx++) {
                target[dstRow + lx].copyFrom(this.cells[srcRow + lx]);
            }
        }
    }

    public static Cell getPaddedCell(Cell[] chunkCells, int relX, int relZ) {
        if (chunkCells.length >= HOLDER_ARRAY_LENGTH) {
            return chunkCells[PADDED_OFFSET + (relZ + 1) * PADDED_SIZE + (relX + 1)];
        }
        return chunkCells[relZ * CHUNK_SIZE + relX];
    }

    public static Cell[] allocateHolderCells() {
        Cell[] chunkCells = new Cell[HOLDER_ARRAY_LENGTH];
        for (int i = PADDED_OFFSET; i < HOLDER_ARRAY_LENGTH; i++) {
            chunkCells[i] = new Cell();
        }
        return chunkCells;
    }

    public static Cell[] generateChunkCells(int chunkX, int chunkZ, GeneratorContext context) {
        ChunkFilterGrid grid = THREAD_LOCAL.get();
        if (!WorldgenPerfTracker.ACTIVE) {
            grid.init(chunkX, chunkZ, context);
            context.biomeSurfaceEnvelope.prepopulateFromChunkGrid(
                context, chunkX, chunkZ, grid.cells, TOTAL_SIZE, BORDER
            );
            CellSampler.seedThreadCacheFromGrid(
                context.lookup, grid.blockX, grid.blockZ, TOTAL_SIZE, BORDER, CHUNK_SIZE, grid.cells
            );
            if (context.filters != null) {
                context.filters.apply(grid);
            }
            Cell[] chunkCells = allocateHolderCells();
            grid.copyTo(chunkCells);
            return chunkCells;
        }

        WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
        long t0 = System.nanoTime();
        boolean prevInside = ts.insideGridInit;
        ts.insideGridInit = true;
        try {
            grid.init(chunkX, chunkZ, context);
            context.biomeSurfaceEnvelope.prepopulateFromChunkGrid(
                context, chunkX, chunkZ, grid.cells, TOTAL_SIZE, BORDER
            );
            CellSampler.seedThreadCacheFromGrid(
                context.lookup, grid.blockX, grid.blockZ, TOTAL_SIZE, BORDER, CHUNK_SIZE, grid.cells
            );
        } finally {
            ts.insideGridInit = prevInside;
        }
        long t1 = System.nanoTime();
        ts.gridInitNanos += (t1 - t0);
        if (context.filters != null) {
            context.filters.apply(grid);
        }
        Cell[] chunkCells = allocateHolderCells();
        grid.copyTo(chunkCells);
        long t2 = System.nanoTime();
        ts.gridGenerateCount++;
        ts.gridGenerateNanos += (t2 - t0);
        return chunkCells;
    }
}
