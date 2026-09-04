package raccoonman.reterraforged.world.worldgen.runtime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;

/** Owner-preserving invalidation for reloadable inputs such as registry tag bindings. */
public final class WorldgenLifecycle {
	private WorldgenLifecycle() {
	}

	public static void tagsReloaded(MinecraftServer server, long resourceRevision) {
		String fingerprint = WorldgenFingerprints.tags(server.registryAccess());
		String resourceLayers = WorldgenFingerprints.resourceLayers(server, resourceRevision);
		int refreshed = 0;
		int rejected = 0;
		for (ServerLevel level : server.getAllLevels()) {
			if (!(level.getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator generator)) {
				continue;
			}
			WorldgenEpoch epoch = generator.epoch().orElse(null);
			if (epoch == null) {
				continue;
			}
			TagEpoch tags = epoch.tagEpoch().fingerprint().equals(fingerprint)
				? epoch.tagEpoch()
				: epoch.tagEpoch().next(fingerprint);
			WorldgenContributionRevision.Snapshot contributions = WorldgenContributionRevision.snapshot(
				epoch.dimension(), generator.acquireProviderCatalog()
			);
			try {
				if (refresh(
					level, generator, epoch, resourceRevision, resourceLayers, tags, contributions,
					"resource reload"
				)) {
					refreshed++;
				}
			} catch (RuntimeException | LinkageError failure) {
				rejected++;
				RTFCommon.LOGGER.error(
					"Rejected FTF worldgen input epoch for dimension={} resource_revision={}; the previous immutable plan remains active",
					level.dimension().location(), resourceRevision, failure
				);
			}
		}
		RTFCommon.LOGGER.info(
			"Processed FTF worldgen input epochs: tags={} refreshed={} rejected={}",
			fingerprint, refreshed, rejected
		);
	}

	private static boolean refresh(
		ServerLevel level,
		TerraForgedChunkGenerator generator,
		WorldgenEpoch epoch,
		long resourceRevision,
		String resourceLayers,
		TagEpoch tags,
		WorldgenContributionRevision.Snapshot contributions,
		String reason
	) {
		if (!contributions.failures().isEmpty()) {
			IllegalStateException failure = new IllegalStateException(
				"Contribution revision acquisition failed: " + contributions.failures()
			);
			generator.rejectInputSnapshot(resourceRevision, resourceLayers, tags, contributions, failure);
			throw failure;
		}
		if (contributions.regressesFrom(epoch.contributionRevision())) {
			IllegalStateException failure = new IllegalStateException(
				"Contribution revision regressed from " + epoch.contributionRevision().revisions()
					+ " to " + contributions.revisions()
			);
			generator.rejectInputSnapshot(resourceRevision, resourceLayers, tags, contributions, failure);
			throw failure;
		}
		if (resourceRevision < epoch.resourceRevision()) {
			IllegalStateException failure = new IllegalStateException(
				"Resource revision regressed from " + epoch.resourceRevision() + " to " + resourceRevision
			);
			generator.rejectInputSnapshot(resourceRevision, resourceLayers, tags, contributions, failure);
			throw failure;
		}
		boolean resourcesChanged = resourceRevision > epoch.resourceRevision();
		boolean tagsAdvanced = tags.sequence() > epoch.tagEpoch().sequence();
		boolean contributionsAdvanced = contributions.strictlyAdvances(epoch.contributionRevision());
		if (!resourcesChanged && !tagsAdvanced && !contributionsAdvanced) {
			return false;
		}
		if (!((Object) level.getChunkSource().randomState() instanceof RTFRandomState randomState)) {
			throw new IllegalStateException("FTF generator has no owned random-state contract");
		}
		try {
			generator.refreshInputs(resourceRevision, resourceLayers, tags, contributions, randomState);
			return true;
		} catch (Exception failure) {
			throw new IllegalStateException(
				"Failed to recompile FTF worldgen plan after " + reason + " for "
					+ level.dimension().location(),
				failure
			);
		}
	}
}
