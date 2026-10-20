package etcodehome.freeterraforged.world.worldgen.cell.filter;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;

public class ChunkFilterGrid implements FilterGrid {
    public static final int CHUNK_SIZE = 16;
    public static final int BORDER = 16;
    public static final int TOTAL_SIZE = CHUNK_SIZE + BORDER * 2; // 48
    public static final int TOTAL_CELLS = TOTAL_SIZE * TOTAL_SIZE; // 2304

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

    public void init(int chunkX, int chunkZ, Heightmap heightmap) {
        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;
        this.blockX = minBlockX - BORDER;
        this.blockZ = minBlockZ - BORDER;

        for (int z = 0; z < TOTAL_SIZE; z++) {
            int worldZ = this.blockZ + z;
            int rowOffset = z * TOTAL_SIZE;
            for (int x = 0; x < TOTAL_SIZE; x++) {
                int worldX = this.blockX + x;
                Cell cell = this.cells[rowOffset + x];
                cell.reset();
                heightmap.apply(cell, worldX, worldZ, false);
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

    public void copyTo(Cell[] target256) {
        for (int lz = 0; lz < CHUNK_SIZE; lz++) {
            int srcRow = (BORDER + lz) * TOTAL_SIZE + BORDER;
            int dstRow = lz * CHUNK_SIZE;
            for (int lx = 0; lx < CHUNK_SIZE; lx++) {
                target256[dstRow + lx].copyFrom(this.cells[srcRow + lx]);
            }
        }
    }

    public static Cell[] generateChunkCells(int chunkX, int chunkZ, GeneratorContext context) {
        ChunkFilterGrid grid = THREAD_LOCAL.get();
        grid.init(chunkX, chunkZ, context.heightmap);
        if (context.filters != null) {
            context.filters.apply(grid);
        }
        Cell[] chunkCells = new Cell[CHUNK_SIZE * CHUNK_SIZE];
        for (int i = 0; i < chunkCells.length; i++) {
            chunkCells[i] = new Cell();
        }
        grid.copyTo(chunkCells);
        return chunkCells;
    }
}
