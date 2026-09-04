package raccoonman.reterraforged.world.worldgen;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderGetter;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.util.Seed;

public class GeneratorContext implements AutoCloseable {
    public Seed seed;
    public Levels levels;
    public Preset preset;
    public HolderGetter<Noise> noiseLookup;
    public TileGenerator generator;
    @Nullable
    public TileCache cache;
    public WorldLookup lookup;
    
    public GeneratorContext(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount, @Nullable TileCache cache) {
        this.preset = preset;
        this.noiseLookup = noiseLookup;
        this.seed = new Seed(seed);
        this.levels = new Levels(preset.world().properties.terrainScaler(), preset.world().properties.worldHeight, preset.world().properties.worldDepth, preset.world().properties.seaLevel);
		Heightmap heightmap = Heightmap.make(this);
		try {
			this.generator = new TileGenerator(heightmap, new WorldFilters(this), tileSize, tileBorder, batchCount);
		} catch (RuntimeException | Error failure) {
			try {
				heightmap.close();
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
        this.cache = cache;
        this.lookup = new WorldLookup(this);
    }

    public static GeneratorContext makeCached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int batchCount, boolean queue) {
    	GeneratorContext ctx = makeUncached(preset, noiseLookup, seed, tileSize, Math.min(2, Math.max(1, preset.filters().erosion.dropletLifetime / 16)), batchCount);
		try {
			ctx.cache = new TileCache(tileSize, queue, ctx.generator);
			ctx.lookup = new WorldLookup(ctx);
			return ctx;
		} catch (RuntimeException | Error failure) {
			try {
				ctx.close();
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
    }
    
    public static GeneratorContext makeUncached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount) {
    	return new GeneratorContext(preset, noiseLookup, seed, tileSize, tileBorder, batchCount, null);
    }

	@Override
	public void close() {
		TileCache owned = this.cache;
		this.cache = null;
		Throwable failure = null;
		if (owned != null) {
			try {
				owned.close();
			} catch (RuntimeException | Error cacheFailure) {
				failure = cacheFailure;
			}
		}
		try {
			this.generator.close();
		} catch (RuntimeException | Error generatorFailure) {
			if (failure == null) {
				failure = generatorFailure;
			} else if (generatorFailure instanceof Error && !(failure instanceof Error)) {
				generatorFailure.addSuppressed(failure);
				failure = generatorFailure;
			} else {
				failure.addSuppressed(generatorFailure);
			}
		}
		if (failure instanceof RuntimeException runtime) {
			throw runtime;
		}
		if (failure instanceof Error error) {
			throw error;
		}
	}
}
