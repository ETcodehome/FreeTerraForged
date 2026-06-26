package raccoonman.reterraforged.world.worldgen.cell.terrain;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.ArchipelagoPopulator;

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