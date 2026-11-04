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
        
        for (int x = 0; x < total; x++) {
            for (int z = 0; z < total; z++) {
                Cell cell = map.getCellRaw(x, z);
                if (cell.terrain.isCoast() && !cell.terrain.isWetland() && cell.continentEdge < this.transition.beach) {
                    Cell n = map.getCellRaw(x, z - 8);
                    Cell s = map.getCellRaw(x, z + 8);
                    Cell e = map.getCellRaw(x + 8, z);
                    Cell w = map.getCellRaw(x - 8, z);
                    float gx = this.grad(e, w, cell);
                    float gz = this.grad(n, s, cell);
                    float d2 = gx * gx + gz * gz;
                    if (d2 < 0.275F) {
                        cell.terrain = TerrainType.BEACH;
                    }
                }
            }
        }
    }
    
    private float grad(Cell a, Cell b, Cell def) {
        int distance = 17;
        if (a.isAbsent()) {
            a = def;
            distance -= 8;
        }
        if (b.isAbsent()) {
            b = def;
            distance -= 8;
        }
        return (a.height - b.height) / distance;
    }
    
    public static BeachDetect make(GeneratorContext ctx) {
        Levels levels = ctx.levels;
        ControlPoints transition = ctx.preset.world().controlPoints;
        return new BeachDetect(levels, transition);
    }
}
