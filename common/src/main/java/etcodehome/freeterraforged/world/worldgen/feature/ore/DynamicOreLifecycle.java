package etcodehome.freeterraforged.world.worldgen.feature.ore;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.server.FTFMinecraftServer;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalFrame;

public final class DynamicOreLifecycle {

	private DynamicOreLifecycle() {
	}

	public static void onLevelLoad(ServerLevel level) {
		if (Level.OVERWORLD.equals(level.dimension())) {
			refresh(level);
		}
	}

	public static void onServerStarted(MinecraftServer server) {
		if (server instanceof FTFMinecraftServer owner
				&& owner.getDynamicOrePlan().verticalFrame().isEmpty()) {
			refresh(server);
		}
	}

	public static void refresh(MinecraftServer server) {
		if (!(server instanceof FTFMinecraftServer owner)) {
			return;
		}
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if (overworld == null) {
			owner.publishDynamicOrePlan(DynamicOrePlan.empty());
			return;
		}
		refresh(overworld);
	}

	private static void refresh(ServerLevel overworld) {
		MinecraftServer server = overworld.getServer();
		if (!(server instanceof FTFMinecraftServer owner)) {
			return;
		}
		if (!((Object)overworld.getChunkSource().randomState() instanceof FTFRandomState randomState)
				|| randomState.generatorContext() == null) {
			owner.publishDynamicOrePlan(DynamicOrePlan.empty());
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
		FTFCommon.LOGGER.info("Dynamic ore contract inventory: {}", plan.summary());
		plan.failures().forEach(failure -> FTFCommon.LOGGER.warn(
				"Dynamic ore contract inspection failure: {}", failure
		));
	}
}