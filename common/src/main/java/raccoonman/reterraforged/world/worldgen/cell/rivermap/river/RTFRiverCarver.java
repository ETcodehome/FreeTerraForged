package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunction;

/**
 * Defines a structural contract for river carvers to allow
 * specialization across different worldgen profiles or scenarios.
 */
public interface RTFRiverCarver {

    /**
     * @return true if this is a main river channel.
     */
    boolean isMain();

    /**
     * @return the underlying river segment metadata/coordinates.
     */
    River getRiver();

    /**
     * @return the domain warp instance used to deform the river paths.
     */
    RiverWarp getWarp();

    /**
     * @return the core dimension, depth, and order configurations.
     */
    RiverConfig getConfig();

    /**
     * Executes the carving logic on a specific map cell based on
     * interpolation between a previous and current position step.
     */
    void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT);

    RiverConfig createForkConfig(float t, Levels levels);
}
