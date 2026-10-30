package etcodehome.freeterraforged.server.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.network.PreviewLocateRequestPayload;
import etcodehome.freeterraforged.network.PreviewLocationPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Handles locate requests made from the standalone preview screen. */
public final class PreviewLocateHandler {
	private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

	private PreviewLocateHandler() {
	}

	public static void handle(PreviewLocateRequestPayload request, ServerPlayer player) {
		if (!player.createCommandSourceStack().hasPermission(2)) {
			send(player, request, false, BlockPos.ZERO, "You do not have permission to use the FTF preview");
			return;
		}
		if (request.targetType() == PreviewLocateRequestPayload.TargetType.CANCEL) {
			Pending pending = PENDING.remove(player.getUUID());
			if (pending != null) {
				pending.future().cancel(true);
			}
			return;
		}
		LocateTerrainCommand.TargetLevel target = LocateTerrainCommand.findTargetLevel(
			player.createCommandSourceStack()
		);
		if (target == null || !target.level().dimension().location().toString().equals(request.dimension())) {
			send(player, request, false, BlockPos.ZERO, "The preview's FTF dimension is no longer available");
			return;
		}
		String value = request.target().trim();
		if (value.isEmpty()) {
			send(player, request, false, BlockPos.ZERO, "A locate target is required");
			return;
		}
		if (request.targetType() == PreviewLocateRequestPayload.TargetType.TERRAIN) {
			locateTerrain(request, player, target, value);
		} else {
			locateBiome(request, player, target.level(), value);
		}
	}

	private static void locateTerrain(
		PreviewLocateRequestPayload request,
		ServerPlayer player,
		LocateTerrainCommand.TargetLevel target,
		String value
	) {
		BlockPos origin = origin(player, target.level());
		CompletableFuture<BlockPos> future = LocateTerrainCommand.findForPreview(
			target.context(), origin, value
		);
		startSearch(request, player, value, "terrain", future);
	}

	private static void locateBiome(
		PreviewLocateRequestPayload request,
		ServerPlayer player,
		ServerLevel level,
		String value
	) {
		try {
			CompletableFuture<BlockPos> future = LocateBiomeCommand.findForPreview(
				level, origin(player, level), value
			);
			startSearch(request, player, value, "biome", future);
		} catch (RuntimeException failure) {
			FTFCommon.LOGGER.debug("Preview biome locate failed for {}", value, failure);
			send(player, request, false, BlockPos.ZERO,
				failure.getMessage() == null ? "Invalid biome: " + value : failure.getMessage());
		}
	}

	private static void startSearch(
		PreviewLocateRequestPayload request,
		ServerPlayer player,
		String value,
		String kind,
		CompletableFuture<BlockPos> future
	) {
		Pending pending = new Pending(future);
		Pending previous = PENDING.put(player.getUUID(), pending);
		if (previous != null) {
			previous.future().cancel(true);
		}
		MinecraftServer server = player.getServer();
		future.whenComplete((position, failure) -> {
			if (!server.isRunning()) {
				return;
			}
			try {
				server.execute(() -> {
					if (!PENDING.remove(player.getUUID(), pending)) {
						return;
					}
					ServerPlayer current = server.getPlayerList().getPlayer(player.getUUID());
					if (current == null) {
						return;
					}
					if (failure != null) {
						String message = failure.getCause() != null && failure.getCause().getMessage() != null
							? failure.getCause().getMessage()
							: failure.getMessage();
						send(current, request, false, BlockPos.ZERO,
							message == null
								? Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + " search failed"
								: message);
					} else if (position == null) {
						send(current, request, false, BlockPos.ZERO,
								"Could not find " + kind + " " + value + " before the search ended");
					} else {
						send(current, request, true, position, "Found " + kind + " " + value);
					}
				});
			} catch (RejectedExecutionException ignored) {
				// Server stopped between completion and dispatch.
			}
		});
	}

	private static BlockPos origin(ServerPlayer player, ServerLevel level) {
		return player.serverLevel() == level ? player.blockPosition() : level.getSharedSpawnPos();
	}

	private static void send(
		ServerPlayer player,
		PreviewLocateRequestPayload request,
		boolean found,
		BlockPos position,
		String message
	) {
		player.connection.send(new ClientboundCustomPayloadPacket(new PreviewLocationPayload(
			request.requestId(), request.dimension(), found,
			position.getX(), position.getZ(), message
		)));
	}

	private record Pending(CompletableFuture<BlockPos> future) {
	}
}
