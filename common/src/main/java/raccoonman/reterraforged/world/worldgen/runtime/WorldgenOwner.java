package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;

public interface WorldgenOwner {
	UUID id();

	WorldgenOwnerType type();

	long seed();

	RegistryAccess.Frozen registries();

	ResourceKey<LevelStem> dimension();

	HolderLookup.Provider lookups();

	LevelStem selectedStem();

	String settingsIdentity();

	long resourceRevision();

	String resourceLayerFingerprint();

	TagEpoch tagEpoch();

	WorldgenContributionRevision.Snapshot contributionRevision();
}
