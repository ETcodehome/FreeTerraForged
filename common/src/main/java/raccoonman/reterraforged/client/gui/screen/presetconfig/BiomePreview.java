package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewResolver;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewIntegration;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

final class BiomePreview {
    private static final ResourceLocation UNREGISTERED = ResourceLocation.fromNamespaceAndPath("reterraforged", "unregistered");

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

        List<String> palette = new ArrayList<>(16);
        Map<Biome, Entry> entryCache = new IdentityHashMap<>(32);

        int halfSize = size / 2;
        Climate.Sampler sampler = this.resolver.tileClimateSampler(tile, centerX, centerZ, zoom);

        // Mutable holder to satisfy lambda effectively-final constraint
        final class LocalQuartCache {
            int qX = Integer.MIN_VALUE;
            int qY = Integer.MIN_VALUE;
            int qZ = Integer.MIN_VALUE;
            Holder<Biome> biome = null;
        }
        final LocalQuartCache quartCache = new LocalQuartCache();

        try (BiomePreviewIntegration.Session ignored = this.resolver.openIntegrationSession()) {
            tile.iterate((cell, x, z) -> {
                // Check cancellation once per row
                if (x == 0) cancellation.check();

                int blockX = centerX + (x - halfSize) * zoom;
                int blockZ = centerZ + (z - halfSize) * zoom;
                int surfaceY = surfaceY(cell, levels);

                int qX = QuartPos.fromBlock(blockX);
                int qY = QuartPos.fromBlock(surfaceY);
                int qZ = QuartPos.fromBlock(blockZ);

                Holder<Biome> biome;
                if (qX == quartCache.qX && qY == quartCache.qY && qZ == quartCache.qZ && quartCache.biome != null) {
                    biome = quartCache.biome;
                } else {
                    biome = this.resolver.resolveQuart(qX, qY, qZ, sampler);
                    quartCache.qX = qX;
                    quartCache.qY = qY;
                    quartCache.qZ = qZ;
                    quartCache.biome = biome;
                }

                Biome rawBiome = biome.value();
                Entry entry = entryCache.get(rawBiome);
                if (entry == null) {
                    ResourceLocation id = biome.unwrapKey().map(ResourceKey::location).orElse(UNREGISTERED);
                    short paletteIdx = (short) palette.size();
                    palette.add(id.toString());
                    entry = new Entry(paletteIdx, BiomePreviewColors.color(biome, id));
                    entryCache.put(rawBiome, entry);
                }

                int index = z * size + x;
                indices[index] = entry.paletteIndex;
                colors[index] = entry.color;
            });
        }
        return new Sidecar(this.cacheKey, size, palette.toArray(new String[0]), indices, colors, this.resolver.warning());
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
        // Fast structural fingerprinting instead of full JSON serialization & registry sorting
        int presetHash = preset.hashCode();
        int biomeCount = (int) settings.worldgenLoadContext().lookupOrThrow(Registries.BIOME).listElements().count();
        String biomeSource = settings.selectedDimensions().overworld().getBiomeSource().getClass().getName();

        return new CacheKey(
                settings.options().seed(),
                presetHash,
                settings.dataConfiguration().hashCode(),
                biomeSource,
                biomeCount
        );
    }

    private static int surfaceY(Cell cell, Levels levels) {
        int minY = -levels.worldDepth;
        int maxY = Math.max(minY, levels.terrainScaleFactor - 1);
        return Math.max(minY, Math.min(maxY, levels.scale(cell.height)));
    }

    private record Entry(short paletteIndex, int color) {}

    record CacheKey(long seed, int presetHash, int dataConfigHash, String biomeSource, int biomeCount) {}

    static final class Sidecar {
        private final CacheKey cacheKey;
        private final int size;
        private final String[] palette;
        private final short[] indices;
        private final int[] colors;
        private final String warning;

        private Sidecar(CacheKey cacheKey, int size, String[] palette, short[] indices, int[] colors, String warning) {
            this.cacheKey = cacheKey;
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

        CacheKey cacheKey() {
            return this.cacheKey;
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