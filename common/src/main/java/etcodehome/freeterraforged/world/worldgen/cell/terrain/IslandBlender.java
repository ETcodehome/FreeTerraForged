package etcodehome.freeterraforged.world.worldgen.cell.terrain;

import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.ArchipelagoPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.CellPopulator;

/**
 * Places archipelago terrain in ocean areas.
 * ArchipelagoPopulator handles continuous blending from ocean floor to island height,
 * so this blender only applies the result directly without extra math.
 */
public class IslandBlender implements CellPopulator {
    private CellPopulator baseTerrain;
    private ArchipelagoPopulator archipelago;
    private Levels levels;
    
    public IslandBlender(CellPopulator baseTerrain, ArchipelagoPopulator archipelago, Levels levels) {
        this.baseTerrain = baseTerrain;
        this.archipelago = archipelago;
        this.levels = levels;
    }
    
    @Override
    public void apply(Cell cell, float x, float z) {
        // Run base terrain first (ocean/coast)
        this.baseTerrain.apply(cell, x, z);
        
        // Allow coast cells to participate so archipelago shores can blend into shallow water.
        if (cell.terrain != null && cell.terrain.isOverground() && !cell.terrain.isCoast()) {
            return;
        }
        
        this.archipelago.apply(cell, x, z);
    }
}