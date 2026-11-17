package etcodehome.freeterraforged.server.command;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.network.PreviewLocateRequestPayload;
import etcodehome.freeterraforged.network.PreviewLocationPayload;
import etcodehome.freeterraforged.network.PreviewLocationPayload.Status;
import etcodehome.freeterraforged.network.PreviewTeleportRequestPayload;
import etcodehome.freeterraforged.world.worldgen.search.TerrainSearchTargets;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.levelgen.Heightmap;

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

	public static void teleport(PreviewTeleportRequestPayload request, ServerPlayer player) {
		if (!player.createCommandSourceStack().hasPermission(2)) {
			teleportFailure(
				player,
				"freeterraforged.gui.preset_preview.teleport.no_permission",
				"You do not have permission to teleport from the FreeTerraForged preview."
			);
			return;
		}
		LocateTerrainCommand.TargetLevel target = LocateTerrainCommand.findTargetLevel(
			player.createCommandSourceStack()
		);
		if (target == null || !target.level().dimension().location().toString().equals(request.dimension())) {
			teleportFailure(
				player,
				"freeterraforged.gui.preset_preview.teleport.unavailable",
				"The FreeTerraForged world used by this preview is no longer available."
			);
			return;
		}

		ServerLevel level = target.level();
		BlockPos horizontal = new BlockPos(request.x(), level.getMinBuildHeight(), request.z());
		if (!Level.isInSpawnableBounds(horizontal)
			|| !level.getWorldBorder().isWithinBounds(request.x() + 0.5D, request.z() + 0.5D)) {
			teleportFailure(
				player,
				"freeterraforged.gui.preset_preview.teleport.out_of_bounds",
				"That preview location is outside the playable world."
			);
			return;
		}

		int y = request.exactY()
			? request.y()
			: surfaceTeleportY(level, request.x(), request.z());
		if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
			teleportFailure(
				player,
				"freeterraforged.gui.preset_preview.teleport.out_of_bounds",
				"That preview location is outside the playable world."
			);
			return;
		}
		boolean teleported = player.teleportTo(
			level,
			request.x() + 0.5D,
			y,
			request.z() + 0.5D,
			Set.<RelativeMovement>of(),
			player.getYRot(),
			player.getXRot()
		);
		if (teleported) {
			player.setDeltaMovement(player.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
			player.setOnGround(true);
		} else {
			teleportFailure(
				player,
				"freeterraforged.gui.preset_preview.teleport.failed",
				"The teleport could not be completed."
			);
		}
	}

	private static int surfaceTeleportY(ServerLevel level, int x, int z) {
		int chunkX = net.minecraft.core.SectionPos.blockToSectionCoord(x);
		int chunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(z);
		if (level.hasChunk(chunkX, chunkZ)) {
			int loadedHeight = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (loadedHeight > level.getMinBuildHeight()) {
				return loadedHeight;
			}
		}

		int baseHeight = level.getChunkSource().getGenerator().getBaseHeight(
			x,
			z,
			Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
			level,
			level.getChunkSource().randomState()
		);
		int safeHeight = Math.max(level.getSeaLevel() + 2, baseHeight + 2);
		return Math.min(level.getMaxBuildHeight() - 1, safeHeight);
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
			position.getX(), position.getY(), position.getZ(), request.target()
		)));
	}

	private static void teleportFailure(ServerPlayer player, String key, String fallback) {
		player.sendSystemMessage(
			Component.translatableWithFallback(key, fallback).withStyle(ChatFormatting.RED)
		);
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
