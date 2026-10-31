package etcodehome.freeterraforged.server.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.network.PreviewLocateRequestPayload;
import etcodehome.freeterraforged.network.PreviewLocationPayload;
import etcodehome.freeterraforged.network.PreviewLocationPayload.Status;
import etcodehome.freeterraforged.world.worldgen.search.TerrainSearchTargets;
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
			send(player, request, Status.NO_PERMISSION, BlockPos.ZERO);
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
			send(player, request, Status.UNAVAILABLE, BlockPos.ZERO);
			return;
		}
		String value = request.target().trim();
		if (value.isEmpty()) {
			send(player, request, Status.TARGET_REQUIRED, BlockPos.ZERO);
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
		if (TerrainSearchTargets.get(value) == null) {
			send(player, request, Status.INVALID_TARGET, BlockPos.ZERO);
			return;
		}
		BlockPos origin = origin(player, target.level());
		CompletableFuture<LocatePreviewResult> future = LocateTerrainCommand.findForPreview(
			target.context(), origin, value
		);
		startSearch(request, player, future);
	}

	private static void locateBiome(
		PreviewLocateRequestPayload request,
		ServerPlayer player,
		ServerLevel level,
		String value
	) {
		try {
			CompletableFuture<LocatePreviewResult> future = LocateBiomeCommand.findForPreview(
				level, origin(player, level), value
			);
			startSearch(request, player, future);
		} catch (RuntimeException failure) {
			FTFCommon.LOGGER.debug("Preview biome locate failed for {}", value, failure);
			send(player, request, Status.INVALID_TARGET, BlockPos.ZERO);
		}
	}

	private static void startSearch(
		PreviewLocateRequestPayload request,
		ServerPlayer player,
		CompletableFuture<LocatePreviewResult> future
	) {
		Pending pending = new Pending(future);
		Pending previous = PENDING.put(player.getUUID(), pending);
		if (previous != null) {
			previous.future().cancel(true);
		}
		MinecraftServer server = player.getServer();
		future.whenComplete((result, failure) -> {
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
						FTFCommon.LOGGER.error(
							"Preview {} locate failed for {}",
							request.targetType().name().toLowerCase(java.util.Locale.ROOT),
							request.target(),
							failure
						);
						send(current, request, Status.FAILED, BlockPos.ZERO);
					} else if (result == null) {
						FTFCommon.LOGGER.error(
							"Preview {} locate returned no result for {}",
							request.targetType().name().toLowerCase(java.util.Locale.ROOT),
							request.target()
						);
						send(current, request, Status.FAILED, BlockPos.ZERO);
					} else {
						send(current, request, status(result.status()), result.position());
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
		Status status,
		BlockPos position
	) {
		player.connection.send(new ClientboundCustomPayloadPacket(new PreviewLocationPayload(
			request.requestId(), request.dimension(), request.targetType(), status,
			position.getX(), position.getZ(), request.target()
		)));
	}

	private static Status status(LocatePreviewResult.Status status) {
		return switch (status) {
			case FOUND -> Status.FOUND;
			case NOT_FOUND -> Status.NOT_FOUND;
			case TIMED_OUT -> Status.TIMED_OUT;
			case IMPOSSIBLE -> Status.IMPOSSIBLE;
			case UNAVAILABLE -> Status.UNAVAILABLE;
		};
	}

	private record Pending(CompletableFuture<?> future) {
	}
}
