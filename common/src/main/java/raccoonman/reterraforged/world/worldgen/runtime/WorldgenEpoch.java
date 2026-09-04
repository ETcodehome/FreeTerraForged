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
	long resourceRevision,
	String resourceLayerFingerprint,
	TagEpoch tagEpoch,
	WorldgenContributionRevision.Snapshot contributionRevision
) implements WorldgenOwner {
	public WorldgenEpoch {
		id = Objects.requireNonNull(id, "id");
		dimension = Objects.requireNonNull(dimension, "dimension");
		registries = Objects.requireNonNull(registries, "registries");
		selectedStem = Objects.requireNonNull(selectedStem, "selectedStem");
		settingsIdentity = Objects.requireNonNull(settingsIdentity, "settingsIdentity");
		if (resourceRevision < 0L) {
			throw new IllegalArgumentException("Resource revision must be non-negative");
		}
		resourceLayerFingerprint = Objects.requireNonNull(resourceLayerFingerprint, "resourceLayerFingerprint");
		tagEpoch = Objects.requireNonNull(tagEpoch, "tagEpoch");
		contributionRevision = Objects.requireNonNull(contributionRevision, "contributionRevision");
		if (!contributionRevision.dimension().equals(dimension.location())) {
			throw new IllegalArgumentException("Contribution revision belongs to a different dimension");
		}
	}

	public static WorldgenEpoch create(
		ResourceKey<LevelStem> dimension,
		long seed,
		RegistryAccess registries,
		LevelStem selectedStem,
		String settingsIdentity,
		long resourceRevision,
		String resourceLayerFingerprint,
		TagEpoch tagEpoch,
		WorldgenContributionRevision.Snapshot contributionRevision
	) {
		return new WorldgenEpoch(
			UUID.randomUUID(), dimension, seed, registries.freeze(), selectedStem,
			settingsIdentity, resourceRevision, resourceLayerFingerprint, tagEpoch,
			contributionRevision
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

	public boolean inputRevisionStrictlyAdvances(WorldgenEpoch previous) {
		requireSameOwner(previous);
		return this.resourceRevision > previous.resourceRevision
			|| this.tagEpoch.sequence() > previous.tagEpoch.sequence()
			|| this.contributionRevision.strictlyAdvances(previous.contributionRevision);
	}

	public boolean inputRevisionRegressesFrom(WorldgenEpoch previous) {
		requireSameOwner(previous);
		return this.resourceRevision < previous.resourceRevision
			|| this.tagEpoch.sequence() < previous.tagEpoch.sequence()
			|| this.contributionRevision.regressesFrom(previous.contributionRevision);
	}

	private void requireSameOwner(WorldgenEpoch previous) {
		Objects.requireNonNull(previous, "previous");
		if (!this.id.equals(previous.id) || !this.dimension.equals(previous.dimension)) {
			throw new IllegalArgumentException("Worldgen input revisions belong to different owners");
		}
	}

	public WorldgenEpoch withInputs(
		long replacementResourceRevision,
		String replacementResourceLayerFingerprint,
		TagEpoch replacementTags,
		WorldgenContributionRevision.Snapshot replacementContributions
	) {
		Objects.requireNonNull(replacementResourceLayerFingerprint, "replacementResourceLayerFingerprint");
		Objects.requireNonNull(replacementTags, "replacementTags");
		Objects.requireNonNull(replacementContributions, "replacementContributions");
		WorldgenEpoch replacement = new WorldgenEpoch(
			this.id, this.dimension, this.seed, this.registries, this.selectedStem,
			this.settingsIdentity, replacementResourceRevision,
			replacementResourceLayerFingerprint, replacementTags,
			replacementContributions
		);
		if (!replacement.inputRevisionStrictlyAdvances(this)) {
			throw new IllegalArgumentException("Replacement worldgen inputs do not advance this epoch");
		}
		if (replacement.inputRevisionRegressesFrom(this)) {
			throw new IllegalArgumentException("Replacement worldgen inputs regress this epoch");
		}
		return replacement;
	}
}
