package raccoonman.reterraforged.world.worldgen.feature.ore;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.server.RTFMinecraftServer;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalFrame;

/** Publishes one server-owned immutable plan for the current registry epoch. */
public final class DynamicOreLifecycle {
	private static boolean bootstrapped;

	private DynamicOreLifecycle() {
	}

	public static synchronized void bootstrap() {
		if (bootstrapped) {
			return;
		}
		bootstrapped = true;
		LifecycleEvent.SERVER_LEVEL_LOAD.register(DynamicOreLifecycle::onLevelLoad);
		LifecycleEvent.SERVER_STARTED.register(DynamicOreLifecycle::onServerStarted);
	}

	private static void onLevelLoad(ServerLevel level) {
		if (Level.OVERWORLD.equals(level.dimension())) {
			refresh(level);
		}
	}

	private static void onServerStarted(MinecraftServer server) {
		if (server instanceof RTFMinecraftServer owner
			&& owner.getDynamicOrePlan().occurrences().isEmpty()) {
			refresh(server);
		}
	}

	public static void refresh(MinecraftServer server) {
		if (!(server instanceof RTFMinecraftServer owner)) {
			return;
		}
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if (overworld == null) {
			owner.publishDynamicOrePlan(DynamicOrePlan.empty(DynamicOrePlanner.schemaFingerprint()));
			return;
		}
		refresh(overworld);
	}

	private static void refresh(ServerLevel overworld) {
		MinecraftServer server = overworld.getServer();
		if (!(server instanceof RTFMinecraftServer owner)) {
			return;
		}
		if (!((Object)overworld.getChunkSource().randomState() instanceof RTFRandomState randomState)
			|| randomState.generatorContext() == null) {
			owner.publishDynamicOrePlan(DynamicOrePlan.empty(DynamicOrePlanner.schemaFingerprint()));
			return;
		}

		ChunkGenerator generator = overworld.getChunkSource().getGenerator();
		DynamicOrePlan plan = new DynamicOrePlanner().build(
			server.registryAccess(),
			generator,
			generator.getBiomeSource().possibleBiomes(),
			new VerticalFrame(
				overworld.getMinBuildHeight(),
				overworld.getMaxBuildHeight() - 1,
				generator.getSeaLevel()
			)
		);
		owner.publishDynamicOrePlan(plan);
		RTFCommon.LOGGER.info("Dynamic ore contract inventory: {}", plan.summary());
		Map<String, Long> failures = plan.occurrences().stream()
			.filter(occurrence -> occurrence.inspection().status() == DynamicOrePlan.InspectionStatus.FAILED)
			.collect(Collectors.groupingBy(
				occurrence -> occurrence.inspection().phase()
					+ " | " + occurrence.inspection().failureType().orElse("<unknown>")
					+ " | " + occurrence.inspection().failureMessage().orElse("<no message>"),
				TreeMap::new,
				Collectors.counting()
			));
		failures.forEach((failure, count) -> RTFCommon.LOGGER.warn(
			"Dynamic ore contract inspection failure ({} occurrence(s)): {}", count, failure
		));
		if (RTFCommon.LOGGER.isDebugEnabled()) {
			for (DynamicOrePlan.Occurrence occurrence : plan.occurrences()) {
				RTFCommon.LOGGER.debug("Dynamic ore contract occurrence: {}", occurrence);
			}
		}
	}
}
