package etcodehome.freeterraforged.world.worldgen.cell.filter;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;

public record BeachDetect(Levels levels, ControlPoints transition) implements Filter {
    
    @Override
    public void apply(FilterGrid map, int seedX, int seedZ, int iterations) {
        int total = map.getTotalSize();
        final float cellSpacing = map.getCellSpacing() > 0.0F ? map.getCellSpacing() : 1.0F;
        final int step = Math.max(1, Math.round(8.0F / cellSpacing));
        
        for (int x = 0; x < total; x++) {
            for (int z = 0; z < total; z++) {
                Cell cell = map.getCellRaw(x, z);
                if (cell.terrain.isCoast() && !cell.terrain.isWetland() && cell.controlPoint < this.transition.beach) {
                    Cell n = map.getCellRaw(x, z - step);
                    Cell s = map.getCellRaw(x, z + step);
                    Cell e = map.getCellRaw(x + step, z);
                    Cell w = map.getCellRaw(x - step, z);
                    float gx = this.grad(e, w, cell, step, cellSpacing);
                    float gz = this.grad(n, s, cell, step, cellSpacing);
                    float d2 = gx * gx + gz * gz;
                    if (d2 < 0.275F) {
                        cell.terrain = TerrainType.BEACH;
                    }
                }
            }
        }
    }
    
    private float grad(Cell a, Cell b, Cell def, int step, float cellSpacing) {
        int distance = 2 * step + 1;
        if (a.isAbsent()) {
            a = def;
            distance -= step;
        }
        if (b.isAbsent()) {
            b = def;
            distance -= step;
        }
        return (a.height - b.height) / (distance * cellSpacing);
    }
    
    public static BeachDetect make(GeneratorContext ctx) {
        Levels levels = ctx.levels;
        ControlPoints transition = ctx.preset.world().controlPoints;
        return new BeachDetect(levels, transition);
    }
}
