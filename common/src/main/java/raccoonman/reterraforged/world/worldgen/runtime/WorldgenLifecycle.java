package raccoonman.reterraforged.world.worldgen.runtime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;

/** Owner-preserving invalidation for reloadable inputs such as registry tag bindings. */
public final class WorldgenLifecycle {
	private WorldgenLifecycle() {
	}

	public static void tagsReloaded(MinecraftServer server) {
		String fingerprint = WorldgenFingerprints.tags(server.registryAccess());
		for (ServerLevel level : server.getAllLevels()) {
			if (!(level.getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator generator)) {
				continue;
			}
			generator.epoch().ifPresent(epoch -> {
				if (epoch.tagEpoch().fingerprint().equals(fingerprint)) {
					return;
				}
				try {
					generator.refreshTags(
						epoch.tagEpoch().next(fingerprint),
						(RTFRandomState) (Object) level.getChunkSource().randomState()
					);
				} catch (Exception failure) {
					throw new IllegalStateException(
						"Failed to recompile FTF worldgen plan after tag reload for " + level.dimension().location(),
						failure
					);
				}
			});
		}
		RTFCommon.LOGGER.info("Refreshed FTF worldgen tag epochs: {}", fingerprint);
	}

	public static void contributionsFinalized(ServerLevel level) {
		if (!(level.getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator generator)
			|| !((Object) level.getChunkSource().randomState() instanceof RTFRandomState randomState)) {
			return;
		}
		try {
			generator.refreshContributions(randomState);
		} catch (Exception failure) {
			throw new IllegalStateException(
				"Failed to recompile FTF worldgen plan after contribution finalization for "
					+ level.dimension().location(),
				failure
			);
		}
	}
}
