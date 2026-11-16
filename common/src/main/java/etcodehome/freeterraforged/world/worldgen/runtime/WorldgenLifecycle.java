package etcodehome.freeterraforged.world.worldgen.runtime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;

public final class WorldgenLifecycle {
	private WorldgenLifecycle() {
	}

	public static void tagsReloaded(MinecraftServer server, long resourceRevision) {
		String fingerprint = WorldgenFingerprints.tags(server.registryAccess());
		String resourceLayers = WorldgenFingerprints.resourceLayers(server, resourceRevision);
		BiomeReplacementConfigSnapshot biomeConfig;
		try {
			biomeConfig = BiomeReplacementConfigStore.capture();
		} catch (RuntimeException failure) {
			FTFCommon.LOGGER.error(
				"Could not capture the FTF biome replacement config during resource reload; previous immutable plans remain active",
				failure
			);
			return;
		}
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
					level, generator, epoch, resourceRevision, resourceLayers, tags, biomeConfig,
					contributions, "resource reload"
				)) {
					refreshed++;
				}
			} catch (RuntimeException | LinkageError failure) {
				rejected++;
				FTFCommon.LOGGER.error(
					"Rejected FTF worldgen input epoch for dimension={} resource_revision={} biome_replacement_revision={}; the previous immutable plan remains active",
					level.dimension().location(), resourceRevision, biomeConfig.revision(), failure
				);
			}
		}
		FTFCommon.LOGGER.info(
			"Processed FTF worldgen input epochs: tags={} biome_replacement_revision={} refreshed={} rejected={}",
			fingerprint, biomeConfig.revision(), refreshed, rejected
		);
	}

	private static boolean refresh(
		ServerLevel level,
		TerraForgedChunkGenerator generator,
		WorldgenEpoch epoch,
		long resourceRevision,
		String resourceLayers,
		TagEpoch tags,
		BiomeReplacementConfigSnapshot biomeConfig,
		WorldgenContributionRevision.Snapshot contributions,
		String reason
	) {
		if (!contributions.failures().isEmpty()) {
			IllegalStateException failure = new IllegalStateException(
				"Contribution revision acquisition failed: " + contributions.failures()
			);
			generator.rejectInputSnapshot(
				resourceRevision, resourceLayers, tags, biomeConfig, contributions, failure
			);
			throw failure;
		}
		if (contributions.regressesFrom(epoch.contributionRevision())) {
			IllegalStateException failure = new IllegalStateException(
				"Contribution revision regressed from " + epoch.contributionRevision().revisions()
					+ " to " + contributions.revisions()
			);
			generator.rejectInputSnapshot(
				resourceRevision, resourceLayers, tags, biomeConfig, contributions, failure
			);
			throw failure;
		}
		if (resourceRevision < epoch.resourceRevision()) {
			IllegalStateException failure = new IllegalStateException(
				"Resource revision regressed from " + epoch.resourceRevision() + " to " + resourceRevision
			);
			generator.rejectInputSnapshot(
				resourceRevision, resourceLayers, tags, biomeConfig, contributions, failure
			);
			throw failure;
		}
		if (biomeConfig.revision() < epoch.biomeReplacementConfig().revision()) {
			IllegalStateException failure = new IllegalStateException(
				"Biome replacement revision regressed from "
					+ epoch.biomeReplacementConfig().revision() + " to " + biomeConfig.revision()
			);
			generator.rejectInputSnapshot(
				resourceRevision, resourceLayers, tags, biomeConfig, contributions, failure
			);
			throw failure;
		}
		boolean resourcesChanged = resourceRevision > epoch.resourceRevision();
		boolean tagsAdvanced = tags.sequence() > epoch.tagEpoch().sequence();
		boolean contributionsAdvanced = contributions.strictlyAdvances(epoch.contributionRevision());
		boolean biomeConfigAdvanced = biomeConfig.revision() > epoch.biomeReplacementConfig().revision();
		if (!resourcesChanged && !tagsAdvanced && !contributionsAdvanced && !biomeConfigAdvanced) {
			return false;
		}
		if (!((Object) level.getChunkSource().randomState() instanceof FTFRandomState randomState)) {
			throw new IllegalStateException("FTF generator has no owned random-state contract");
		}
		try {
			generator.refreshInputs(
				resourceRevision, resourceLayers, tags, biomeConfig, contributions, randomState
			);
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
