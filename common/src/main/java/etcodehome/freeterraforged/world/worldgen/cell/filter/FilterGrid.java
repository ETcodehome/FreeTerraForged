package etcodehome.freeterraforged.world.worldgen.cell.filter;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;

public interface FilterGrid {
    int getBlockX();

    int getBlockZ();

    int getSize();

    int getBorder();

    int getTotalSize();

    Cell[] getBacking();

    Cell getCellRaw(int x, int z);
}
