package etcodehome.freeterraforged.world.worldgen;

import java.util.function.IntFunction;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.BeachDetect;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.CliffRelaxation;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Erosion;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Filterable;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Steepness;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.TerrainCeiling;

public class WorldFilters {
    private Steepness steepness;
    private TerrainCeiling terrainCeiling;
    private BeachDetect beach;
    private WorldErosion<CliffRelaxation> cliffRelaxation;
    private WorldErosion<Erosion> erosion;

    public WorldFilters(GeneratorContext context) {
        IntFunction<CliffRelaxation> relaxFactory = CliffRelaxation.factory(context);
        this.cliffRelaxation = new WorldErosion<>(relaxFactory, (c, size) -> c.getSize() == size);

        IntFunction<Erosion> factory = Erosion.factory(context);
        this.erosion = new WorldErosion<>(factory, (e, size) -> e.getSize() == size);

        this.beach = BeachDetect.make(context);
        this.steepness = Steepness.make(1, 10.0F, context.levels);
        if (TerrainCeiling.isEnabled(context.preset)) {
            this.terrainCeiling = TerrainCeiling.make(context.preset.world().properties);
        }
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
        final int size = map.getBlockSize().total();

        // Must run before Erosion: it smooths the terrain and records Cell.preErosionHeight.
        CliffRelaxation relaxation = this.cliffRelaxation.get(size);
        relaxation.apply(map, seedX, seedZ, 1);

        Erosion erosion = this.erosion.get(size);
        erosion.apply(map, seedX, seedZ, 1);

        relaxation.apply(map, seedX, seedZ, 1);

        this.beach.apply(map, seedX, seedZ, 1);
        this.steepness.apply(map, seedX, seedZ, 1);
    }
}