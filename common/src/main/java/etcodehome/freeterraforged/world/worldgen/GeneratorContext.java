package etcodehome.freeterraforged.world.worldgen;

import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.util.Seed;

import net.minecraft.core.HolderGetter;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.cell.filter.TerrainFilters;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.FilteredChunkCache;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.RawChunkCache;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.feature.ScreeNoises;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public class GeneratorContext implements AutoCloseable {
    public final etcodehome.freeterraforged.world.worldgen.biome.BiomeSurfaceEnvelope biomeSurfaceEnvelope =
        new etcodehome.freeterraforged.world.worldgen.biome.BiomeSurfaceEnvelope();
    public Seed seed;
    public final Levels levels;
    public Preset preset;
    public HolderGetter<Noise> noiseLookup;
    public Heightmap heightmap;
    public RawChunkCache rawChunkCache;
    public FilteredChunkCache filteredChunkCache;
    public ScreeNoises screeNoises;
    public TerrainFilters filters;
    public WorldLookup lookup;
    
    public GeneratorContext(Preset preset, HolderGetter<Noise> noiseLookup, long seed) {
        this.preset = preset;
        this.levels = new Levels(preset.world().properties);
        this.noiseLookup = noiseLookup;
        this.seed = new Seed(seed);
        this.heightmap = Heightmap.make(this);
        this.rawChunkCache = new RawChunkCache(this);
        this.filteredChunkCache = new FilteredChunkCache(this);
        this.screeNoises = new ScreeNoises(this.heightmap.climate().randomSeed(), this.levels);
        this.filters = new TerrainFilters(this);
        this.lookup = new WorldLookup(this);
    }

    public Heightmap getHeightmap() {
        return this.heightmap;
    }

    public static GeneratorContext make(Preset preset, HolderGetter<Noise> noiseLookup, long seed) {
        return new GeneratorContext(preset, noiseLookup, seed);
    }

    public static GeneratorContext makeCached(Preset preset, HolderGetter<Noise> noiseLookup, long seed, int tileSize, int batchCount, boolean queue) {
        return make(preset, noiseLookup, seed);
    }
    
    public static GeneratorContext makeUncached(Preset preset, HolderGetter<Noise> noiseLookup, long seed, int tileSize, int tileBorder, int batchCount) {
        return make(preset, noiseLookup, seed);
    }

	@Override
	public void close() {
		this.biomeSurfaceEnvelope.clear();
		if (this.rawChunkCache != null) {
			this.rawChunkCache.clear();
		}
		this.heightmap.close();
	}
}
