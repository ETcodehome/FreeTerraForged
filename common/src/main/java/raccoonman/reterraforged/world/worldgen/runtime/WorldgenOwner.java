package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;

/** Exact lifetime and selected-root authority for a compiled plan. */
public interface WorldgenOwner {
	UUID id();

	WorldgenOwnerType type();

	long seed();

	RegistryAccess.Frozen registries();

	ResourceKey<LevelStem> dimension();

	/**
	 * The final lookup graph selected for this owner. Server epochs use their frozen registries;
	 * preview requests may supply a request-local patched lookup without pretending it is a mutable
	 * or independently owned registry access.
	 */
	HolderLookup.Provider lookups();

	LevelStem selectedStem();

	String settingsIdentity();

	long resourceRevision();

	String resourceLayerFingerprint();

	TagEpoch tagEpoch();

	WorldgenContributionRevision.Snapshot contributionRevision();
}
