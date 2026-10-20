package etcodehome.freeterraforged.world.worldgen.cell.filter;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainCeiling;

public class TerrainFilters {
    private final CliffRelaxation cliffRelaxation1;
    private final Erosion erosion;
    private final CliffRelaxation cliffRelaxation2;
    private final BeachDetect beachDetect;
    private final Steepness steepness;
    private final TerrainCeiling terrainCeiling;

    public TerrainFilters(GeneratorContext context) {
        this.cliffRelaxation1 = CliffRelaxation.factory(context, 1).apply(0);
        this.erosion = Erosion.factory(context).apply(0);
        this.cliffRelaxation2 = CliffRelaxation.factory(context, 2).apply(0);
        this.beachDetect = BeachDetect.make(context);
        this.steepness = Steepness.make(1, 10.0F, context.levels);
        if (context.preset != null && TerrainCeiling.isEnabled(context.preset)) {
            this.terrainCeiling = TerrainCeiling.make(context.preset.world().properties);
        } else {
            this.terrainCeiling = null;
        }
    }

    public void apply(FilterGrid grid) {
        int seedX = grid.getBlockX();
        int seedZ = grid.getBlockZ();

        // 1. Pre-erosion cliff relaxation: smooths spikes/pits and records preErosionHeight
        this.cliffRelaxation1.apply(grid, seedX, seedZ, 1);

        // 2. Procedural Phacelle erosion: computes sediment, heightErosion, and eroded height
        this.erosion.apply(grid, seedX, seedZ, 1);

        // 3. Post-erosion cliff relaxation: softens sharp erosion gullies
        this.cliffRelaxation2.apply(grid, seedX, seedZ, 1);

        // 4. Beach detection: marks coastal cells as BEACH where slope is low
        this.beachDetect.apply(grid, seedX, seedZ, 1);

        // 5. Steepness: computes cell.gradient
        this.steepness.apply(grid, seedX, seedZ, 1);

        // 6. Terrain ceiling compression (if enabled)
        if (this.terrainCeiling != null) {
            for (Cell cell : grid.getBacking()) {
                cell.height = this.terrainCeiling.apply(cell.height);
            }
        }
    }
}
