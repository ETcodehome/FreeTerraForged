package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;

/** Request-owned preview authority. It is never interchangeable with a server epoch. */
public record PreviewRequest(
	UUID id,
	ResourceKey<LevelStem> dimension,
	long seed,
	RegistryAccess.Frozen registries,
	HolderLookup.Provider lookups,
	LevelStem selectedStem,
	String settingsIdentity,
	long resourceRevision,
	String resourceLayerFingerprint,
	TagEpoch tagEpoch,
	WorldgenContributionRevision.Snapshot contributionRevision
) implements WorldgenOwner {
	public PreviewRequest {
		id = Objects.requireNonNull(id, "id");
		dimension = Objects.requireNonNull(dimension, "dimension");
		registries = Objects.requireNonNull(registries, "registries");
		lookups = Objects.requireNonNull(lookups, "lookups");
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

	public static PreviewRequest create(
		ResourceKey<LevelStem> dimension,
		long seed,
		RegistryAccess.Frozen registries,
		HolderLookup.Provider lookups,
		LevelStem selectedStem,
		String settingsIdentity,
		String resourceLayerFingerprint,
		TagEpoch tagEpoch,
		WorldgenContributionRevision.Snapshot contributionRevision
	) {
		return new PreviewRequest(
			UUID.randomUUID(), dimension, seed, registries, lookups, selectedStem,
			settingsIdentity, 0L, resourceLayerFingerprint, tagEpoch, contributionRevision
		);
	}

	@Override
	public WorldgenOwnerType type() {
		return WorldgenOwnerType.PREVIEW_REQUEST;
	}

}
