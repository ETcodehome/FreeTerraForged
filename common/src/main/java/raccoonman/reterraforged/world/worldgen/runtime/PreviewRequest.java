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
	String resourceLayerFingerprint,
	TagEpoch tagEpoch,
	long contributionSequence
) implements WorldgenOwner {
	public PreviewRequest {
		id = Objects.requireNonNull(id, "id");
		dimension = Objects.requireNonNull(dimension, "dimension");
		registries = Objects.requireNonNull(registries, "registries").freeze();
		lookups = Objects.requireNonNull(lookups, "lookups");
		selectedStem = Objects.requireNonNull(selectedStem, "selectedStem");
		settingsIdentity = Objects.requireNonNull(settingsIdentity, "settingsIdentity");
		resourceLayerFingerprint = Objects.requireNonNull(resourceLayerFingerprint, "resourceLayerFingerprint");
		tagEpoch = Objects.requireNonNull(tagEpoch, "tagEpoch");
		if (contributionSequence < 0L) {
			throw new IllegalArgumentException("Contribution sequence must be non-negative");
		}
	}

	public static PreviewRequest create(
		ResourceKey<LevelStem> dimension,
		long seed,
		RegistryAccess registries,
		HolderLookup.Provider lookups,
		LevelStem selectedStem,
		String settingsIdentity,
		String resourceLayerFingerprint,
		TagEpoch tagEpoch
	) {
		return new PreviewRequest(
			UUID.randomUUID(), dimension, seed, registries.freeze(), lookups, selectedStem,
			settingsIdentity, resourceLayerFingerprint, tagEpoch,
			WorldgenContributionRevision.current()
		);
	}

	@Override
	public WorldgenOwnerType type() {
		return WorldgenOwnerType.PREVIEW_REQUEST;
	}

}
