package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.BiomePreviewResolver;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.PreviewGrid;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementConfig;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementConfigSnapshot;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenContributionRevision;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenProviderCatalog;

final class BiomePreview implements AutoCloseable {
    private static final ResourceLocation UNREGISTERED = FTFCommon.location("unregistered");

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
				cacheKey.biomeReplacementConfig(),
				cacheKey.contributionRevision(),
				cacheKey.providers(),
				cancelled
        );
        return new BiomePreview(resolver, cacheKey);
    }

    Sidecar resolve(
        PreviewGrid tile,
        int centerX,
        int centerZ,
        int zoom,
        Levels levels,
        BooleanSupplier cancelled
    ) {
        int size = tile.size();
        short[] indices = new short[size * size];
        short[] overrideIndices = new short[size * size];
        byte[] hatchPhases = new byte[size * size];
        ArrayList<String> palette = new ArrayList<>();
        ArrayList<OverrideInfo> overrides = new ArrayList<>();
        overrides.add(null);
        int[] paletteColors = new int[32];
        IdentityHashMap<Holder<Biome>, Entry> entries = new IdentityHashMap<>();
        Map<OverrideKey, Integer> overrideEntries = new HashMap<>();
        BiomePreviewResolver.ResolvedTile resolved = this.resolver.resolveSurfaceTile(
            tile, centerX, centerZ, zoom, levels, cancelled
        );

        for (int z = 0; z < size; z++) {
            checkCancelled(cancelled);
            int rowOffset = z * size;
            for (int x = 0; x < size; x++) {
                BiomeReplacementPlan.PreviewSelection selection = resolved.selectionAt(x, z);
                Holder<Biome> biome = selection.finalBiome();

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
                if (selection.overrideKind() != BiomeReplacementPlan.OverrideKind.NONE) {
                    OverrideKey overrideKey = new OverrideKey(
                        selection.preReplacementBiome(), selection.finalBiome(),
                        selection.overrideKind(), selection.rule()
                    );
                    int overrideIndex = overrideEntries.computeIfAbsent(overrideKey, ignored -> {
                        ResourceLocation sourceId = selection.preReplacementBiome().unwrapKey()
                            .map(ResourceKey::location).orElse(UNREGISTERED);
                        ResourceLocation targetId = selection.finalBiome().unwrapKey()
                            .map(ResourceKey::location).orElse(UNREGISTERED);
                        String selector = selection.rule().selectorKind()
                            == BiomeReplacementConfig.SelectorKind.TAG
                                ? "#" + selection.rule().selector()
                                : selection.rule().selector();
                        overrides.add(new OverrideInfo(
                            selection.overrideKind(), sourceId.toString(), targetId.toString(),
                            selection.rule().index(), selection.rule().line(), selector
                        ));
                        return overrides.size() - 1;
                    });
                    if (overrideIndex > 0xFFFF) {
                        throw new IllegalStateException(
                            "Preview override palette exceeds its 65,535-entry index space"
                        );
                    }
                    overrideIndices[index] = (short) overrideIndex;
                    int worldX = Math.addExact(
                        centerX, Math.multiplyExact(x - size / 2, zoom)
                    );
                    int worldZ = Math.addExact(
                        centerZ, Math.multiplyExact(z - size / 2, zoom)
                    );
                    hatchPhases[index] = (byte) hatchPhase(worldX, worldZ, zoom);
                }
            }
        }
        return new Sidecar(
            size,
            palette.toArray(String[]::new),
            indices,
            Arrays.copyOf(paletteColors, palette.size()),
            overrideIndices,
            hatchPhases,
            overrides.toArray(OverrideInfo[]::new)
        );
    }

    Sidecar resolveCached(
        PreviewComputationCache cache,
        PreviewGrid tile,
        int centerX,
        int centerZ,
        int zoom,
        Levels levels,
        BooleanSupplier cancelled
    ) {
        int size = tile.size();
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

    static int hatchPhase(int worldX, int worldZ, int zoom) {
        if (zoom < 1) {
            throw new IllegalArgumentException("Preview zoom must be positive");
        }
        int sampleX = Math.floorDiv(worldX, zoom);
        int sampleZ = Math.floorDiv(worldZ, zoom);
        return Math.floorMod(Math.floorMod(sampleX, 4) + Math.floorMod(sampleZ, 4), 4);
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

    private record OverrideKey(
        Holder<Biome> source,
        Holder<Biome> target,
        BiomeReplacementPlan.OverrideKind kind,
        BiomeReplacementPlan.RuleInfo rule
    ) {
    }

    record OverrideInfo(
        BiomeReplacementPlan.OverrideKind kind,
        String source,
        String target,
        int ruleIndex,
        int line,
        String selector
    ) {
        OverrideInfo {
            java.util.Objects.requireNonNull(kind, "kind");
            java.util.Objects.requireNonNull(source, "source");
            java.util.Objects.requireNonNull(target, "target");
            java.util.Objects.requireNonNull(selector, "selector");
            if (kind == BiomeReplacementPlan.OverrideKind.NONE || ruleIndex < 0 || line <= 0) {
                throw new IllegalArgumentException("Override palette entry has invalid provenance");
            }
        }

    }

    record CacheKey(
		long seed,
		String preset,
		WorldDataConfiguration dataConfiguration,
		String settingsIdentity,
		String tagFingerprint,
		BiomeReplacementConfigSnapshot biomeReplacementConfig,
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
			biomeReplacementConfig = java.util.Objects.requireNonNull(
				biomeReplacementConfig, "biomeReplacementConfig"
			);
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
			BiomeReplacementConfigSnapshot biomeReplacementConfig,
			net.minecraft.core.RegistryAccess.Frozen registrySnapshot,
			LevelStem selectedStem,
			WorldgenProviderCatalog providers
		) {
			return this.seed == seed
				&& this.preset.equals(preset)
				&& this.dataConfiguration.equals(dataConfiguration)
				&& this.settingsIdentity.equals(settingsIdentity)
				&& this.tagFingerprint.equals(tagFingerprint)
				&& this.biomeReplacementConfig.revision() == biomeReplacementConfig.revision()
				&& this.biomeReplacementConfig.fingerprint().equals(biomeReplacementConfig.fingerprint())
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
				&& this.biomeReplacementConfig.revision() == other.biomeReplacementConfig.revision()
				&& this.biomeReplacementConfig.fingerprint().equals(
					other.biomeReplacementConfig.fingerprint()
				)
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
				this.tagFingerprint, this.biomeReplacementConfig.revision(),
				this.biomeReplacementConfig.fingerprint(), this.contributionRevision
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
        private final short[] overrideIndices;
        private final byte[] hatchPhases;
        private final OverrideInfo[] overrides;

        Sidecar(
            int size,
            String[] palette,
            short[] indices,
            int[] paletteColors,
            short[] overrideIndices,
            byte[] hatchPhases,
            OverrideInfo[] overrides
        ) {
            if (size <= 0 || indices.length != Math.multiplyExact(size, size)) {
                throw new IllegalArgumentException("Preview biome indices do not match the tile dimensions");
            }
            if (palette.length != paletteColors.length || palette.length > 0x10000) {
                throw new IllegalArgumentException("Preview biome palette IDs and colors must share a valid index space");
            }
            if (overrideIndices.length != indices.length || hatchPhases.length != indices.length
                || overrides.length > 0x10000 || overrides.length == 0 || overrides[0] != null) {
                throw new IllegalArgumentException("Preview override provenance does not match the tile dimensions");
            }
            this.size = size;
            this.palette = palette;
            this.indices = indices;
            this.paletteColors = paletteColors;
            this.overrideIndices = overrideIndices;
            this.hatchPhases = hatchPhases;
            this.overrides = overrides;
        }

        String id(int x, int z) {
            return this.palette[this.paletteIndex(x, z)];
        }

        String legendId(int x, int z) {
            String target = this.id(x, z);
            int overrideIndex = this.overrideIndex(x, z);
            if (overrideIndex == 0) {
                return target;
            }
            return target + " (overriding " + this.overrides[overrideIndex].source() + ")";
        }

        int color(int x, int z) {
            return this.paletteColors[this.paletteIndex(x, z)];
        }

        boolean hasOverride(int x, int z) {
            return this.overrideIndex(x, z) != 0;
        }

        boolean hatchAt(int x, int z) {
            int index = this.index(x, z);
            return Short.toUnsignedInt(this.overrideIndices[index]) != 0
                && this.hatchPhases[index] == 0;
        }

        int hatchPhase(int x, int z) {
            return Byte.toUnsignedInt(this.hatchPhases[this.index(x, z)]);
        }

        private int index(int x, int z) {
            int clampedX = Math.max(0, Math.min(this.size - 1, x));
            int clampedZ = Math.max(0, Math.min(this.size - 1, z));
            return clampedZ * this.size + clampedX;
        }

        private int paletteIndex(int x, int z) {
            return Short.toUnsignedInt(this.indices[this.index(x, z)]);
        }

        private int overrideIndex(int x, int z) {
            return Short.toUnsignedInt(this.overrideIndices[this.index(x, z)]);
        }
    }
}
