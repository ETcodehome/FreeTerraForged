package etcodehome.freeterraforged.world.worldgen.cell.filter;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;

public interface Filter {
    void apply(FilterGrid map, int regionX, int regionZ, int iterationsPerChunk);

    default void iterate(FilterGrid map, Visitor visitor) {
        int total = map.getTotalSize();
        for (int dz = 0; dz < total; ++dz) {
            for (int dx = 0; dx < total; ++dx) {
                Cell cell = map.getCellRaw(dx, dz);
                visitor.visit(map, cell, dx, dz);
            }
        }
    }

    interface Visitor {
        void visit(FilterGrid map, Cell cell, int dx, int dz);
    }
}
