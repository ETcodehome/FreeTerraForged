package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;

/** Server-side authority for one realized dimension worldgen bootstrap. */
public record WorldgenEpoch(
	UUID id,
	ResourceKey<LevelStem> dimension,
	long seed,
	RegistryAccess.Frozen registries,
	LevelStem selectedStem,
	String settingsIdentity,
	String resourceLayerFingerprint,
	TagEpoch tagEpoch,
	long contributionSequence
) implements WorldgenOwner {
	public WorldgenEpoch {
		id = Objects.requireNonNull(id, "id");
		dimension = Objects.requireNonNull(dimension, "dimension");
		registries = Objects.requireNonNull(registries, "registries").freeze();
		selectedStem = Objects.requireNonNull(selectedStem, "selectedStem");
		settingsIdentity = Objects.requireNonNull(settingsIdentity, "settingsIdentity");
		resourceLayerFingerprint = Objects.requireNonNull(resourceLayerFingerprint, "resourceLayerFingerprint");
		tagEpoch = Objects.requireNonNull(tagEpoch, "tagEpoch");
		if (contributionSequence < 0L) {
			throw new IllegalArgumentException("Contribution sequence must be non-negative");
		}
	}

	public static WorldgenEpoch create(
		ResourceKey<LevelStem> dimension,
		long seed,
		RegistryAccess registries,
		LevelStem selectedStem,
		String settingsIdentity,
		String resourceLayerFingerprint,
		TagEpoch tagEpoch
	) {
		return new WorldgenEpoch(
			UUID.randomUUID(), dimension, seed, registries.freeze(), selectedStem,
			settingsIdentity, resourceLayerFingerprint, tagEpoch, 0L
		);
	}

	@Override
	public WorldgenOwnerType type() {
		return WorldgenOwnerType.WORLDGEN_EPOCH;
	}

	@Override
	public HolderLookup.Provider lookups() {
		return this.registries;
	}

	public WorldgenEpoch withTagEpoch(TagEpoch replacement) {
		return new WorldgenEpoch(
			this.id, this.dimension, this.seed, this.registries, this.selectedStem,
			this.settingsIdentity, this.resourceLayerFingerprint, replacement,
			this.contributionSequence
		);
	}

	public WorldgenEpoch nextContributionSequence() {
		return new WorldgenEpoch(
			this.id, this.dimension, this.seed, this.registries, this.selectedStem,
			this.settingsIdentity, this.resourceLayerFingerprint, this.tagEpoch,
			Math.addExact(this.contributionSequence, 1L)
		);
	}
}
