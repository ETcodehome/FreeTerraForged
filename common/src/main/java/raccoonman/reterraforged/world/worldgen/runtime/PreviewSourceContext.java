package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Public inputs from which a mechanism provider may create one request-owned preview source.
 *
 * <p>The realized source is an input boundary, not preview-owned state. A provider must use only
 * public APIs to create a fresh source backed by immutable snapshots or request-confined state.
 */
public record PreviewSourceContext(
	long seed,
	RegistryAccess.Frozen registries,
	HolderLookup.Provider lookups,
	BiomeSource realizedSource,
	Holder<NoiseGeneratorSettings> noiseSettings,
	String settingsIdentity,
	String resourceLayerFingerprint,
	TagEpoch tagEpoch
) {
	public PreviewSourceContext {
		registries = Objects.requireNonNull(registries, "registries").freeze();
		lookups = Objects.requireNonNull(lookups, "lookups");
		realizedSource = Objects.requireNonNull(realizedSource, "realizedSource");
		noiseSettings = Objects.requireNonNull(noiseSettings, "noiseSettings");
		settingsIdentity = Objects.requireNonNull(settingsIdentity, "settingsIdentity");
		resourceLayerFingerprint = Objects.requireNonNull(resourceLayerFingerprint, "resourceLayerFingerprint");
		tagEpoch = Objects.requireNonNull(tagEpoch, "tagEpoch");
	}
}
