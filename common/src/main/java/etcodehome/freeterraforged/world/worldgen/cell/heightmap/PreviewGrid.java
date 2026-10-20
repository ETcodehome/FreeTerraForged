package etcodehome.freeterraforged.world.worldgen.cell.heightmap;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import etcodehome.freeterraforged.concurrent.ThreadPools;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;

public final class PreviewGrid {
    private final int size;
    private final Cell[] cells;

    public PreviewGrid(int size) {
        this.size = size;
        this.cells = new Cell[size * size];
        for (int i = 0; i < this.cells.length; i++) {
            this.cells[i] = new Cell();
        }
    }

    public int size() {
        return this.size;
    }

    public Cell getCell(int x, int z) {
        if (x < 0 || x >= this.size || z < 0 || z >= this.size) {
            return Cell.empty();
        }
        return this.cells[z * this.size + x];
    }

    public Cell lookup(int x, int z) {
        return getCell(x, z);
    }

    public void iterate(CellVisitor visitor) {
        for (int z = 0; z < this.size; z++) {
            for (int x = 0; x < this.size; x++) {
                visitor.visit(this.cells[z * this.size + x], x, z);
            }
        }
    }

    public static CompletableFuture<PreviewGrid> generateZoomed(
        Heightmap heightmap,
        float centerX,
        float centerZ,
        float zoom,
        int size,
        BooleanSupplier cancelled
    ) {
        PreviewGrid grid = new PreviewGrid(size);
        float translateX = centerX - size * zoom / 2.0F;
        float translateZ = centerZ - size * zoom / 2.0F;

        int batchCount = 4;
        int batchSize = (size + batchCount - 1) / batchCount;
        CompletableFuture<?>[] futures = new CompletableFuture<?>[batchCount * batchCount];
        int submitted = 0;

        for (int bz = 0; bz < batchCount; bz++) {
            int startZ = bz * batchSize;
            int endZ = Math.min(size, startZ + batchSize);
            for (int bx = 0; bx < batchCount; bx++) {
                int startX = bx * batchSize;
                int endX = Math.min(size, startX + batchSize);

                CompletableFuture<?> batch = CompletableFuture.runAsync(() -> {
                    checkCancelled(cancelled);
                    Rivermap rivers = null;
                    for (int z = startZ; z < endZ; z++) {
                        checkCancelled(cancelled);
                        for (int x = startX; x < endX; x++) {
                            float worldX = x * zoom + translateX;
                            float worldZ = z * zoom + translateZ;
                            Cell cell = grid.getCell(x, z);

                            heightmap.applyTerrain(cell, worldX, worldZ);
                            rivers = Rivermap.get(cell, rivers, heightmap);
                            heightmap.applyRivers(cell, worldX, worldZ, rivers);
                            heightmap.applyClimate(cell, worldX, worldZ, true);
                        }
                    }
                }, ThreadPools.WORLD_GEN);
                futures[submitted++] = batch;
            }
        }

        return CompletableFuture.allOf(futures).thenApply(v -> {
            checkCancelled(cancelled);
            return grid;
        });
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Preview grid generation was cancelled");
        }
    }

    @FunctionalInterface
    public interface CellVisitor {
        void visit(Cell cell, int x, int z);
    }
}
