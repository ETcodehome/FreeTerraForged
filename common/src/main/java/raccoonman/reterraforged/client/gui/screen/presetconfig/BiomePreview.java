package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.JsonOps;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
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
        String[] ids = new String[size * size];
		int[] colors = new int[size * size];
		Map<ResourceLocation, Entry> entries = new HashMap<>();
		int halfSize = size / 2;
		Climate.Sampler sampler = this.resolver.tileClimateSampler(tile, centerX, centerZ, zoom);

        try (BiomePreviewIntegration.Session ignored = this.resolver.openIntegrationSession()) {
            tile.iterate((cell, x, z) -> {
                cancellation.check();
                int blockX = centerX + (x - halfSize) * zoom;
                int blockZ = centerZ + (z - halfSize) * zoom;
                int surfaceY = surfaceY(cell, levels);
				Holder<Biome> biome = this.resolver.resolveQuart(
					QuartPos.fromBlock(blockX),
					QuartPos.fromBlock(surfaceY),
					QuartPos.fromBlock(blockZ),
					sampler
				);
                ResourceLocation id = biome.unwrapKey().map(key -> key.location()).orElse(UNREGISTERED);
                Entry entry = entries.computeIfAbsent(
                    id,
                    key -> new Entry(key.toString(), BiomePreviewColors.color(biome, key))
                );
                int index = z * size + x;
                ids[index] = entry.id;
                colors[index] = entry.color;
            });
        }
        return new Sidecar(this.cacheKey, size, ids, colors, this.resolver.warning());
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
        String encodedPreset = Preset.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, preset)
            .getOrThrow(message -> new IllegalStateException("Failed to fingerprint preview preset: " + message))
            .toString();
        List<String> biomeIds = settings.worldgenLoadContext().lookupOrThrow(Registries.BIOME)
            .listElementIds()
            .map(key -> key.location().toString())
            .sorted()
            .toList();
        String biomeSource = settings.selectedDimensions().overworld().getBiomeSource().getClass().getName();
        return new CacheKey(
            settings.options().seed(),
            encodedPreset,
            settings.dataConfiguration().toString(),
            biomeSource,
            biomeIds
        );
    }

    private static int surfaceY(Cell cell, Levels levels) {
        int minY = -levels.worldDepth;
        int maxY = Math.max(minY, levels.terrainScaleFactor - 1);
        return Math.max(minY, Math.min(maxY, levels.scale(cell.height)));
    }

    private record Entry(String id, int color) {
    }

    record CacheKey(long seed, String preset, String dataConfiguration, String biomeSource, List<String> biomeIds) {
    }

    static final class Sidecar {
        private final CacheKey cacheKey;
        private final int size;
        private final String[] ids;
        private final int[] colors;
        private final String warning;

        private Sidecar(CacheKey cacheKey, int size, String[] ids, int[] colors, String warning) {
            this.cacheKey = cacheKey;
            this.size = size;
            this.ids = ids;
            this.colors = colors;
            this.warning = warning;
        }

        String id(int x, int z) {
            return this.ids[this.index(x, z)];
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
