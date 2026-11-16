package etcodehome.freeterraforged.world.worldgen;

import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.biome.BiomeSurfaceEnvelope;
import etcodehome.freeterraforged.world.worldgen.cell.filter.TerrainFilters;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.FilteredChunkCache;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.RawChunkCache;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.feature.ScreeNoises;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import net.minecraft.core.HolderGetter;

public class GeneratorContext implements AutoCloseable {
    public final BiomeSurfaceEnvelope biomeSurfaceEnvelope = new BiomeSurfaceEnvelope();
    public Seed seed;
    public Levels levels;
    public Preset preset;
    public HolderGetter<Noise> noiseLookup;
    public Heightmap heightmap;
    public RawChunkCache rawChunkCache;
    public FilteredChunkCache filteredChunkCache;
    public ScreeNoises screeNoises;
    public TerrainFilters filters;
    public WorldLookup lookup;

    public GeneratorContext(Preset preset, HolderGetter<Noise> noiseLookup, long seed) {
        this.preset = preset != null ? preset : PresetManager.PM.toPreset();
        PresetManager.PM.ingestFromPreset(this.preset);
        this.noiseLookup = noiseLookup;
        this.seed = new Seed(seed);
        this.levels = new Levels(this.preset.world().properties);
        this.heightmap = Heightmap.make(this);
        this.rawChunkCache = new RawChunkCache(this);
        this.filteredChunkCache = new FilteredChunkCache(this);
        this.screeNoises = new ScreeNoises(this.heightmap.climate().randomSeed(), this.levels);
        this.filters = new TerrainFilters(this);
        this.lookup = new WorldLookup(this);
        PresetManager.PM.registerActiveContext(this);
    }

    public GeneratorContext(HolderGetter<Noise> noiseLookup, long seed) {
        this(PresetManager.PM.toPreset(), noiseLookup, seed);
    }

    public synchronized void reload() {
        this.clearCaches();
        if (this.heightmap != null) {
            this.heightmap.close();
        }
        this.seed = new Seed(this.seed.root());
        this.preset = PresetManager.PM.toPreset();
        this.levels = new Levels(this.preset.world().properties);
        this.heightmap = Heightmap.make(this);
        this.screeNoises = new ScreeNoises(this.heightmap.climate().randomSeed(), this.levels);
        this.filters = new TerrainFilters(this);
        this.lookup = new WorldLookup(this);
    }

    public void clearCaches() {
        this.biomeSurfaceEnvelope.clear();
        if (this.filteredChunkCache != null) {
            this.filteredChunkCache.clear();
        }
        if (this.rawChunkCache != null) {
            this.rawChunkCache.clear();
        }
    }

    public Heightmap getHeightmap() {
        return this.heightmap;
    }

    public static GeneratorContext make(Preset preset, HolderGetter<Noise> noiseLookup, long seed) {
        return new GeneratorContext(preset, noiseLookup, seed);
    }

    public static GeneratorContext make(HolderGetter<Noise> noiseLookup, long seed) {
        return new GeneratorContext(noiseLookup, seed);
    }

    public static GeneratorContext makeCached(Preset preset, HolderGetter<Noise> noiseLookup, long seed, int tileSize, int batchCount, boolean queue) {
        return make(preset, noiseLookup, seed);
    }

    public static GeneratorContext makeUncached(Preset preset, HolderGetter<Noise> noiseLookup, long seed, int tileSize, int tileBorder, int batchCount) {
        return make(preset, noiseLookup, seed);
    }

    @Override
    public void close() {
        PresetManager.PM.unregisterActiveContext(this);
        this.biomeSurfaceEnvelope.clear();
        if (this.filteredChunkCache != null) {
            this.filteredChunkCache.clear();
        }
        if (this.rawChunkCache != null) {
            this.rawChunkCache.clear();
        }
        if (this.heightmap != null) {
            this.heightmap.close();
        }
    }
}
