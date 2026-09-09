package raccoonman.reterraforged.client.gui.screen.presetconfig;

import com.mojang.serialization.JsonOps;

import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenCapabilityDiscovery;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenContributionRevision;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenFingerprints;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenProviderCatalog;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenSettingsIdentity;

final class PreviewRequestKeyFactory implements AutoCloseable {
	private volatile BiomePreview.CacheKey current;
	private Input capturedInput;
	private long inputRevision;
	private long generation;
	private volatile boolean closed;

	synchronized void invalidatePreset() {
		if (this.closed) {
			return;
		}
		this.inputRevision = Math.incrementExact(this.inputRevision);
		this.capturedInput = null;
	}

	synchronized Input capture(WorldCreationContext settings, Preset preset) {
		if (this.closed) {
			throw new java.util.concurrent.CancellationException("Preview request keys are closed");
		}
		RegistryAccess.Frozen registries = settings.worldgenLoadContext();
		LevelStem selectedStem = settings.selectedDimensions().get(LevelStem.OVERWORLD).orElseThrow();
		long seed = settings.options().seed();
		WorldDataConfiguration dataConfiguration = settings.dataConfiguration();
		if (this.capturedInput != null && this.capturedInput.matches(
			this.inputRevision, seed, preset, dataConfiguration, registries, selectedStem
		)) {
			return this.capturedInput;
		}
		this.capturedInput = new Input(
			this.inputRevision, seed, preset, preset.copy(), dataConfiguration, registries, selectedStem
		);
		return this.capturedInput;
	}

	synchronized BiomePreview.CacheKey create(Input input, java.util.function.BooleanSupplier cancelled) {
		this.requireActive(cancelled);
		String presetFingerprint = input.presetFingerprint();
		RegistryAccess.Frozen registries = input.registries();
		LevelStem selectedStem = input.selectedStem();
		WorldgenProviderCatalog providers = selectedStem.generator()
			instanceof raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator terraForged
			? terraForged.acquireProviderCatalog()
			: this.current != null
				&& this.current.registrySnapshot() == registries
				&& this.current.selectedStem() == selectedStem
					? this.current.providers()
					: WorldgenCapabilityDiscovery.discover(PreviewRequestKeyFactory.class.getClassLoader());
		WorldDataConfiguration dataConfiguration = input.dataConfiguration();
		String settingsIdentity = WorldgenSettingsIdentity.describe(selectedStem.generator());
		String tagFingerprint = this.current != null
			&& this.current.registrySnapshot() == registries
			? this.current.tagFingerprint()
			: WorldgenFingerprints.tags(registries);

		if (this.current != null && this.current.sameInputs(
			input.seed(), presetFingerprint, dataConfiguration, settingsIdentity,
			tagFingerprint, registries, selectedStem, providers
		)) {
			WorldgenContributionRevision.Snapshot contributions = WorldgenContributionRevision.snapshot(
				LevelStem.OVERWORLD, this.current.providers()
			);
			if (contributions.equals(this.current.contributionRevision())) {
				this.requireActive(cancelled);
				return this.current;
			}
		}

		WorldgenContributionRevision.Snapshot contributions = WorldgenContributionRevision.snapshot(
			LevelStem.OVERWORLD, providers
		);
		this.requireActive(cancelled);
		this.current = new BiomePreview.CacheKey(
			input.seed(), presetFingerprint, dataConfiguration, settingsIdentity,
			tagFingerprint, contributions, registries, selectedStem, providers,
			this.advanceGeneration()
		);
		return this.current;
	}

	synchronized long advanceGeneration() {
		if (this.closed) {
			throw new java.util.concurrent.CancellationException(
				"Preview request keys are closed"
			);
		}
		this.generation = Math.incrementExact(this.generation);
		return this.generation;
	}

	@Override
	public synchronized void close() {
		this.closed = true;
		this.current = null;
		this.capturedInput = null;
	}

	private void requireActive(java.util.function.BooleanSupplier cancelled) {
		if (this.closed || cancelled.getAsBoolean()) {
			throw new java.util.concurrent.CancellationException(
				"Preview key acquisition was closed or superseded"
			);
		}
	}

	static final class Input {
		private final long revision;
		private final long seed;
		private final Preset sourcePreset;
		private final Preset preset;
		private final WorldDataConfiguration dataConfiguration;
		private final RegistryAccess.Frozen registries;
		private final LevelStem selectedStem;
		private String presetFingerprint;

		private Input(
			long revision,
			long seed,
			Preset sourcePreset,
			Preset preset,
			WorldDataConfiguration dataConfiguration,
			RegistryAccess.Frozen registries,
			LevelStem selectedStem
		) {
			this.revision = revision;
			this.seed = seed;
			this.sourcePreset = java.util.Objects.requireNonNull(sourcePreset, "sourcePreset");
			this.preset = java.util.Objects.requireNonNull(preset, "preset");
			this.dataConfiguration = java.util.Objects.requireNonNull(dataConfiguration, "dataConfiguration");
			this.registries = java.util.Objects.requireNonNull(registries, "registries");
			this.selectedStem = java.util.Objects.requireNonNull(selectedStem, "selectedStem");
		}

		private boolean matches(
			long revision,
			long seed,
			Preset sourcePreset,
			WorldDataConfiguration dataConfiguration,
			RegistryAccess.Frozen registries,
			LevelStem selectedStem
		) {
			return this.revision == revision
				&& this.seed == seed
				&& this.sourcePreset == sourcePreset
				&& this.dataConfiguration.equals(dataConfiguration)
				&& this.registries == registries
				&& this.selectedStem == selectedStem;
		}

		long seed() {
			return this.seed;
		}

		Preset preset() {
			return this.preset;
		}

		WorldDataConfiguration dataConfiguration() {
			return this.dataConfiguration;
		}

		RegistryAccess.Frozen registries() {
			return this.registries;
		}

		LevelStem selectedStem() {
			return this.selectedStem;
		}

		synchronized String presetFingerprint() {
			if (this.presetFingerprint == null) {
				this.presetFingerprint = Preset.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, this.preset)
					.getOrThrow(message -> new IllegalStateException(
						"Failed to fingerprint preview preset: " + message
					))
					.toString();
			}
			return this.presetFingerprint;
		}
	}
}
