package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewResolver;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenContributionRevision;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenProviderCatalog;

final class BiomePreview implements AutoCloseable {
    private static final ResourceLocation UNREGISTERED = ResourceLocation.fromNamespaceAndPath("reterraforged", "unregistered");

    private final BiomePreviewResolver resolver;
    private final CacheKey cacheKey;

    private BiomePreview(BiomePreviewResolver resolver, CacheKey cacheKey) {
        this.resolver = resolver;
        this.cacheKey = cacheKey;
    }

    static BiomePreview create(
        net.minecraft.core.HolderLookup.Provider provider,
        Preset preset,
        GeneratorContext generatorContext,
        CacheKey cacheKey,
        BooleanSupplier cancelled
    ) {
		LevelStem activeOverworld = cacheKey.selectedStem();
		BiomePreviewResolver resolver = BiomePreviewResolver.create(
			cacheKey.registrySnapshot(),
            provider,
			LevelStem.OVERWORLD,
            activeOverworld.type(),
            activeOverworld.generator(),
            preset,
            generatorContext,
            cacheKey.seed(),
			cacheKey.settingsIdentity(),
            cacheKey.dataConfiguration().toString(),
				cacheKey.tagFingerprint(),
				cacheKey.contributionRevision(),
				cacheKey.providers(),
				cancelled
        );
        return new BiomePreview(resolver, cacheKey);
    }

    Sidecar resolve(
        Tile tile,
        int centerX,
        int centerZ,
        int zoom,
        Levels levels,
        BooleanSupplier cancelled
    ) {
        int size = tile.getBlockSize().size();
        short[] indices = new short[size * size];
        ArrayList<String> palette = new ArrayList<>();
        int[] paletteColors = new int[32];
        IdentityHashMap<Holder<Biome>, Entry> entries = new IdentityHashMap<>();
        BiomePreviewResolver.ResolvedTile resolved = this.resolver.resolveSurfaceTile(
            tile, centerX, centerZ, zoom, levels, cancelled
        );

        for (int z = 0; z < size; z++) {
            checkCancelled(cancelled);
            int rowOffset = z * size;
            for (int x = 0; x < size; x++) {
                Holder<Biome> biome = resolved.biomeAt(x, z);

                Entry entry = entries.get(biome);
                if (entry == null) {
                    ResourceLocation id = biome.unwrapKey().map(ResourceKey::location).orElse(UNREGISTERED);
                    entry = new Entry(palette.size(), BiomePreviewColors.color(biome, id));
                    entries.put(biome, entry);
                    palette.add(id.toString());
                    if (entry.paletteIndex() >= paletteColors.length) {
                        paletteColors = Arrays.copyOf(paletteColors, paletteColors.length * 2);
                    }
                    paletteColors[entry.paletteIndex()] = entry.color();
                }
                int index = rowOffset + x;
                if (entry.paletteIndex() > 0xFFFF) {
                    throw new IllegalStateException("Preview biome palette exceeds its 65,536-entry index space");
                }
                indices[index] = (short) entry.paletteIndex();
            }
        }
        return new Sidecar(
            size,
            palette.toArray(String[]::new),
            indices,
            Arrays.copyOf(paletteColors, palette.size())
        );
    }

    Sidecar resolveCached(
        PreviewComputationCache cache,
        Tile tile,
        int centerX,
        int centerZ,
        int zoom,
        Levels levels,
        BooleanSupplier cancelled
    ) {
        int size = tile.getBlockSize().size();
        PreviewComputationCache.SidecarKey key = new PreviewComputationCache.SidecarKey(
            this.cacheKey,
            centerX,
            centerZ,
            zoom,
            size
        );
        return cache.sidecar(key, cancelled, cacheCancelled -> this.resolve(
            tile, centerX, centerZ, zoom, levels,
            cacheCancelled::getAsBoolean
        )).join();
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Preview sidecar generation superseded");
        }
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
		WorldDataConfiguration dataConfiguration,
		String settingsIdentity,
		String tagFingerprint,
		WorldgenContributionRevision.Snapshot contributionRevision,
		net.minecraft.core.RegistryAccess.Frozen registrySnapshot,
		LevelStem selectedStem,
		WorldgenProviderCatalog providers,
		long generation
	) {
		CacheKey {
			preset = java.util.Objects.requireNonNull(preset, "preset");
			dataConfiguration = java.util.Objects.requireNonNull(dataConfiguration, "dataConfiguration");
			settingsIdentity = java.util.Objects.requireNonNull(settingsIdentity, "settingsIdentity");
			tagFingerprint = java.util.Objects.requireNonNull(tagFingerprint, "tagFingerprint");
			contributionRevision = java.util.Objects.requireNonNull(contributionRevision, "contributionRevision");
			registrySnapshot = java.util.Objects.requireNonNull(registrySnapshot, "registrySnapshot");
			selectedStem = java.util.Objects.requireNonNull(selectedStem, "selectedStem");
			providers = java.util.Objects.requireNonNull(providers, "providers");
			if (generation < 0L) {
				throw new IllegalArgumentException("Preview request generation must be non-negative");
			}
		}

		boolean sameInputs(
			long seed,
			String preset,
			WorldDataConfiguration dataConfiguration,
			String settingsIdentity,
			String tagFingerprint,
			net.minecraft.core.RegistryAccess.Frozen registrySnapshot,
			LevelStem selectedStem,
			WorldgenProviderCatalog providers
		) {
			return this.seed == seed
				&& this.preset.equals(preset)
				&& this.dataConfiguration.equals(dataConfiguration)
				&& this.settingsIdentity.equals(settingsIdentity)
				&& this.tagFingerprint.equals(tagFingerprint)
				&& this.registrySnapshot == registrySnapshot
				&& this.selectedStem == selectedStem
				&& this.providers == providers;
		}

		@Override
		public boolean equals(Object value) {
			if (this == value) {
				return true;
			}
			if (!(value instanceof CacheKey other)) {
				return false;
			}
			return this.seed == other.seed
				&& this.preset.equals(other.preset)
				&& this.dataConfiguration.equals(other.dataConfiguration)
				&& this.settingsIdentity.equals(other.settingsIdentity)
				&& this.tagFingerprint.equals(other.tagFingerprint)
				&& this.contributionRevision.equals(other.contributionRevision)
				&& this.registrySnapshot == other.registrySnapshot
				&& this.selectedStem == other.selectedStem
				&& this.providers == other.providers
				&& this.generation == other.generation;
		}

		@Override
		public int hashCode() {
			int values = java.util.Objects.hash(
				this.seed, this.preset, this.dataConfiguration, this.settingsIdentity,
				this.tagFingerprint, this.contributionRevision
			);
			values = 31 * values + System.identityHashCode(this.registrySnapshot);
			values = 31 * values + System.identityHashCode(this.selectedStem);
			values = 31 * values + System.identityHashCode(this.providers);
			return 31 * values + Long.hashCode(this.generation);
		}
	}

    static final class Sidecar {
        private final int size;
        private final String[] palette;
        private final short[] indices;
        private final int[] paletteColors;

        Sidecar(
            int size,
            String[] palette,
            short[] indices,
            int[] paletteColors
        ) {
            if (size <= 0 || indices.length != Math.multiplyExact(size, size)) {
                throw new IllegalArgumentException("Preview biome indices do not match the tile dimensions");
            }
            if (palette.length != paletteColors.length || palette.length > 0x10000) {
                throw new IllegalArgumentException("Preview biome palette IDs and colors must share a valid index space");
            }
            this.size = size;
            this.palette = palette;
            this.indices = indices;
            this.paletteColors = paletteColors;
        }

        String id(int x, int z) {
            return this.palette[this.paletteIndex(x, z)];
        }

        int color(int x, int z) {
            return this.paletteColors[this.paletteIndex(x, z)];
        }

        private int index(int x, int z) {
            int clampedX = Math.max(0, Math.min(this.size - 1, x));
            int clampedZ = Math.max(0, Math.min(this.size - 1, z));
            return clampedZ * this.size + clampedX;
        }

        private int paletteIndex(int x, int z) {
            return Short.toUnsignedInt(this.indices[this.index(x, z)]);
        }
    }
}
