package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import com.mojang.serialization.JsonOps;

import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import raccoonman.reterraforged.compat.biolith.BiolithPreviewContext;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewIntegration;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewResolver;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

final class BiomePreview {
    private static final ResourceLocation UNREGISTERED = ResourceLocation.fromNamespaceAndPath("reterraforged", "unregistered");
    private static final ThreadLocal<WorkerBuffer> WORKER_BUFFER = ThreadLocal.withInitial(WorkerBuffer::new);
    private static final ThreadLocal<ThreadQuartCache> THREAD_CACHE = ThreadLocal.withInitial(ThreadQuartCache::new);

    private final BiomePreviewResolver resolver;
    private final CacheKey cacheKey;

    private BiomePreview(BiomePreviewResolver resolver, CacheKey cacheKey) {
        this.resolver = resolver;
        this.cacheKey = cacheKey;
    }

    static BiomePreview create(
            WorldCreationContext settings,
            net.minecraft.core.HolderLookup.Provider provider,
            Preset preset,
            GeneratorContext generatorContext
    ) {
        long seed = settings.options().seed();
        LevelStem activeOverworld = settings.selectedDimensions().get(LevelStem.OVERWORLD).orElseThrow();

        BiomePreviewResolver resolver = BiomePreviewResolver.create(
                settings.worldgenLoadContext(),
                provider,
                activeOverworld.type(),
                activeOverworld.generator(),
                preset,
                generatorContext,
                seed
        );

        CacheKey key = cacheKey(settings, preset);

        return new BiomePreview(resolver, key);
    }

    Sidecar resolve(
            Tile tile,
            int centerX,
            int centerZ,
            int zoom,
            Levels levels,
            PreviewCancellation cancellation
    ) {
        int size = tile.getBlockSize().size();
        int totalPixels = size * size;
        int border = tile.getBlockSize().border();

        @SuppressWarnings("unchecked")
        Holder<Biome>[] resolvedBiomes = new Holder[totalPixels];
        short[] indices = new short[totalPixels];
        int[] colors = new int[totalPixels];

        int halfSize = size / 2;
        int step = getSamplingStep(zoom);

        Climate.Sampler sampler = this.resolver.tileClimateSampler(tile, centerX, centerZ, zoom);

        try (BiomePreviewIntegration.Session ignored = this.resolver.openIntegrationSession()) {
            // Chunk rows into blocks of 16 to minimize thread scheduling overhead
            int chunkSize = 16;
            int numChunks = (size + chunkSize - 1) / chunkSize;

            // Capture the Biolith preview state on this thread so it can be re-attached
            // on whichever ForkJoinPool worker thread each chunk below executes on.
            Object biolithState = BiolithPreviewContext.captureState();

            IntStream.range(0, numChunks).parallel().forEach(chunkIdx -> {
                cancellation.check();
                ThreadQuartCache cache = THREAD_CACHE.get();
                cache.clear();

                try (AutoCloseable biolithAttach = BiolithPreviewContext.attach(biolithState)) {
                    int startZ = chunkIdx * chunkSize;
                    int endZ = Math.min(size, startZ + chunkSize);

                    for (int z = startZ; z < endZ; z += step) {
                        int blockZ = centerZ + (z - halfSize) * zoom;
                        int relZ = border + z;
                        int maxZ = Math.min(size, z + step);

                        for (int x = 0; x < size; x += step) {
                            int blockX = centerX + (x - halfSize) * zoom;
                            int relX = border + x;
                            Cell cell = tile.getCellRaw(relX, relZ);
                            int surfaceY = surfaceY(cell, levels);

                            int qX = QuartPos.fromBlock(blockX);
                            int qY = QuartPos.fromBlock(surfaceY);
                            int qZ = QuartPos.fromBlock(blockZ);

                            Holder<Biome> biome = cache.get(qX, qY, qZ);
                            if (biome == null) {
                                biome = this.resolver.resolveQuart(qX, qY, qZ, sampler);
                                cache.put(qX, qY, qZ, biome);
                            }

                            // Fill pixel block for the current stride
                            int maxX = Math.min(size, x + step);
                            for (int fillZ = z; fillZ < maxZ; fillZ++) {
                                int rowOffset = fillZ * size;
                                for (int fillX = x; fillX < maxX; fillX++) {
                                    resolvedBiomes[rowOffset + fillX] = biome;
                                }
                            }
                        }
                    }
                } catch (RuntimeException | Error error) {
                    throw error;
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            // Sequential Palette & Color Assembly
            WorkerBuffer buffer = WORKER_BUFFER.get();
            buffer.reset();

            for (int i = 0; i < totalPixels; i++) {
                Holder<Biome> biome = resolvedBiomes[i];
                Biome rawBiome = biome.value();

                Entry entry = buffer.entryCache.get(rawBiome);
                if (entry == null) {
                    ResourceLocation id = biome.unwrapKey().map(ResourceKey::location).orElse(UNREGISTERED);
                    short paletteIdx = (short) buffer.palette.size();
                    buffer.palette.add(id.toString());
                    entry = buffer.obtainEntry(paletteIdx, BiomePreviewColors.color(biome, id));
                    buffer.entryCache.put(rawBiome, entry);
                }

                indices[i] = entry.paletteIndex;
                colors[i] = entry.color;
            }

            return new Sidecar(
                    size,
                    buffer.palette.toArray(new String[0]),
                    indices,
                    colors,
                    this.resolver.warning()
            );
        } finally {
            WORKER_BUFFER.get().reset();
        }
    }

    Sidecar resolveCached(
            PreviewComputationCache cache,
            Tile tile,
            int centerX,
            int centerZ,
            int zoom,
            Levels levels,
            PreviewCancellation cancellation
    ) {
        int size = tile.getBlockSize().size();
        PreviewComputationCache.SidecarKey key = new PreviewComputationCache.SidecarKey(
                this.cacheKey,
                centerX,
                centerZ,
                zoom,
                size
        );
        return cache.sidecar(key, () -> this.resolve(tile, centerX, centerZ, zoom, levels, cancellation)).join();
    }

    static CacheKey cacheKey(WorldCreationContext settings, Preset preset) {
        String presetJson = Preset.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, preset)
                .result()
                .map(Object::toString)
                .orElse("");

        int biomeCount = (int) settings.worldgenLoadContext().lookupOrThrow(Registries.BIOME).listElements().count();
        String biomeSource = settings.selectedDimensions().overworld().getBiomeSource().getClass().getName();

        return new CacheKey(
                settings.options().seed(),
                presetJson,
                settings.dataConfiguration(),
                biomeSource,
                biomeCount
        );
    }

    private static int getSamplingStep(int zoom) {
        if (zoom <= 1) {
            return 4; // 1 block/px: 4x4 pixels share 1 Quart
        }
        if (zoom == 2) {
            return 2; // 2 blocks/px: 2x2 pixels share 1 Quart
        }
        return 1;     // >= 4 blocks/px: 1+ Quarts per pixel
    }

    private static int surfaceY(Cell cell, Levels levels) {
        int minY = -levels.worldDepth;
        int maxY = Math.max(minY, levels.terrainScaleFactor - 1);
        return Math.max(minY, Math.min(maxY, levels.scale(cell.height)));
    }

    private static final class Entry {
        short paletteIndex;
        int color;
    }

    private static final class WorkerBuffer {
        final IdentityHashMap<Biome, Entry> entryCache = new IdentityHashMap<>(32);
        final ArrayList<String> palette = new ArrayList<>(32);
        final ArrayList<Entry> entryPool = new ArrayList<>(32);

        private int entryPoolIndex = 0;

        Entry obtainEntry(short paletteIndex, int color) {
            Entry entry;
            if (this.entryPoolIndex < this.entryPool.size()) {
                entry = this.entryPool.get(this.entryPoolIndex);
            } else {
                entry = new Entry();
                this.entryPool.add(entry);
            }
            this.entryPoolIndex++;
            entry.paletteIndex = paletteIndex;
            entry.color = color;
            return entry;
        }

        void reset() {
            this.entryCache.clear();
            this.palette.clear();
            this.entryPoolIndex = 0;
        }
    }

    /**
     * Fast thread-local direct-mapped cache for Quart coordinates to prevent redundant calls.
     */
    private static final class ThreadQuartCache {
        private static final int MASK = 255; // 256 entries
        private final long[] keys = new long[256];
        @SuppressWarnings("unchecked")
        private final Holder<Biome>[] values = new Holder[256];

        ThreadQuartCache() {
            clear();
        }

        void clear() {
            java.util.Arrays.fill(keys, Long.MIN_VALUE);
            java.util.Arrays.fill(values, null);
        }

        Holder<Biome> get(int qX, int qY, int qZ) {
            long key = pack(qX, qY, qZ);
            int slot = (int) (key & MASK);
            return keys[slot] == key ? values[slot] : null;
        }

        void put(int qX, int qY, int qZ, Holder<Biome> biome) {
            long key = pack(qX, qY, qZ);
            int slot = (int) (key & MASK);
            keys[slot] = key;
            values[slot] = biome;
        }

        private static long pack(int qX, int qY, int qZ) {
            return (((long) qX & 0x3FFFFF) << 42) | (((long) qY & 0xFFFFF) << 22) | ((long) qZ & 0x3FFFFF);
        }
    }

    record CacheKey(
            long seed,
            String presetJson,
            WorldDataConfiguration dataConfig,
            String biomeSource,
            int biomeCount
    ) {}

    static final class Sidecar {
        private final int size;
        private final String[] palette;
        private final short[] indices;
        private final int[] colors;
        private final String warning;

        private Sidecar(int size, String[] palette, short[] indices, int[] colors, String warning) {
            this.size = size;
            this.palette = palette;
            this.indices = indices;
            this.colors = colors;
            this.warning = warning;
        }

        String id(int x, int z) {
            return this.palette[this.indices[this.index(x, z)]];
        }

        int color(int x, int z) {
            return this.colors[this.index(x, z)];
        }

        String warning() {
            return this.warning;
        }

        private int index(int x, int z) {
            int clampedX = Math.max(0, Math.min(this.size - 1, x));
            int clampedZ = Math.max(0, Math.min(this.size - 1, z));
            return clampedZ * this.size + clampedX;
        }
    }
}