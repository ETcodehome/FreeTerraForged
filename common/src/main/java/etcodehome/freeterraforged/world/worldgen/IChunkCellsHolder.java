package etcodehome.freeterraforged.world.worldgen;

import org.jetbrains.annotations.Nullable;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

public interface IChunkCellsHolder {
    @Nullable
    Cell[] freeterraforged$getChunkCells();

    void freeterraforged$setChunkCells(Cell[] cells);

    Cell[] freeterraforged$getOrCreateChunkCells(GeneratorContext context);

    Cell freeterraforged$getCell(int localX, int localZ, GeneratorContext context);
}
