package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

public record PreviewSourceContext(
	long seed,
	RegistryAccess.Frozen registries,
	HolderLookup.Provider lookups,
	BiomeSource realizedSource,
	Holder<NoiseGeneratorSettings> noiseSettings,
	String settingsIdentity,
	String resourceLayerFingerprint,
	TagEpoch tagEpoch,
	BooleanSupplier cancelled
) {
	public PreviewSourceContext(
		long seed,
		RegistryAccess.Frozen registries,
		HolderLookup.Provider lookups,
		BiomeSource realizedSource,
		Holder<NoiseGeneratorSettings> noiseSettings,
		String settingsIdentity,
		String resourceLayerFingerprint,
		TagEpoch tagEpoch
	) {
		this(
			seed, registries, lookups, realizedSource, noiseSettings, settingsIdentity,
			resourceLayerFingerprint, tagEpoch, () -> false
		);
	}

	public PreviewSourceContext {
		registries = Objects.requireNonNull(registries, "registries");
		lookups = Objects.requireNonNull(lookups, "lookups");
		realizedSource = Objects.requireNonNull(realizedSource, "realizedSource");
		noiseSettings = Objects.requireNonNull(noiseSettings, "noiseSettings");
		settingsIdentity = Objects.requireNonNull(settingsIdentity, "settingsIdentity");
		resourceLayerFingerprint = Objects.requireNonNull(resourceLayerFingerprint, "resourceLayerFingerprint");
		tagEpoch = Objects.requireNonNull(tagEpoch, "tagEpoch");
		cancelled = Objects.requireNonNull(cancelled, "cancelled");
	}

	public void checkCancelled() {
		if (this.cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Preview source acquisition was superseded");
		}
	}
}
