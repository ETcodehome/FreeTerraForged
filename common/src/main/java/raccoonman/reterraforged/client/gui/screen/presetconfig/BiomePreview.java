package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import com.mojang.serialization.JsonOps;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewResolver;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenContributionRevision;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenFingerprints;

final class BiomePreview implements AutoCloseable {
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
        GeneratorContext generatorContext,
        CacheKey cacheKey
    ) {
        long seed = settings.options().seed();
        LevelStem activeOverworld = settings.selectedDimensions().get(LevelStem.OVERWORLD).orElseThrow();
        BiomePreviewResolver resolver = BiomePreviewResolver.create(
            settings.worldgenLoadContext(),
            provider,
			LevelStem.OVERWORLD,
            activeOverworld.type(),
            activeOverworld.generator(),
            preset,
            generatorContext,
            seed,
            cacheKey.selectedGraph(),
            cacheKey.dataConfiguration(),
            cacheKey.tagFingerprint()
        );
        return new BiomePreview(resolver, cacheKey);
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
        int[] indices = new int[size * size];
        int[] colors = new int[size * size];
        ArrayList<String> palette = new ArrayList<>();
        IdentityHashMap<Holder<Biome>, Entry> entries = new IdentityHashMap<>();
        BiomePreviewResolver.ResolvedTile resolved = this.resolver.resolveSurfaceTile(
            tile, centerX, centerZ, zoom, levels, cancellation::isCancelled
        );

        for (int z = 0; z < size; z++) {
            cancellation.check();
            int rowOffset = z * size;
            for (int x = 0; x < size; x++) {
                Holder<Biome> biome = resolved.biomeAt(x, z);

                Entry entry = entries.get(biome);
                if (entry == null) {
                    ResourceLocation id = biome.unwrapKey().map(ResourceKey::location).orElse(UNREGISTERED);
                    entry = new Entry(palette.size(), BiomePreviewColors.color(biome, id));
                    entries.put(biome, entry);
                    palette.add(id.toString());
                }
                int index = rowOffset + x;
                indices[index] = entry.paletteIndex();
                colors[index] = entry.color();
            }
        }
        return new Sidecar(
            this.cacheKey,
            size,
            palette.toArray(String[]::new),
            indices,
            colors
        );
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
        LevelStem selectedStem = settings.selectedDimensions().get(LevelStem.OVERWORLD).orElseThrow();
        String selectedGraph = LevelStem.CODEC.encodeStart(
                RegistryOps.create(JsonOps.INSTANCE, settings.worldgenLoadContext()), selectedStem
            )
            .getOrThrow(message -> new IllegalStateException("Failed to fingerprint selected preview stem: " + message))
            .toString();
        String tagFingerprint = WorldgenFingerprints.tags(settings.worldgenLoadContext());
		return new CacheKey(
            settings.options().seed(),
            encodedPreset,
            settings.dataConfiguration().toString(),
            selectedGraph,
			tagFingerprint,
			WorldgenContributionRevision.current(),
			biomeIds
        );
    }

    @Override
    public void close() {
        this.resolver.close();
    }

    private record Entry(int paletteIndex, int color) {
    }

    record CacheKey(
        long seed,
        String preset,
        String dataConfiguration,
		String selectedGraph,
		String tagFingerprint,
		long contributionRevision,
		List<String> biomeIds
	) {
		CacheKey {
			biomeIds = List.copyOf(biomeIds);
        }
    }

    static final class Sidecar {
        private final CacheKey cacheKey;
        private final int size;
        private final String[] palette;
        private final int[] indices;
        private final int[] colors;

        private Sidecar(
            CacheKey cacheKey,
            int size,
            String[] palette,
            int[] indices,
            int[] colors
        ) {
            this.cacheKey = cacheKey;
            this.size = size;
            this.palette = palette;
            this.indices = indices;
            this.colors = colors;
        }

        String id(int x, int z) {
            int index = this.index(x, z);
            return this.palette[this.indices[index]];
        }

        int color(int x, int z) {
            return this.colors[this.index(x, z)];
        }

        CacheKey cacheKey() {
            return this.cacheKey;
        }

        private int index(int x, int z) {
            int clampedX = Math.max(0, Math.min(this.size - 1, x));
            int clampedZ = Math.max(0, Math.min(this.size - 1, z));
            return clampedZ * this.size + clampedX;
        }
    }
}
