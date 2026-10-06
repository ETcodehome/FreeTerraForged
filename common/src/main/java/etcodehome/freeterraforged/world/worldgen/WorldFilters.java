package etcodehome.freeterraforged.world.worldgen;

import java.util.function.IntFunction;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.BeachDetect;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Erosion;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Filterable;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Steepness;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.TerrainCeiling;

public class WorldFilters {
    private Steepness steepness;
    private TerrainCeiling terrainCeiling;
    private BeachDetect beach;
    private WorldErosion<Erosion> erosion;
    
    public WorldFilters(GeneratorContext context) {
        IntFunction<Erosion> factory = Erosion.factory(context);
        this.beach = BeachDetect.make(context);
        this.steepness = Steepness.make(1, 10.0F, context.levels);
        if (TerrainCeiling.isEnabled(context.preset)) {
            this.terrainCeiling = TerrainCeiling.make(context.preset.world().properties);
        }
        this.erosion = new WorldErosion<>(factory, (e, size) -> e.getSize() == size);
    }
    
    public void apply(Tile tile) {
        int regionX = tile.getX();
        int regionZ = tile.getZ();
        this.applyRequiredFilters(tile, regionX, regionZ);
        if (this.terrainCeiling != null) {
            this.terrainCeiling.apply(tile, regionX, regionZ, 1);
        }
    }
    
    private void applyRequiredFilters(Filterable map, int seedX, int seedZ) {
        this.steepness.apply(map, seedX, seedZ, 1);
        this.beach.apply(map, seedX, seedZ, 1);
        Erosion erosion = this.erosion.get(map.getBlockSize().total());
        erosion.apply(map, seedX, seedZ, 1);
    }
}
