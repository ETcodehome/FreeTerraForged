package etcodehome.freeterraforged.world.worldgen.geology;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;

public interface GeologySampler {
    RockSample sample(int x, double y, int z);

    /** Surface sampling can place deposits using the existing river and terrain data. */
    default RockSample sample(int x, double y, int z, Cell cell, int terrainScale) {
        return this.sample(x, y, z);
    }
}
