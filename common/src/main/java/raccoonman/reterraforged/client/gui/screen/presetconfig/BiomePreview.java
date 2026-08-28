package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.IdentityHashMap;

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
        return new BiomePreview(resolver, cacheKey(settings, preset));
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
        short[] indices = new short[size * size];
        int[] colors = new int[size * size];

        WorkerBuffer buffer = WORKER_BUFFER.get();
        buffer.reset();

        int halfSize = size / 2;
        Climate.Sampler sampler = this.resolver.tileClimateSampler(tile, centerX, centerZ, zoom);

        try (BiomePreviewIntegration.Session ignored = this.resolver.openIntegrationSession()) {
            tile.iterate((cell, x, z) -> {
                if (x == 0) cancellation.check();

                int blockX = centerX + (x - halfSize) * zoom;
                int blockZ = centerZ + (z - halfSize) * zoom;
                int surfaceY = surfaceY(cell, levels);

                int qX = QuartPos.fromBlock(blockX);
                int qY = QuartPos.fromBlock(surfaceY);
                int qZ = QuartPos.fromBlock(blockZ);

                Holder<Biome> biome;
                if (qX == buffer.lastQX && qY == buffer.lastQY && qZ == buffer.lastQZ && buffer.lastBiome != null) {
                    biome = buffer.lastBiome;
                } else {
                    biome = this.resolver.resolveQuart(qX, qY, qZ, sampler);
                    buffer.lastQX = qX;
                    buffer.lastQY = qY;
                    buffer.lastQZ = qZ;
                    buffer.lastBiome = biome;
                }

                Biome rawBiome = biome.value();
                Entry entry = buffer.entryCache.get(rawBiome);
                if (entry == null) {
                    ResourceLocation id = biome.unwrapKey().map(ResourceKey::location).orElse(UNREGISTERED);
                    short paletteIdx = (short) buffer.palette.size();
                    buffer.palette.add(id.toString());
                    entry = buffer.obtainEntry(paletteIdx, BiomePreviewColors.color(biome, id));
                    buffer.entryCache.put(rawBiome, entry);
                }

                int index = z * size + x;
                indices[index] = entry.paletteIndex;
                colors[index] = entry.color;
            });

            return new Sidecar(
                    size,
                    buffer.palette.toArray(new String[0]),
                    indices,
                    colors,
                    this.resolver.warning()
            );
        } finally {
            buffer.reset(); // Release Holder<Biome> and registry references to avoid memory leaks
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

        int lastQX = Integer.MIN_VALUE;
        int lastQY = Integer.MIN_VALUE;
        int lastQZ = Integer.MIN_VALUE;
        Holder<Biome> lastBiome = null;

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
            this.lastQX = Integer.MIN_VALUE;
            this.lastQY = Integer.MIN_VALUE;
            this.lastQZ = Integer.MIN_VALUE;
            this.lastBiome = null;
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