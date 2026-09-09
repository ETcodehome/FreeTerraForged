package etcodehome.freeterraforged.world.worldgen.cell.continent.simple;

import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

public class SingleContinentGenerator extends ContinentGenerator {
    private NoiseUtil.Vec2i center;
    
    public SingleContinentGenerator(Seed seed, GeneratorContext context) {
        super(seed, context);
        long center = this.getNearestCenter(0.0F, 0.0F);
        int cx = PosUtil.unpackLeft(center);
        int cz = PosUtil.unpackRight(center);
        this.center = new NoiseUtil.Vec2i(cx, cz);
    }
    
    @Override
    public void apply(Cell cell, float x, float y) {
        super.apply(cell, x, y);
        if (cell.continentX != this.center.x() || cell.continentZ != this.center.y()) {
            cell.continentId = 0.0F;
            cell.continentEdge = 0.0F;
            cell.continentX = 0;
            cell.continentZ = 0;
        }
    }
}
