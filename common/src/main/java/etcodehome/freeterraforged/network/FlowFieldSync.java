package etcodehome.freeterraforged.network;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import dev.architectury.networking.NetworkManager;
import etcodehome.freeterraforged.client.network.FTFClientPayloadHandler;
import etcodehome.freeterraforged.world.worldgen.FlowSettingsSnapshot;
import etcodehome.freeterraforged.world.worldgen.IFlowSettingsHolder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public class FlowFieldSync {
	private static final Map<ServerPlayer, FlowSettingsSyncState> PLAYER_SETTINGS_STATE =
		Collections.synchronizedMap(new WeakHashMap<>());

	public static void sendToPlayer(ServerPlayer player, ChunkPos pos, byte[] rawGrid) {
		FlowFieldSyncPayload payload = new FlowFieldSyncPayload(pos, rawGrid);
		NetworkManager.sendToPlayer(player, FlowFieldSyncPayload.ID, payload.toBuffer());
	}

	public static void sendFlowSettingsToPlayer(ServerPlayer player, FlowSettingsSnapshot settings) {
		FlowSettingsSyncPayload payload = new FlowSettingsSyncPayload(settings);
		NetworkManager.sendToPlayer(player, FlowSettingsSyncPayload.ID, payload.toBuffer());
	}

	public static void syncFlowSettingsIfNeeded(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		FlowSettingsSnapshot settings = ((IFlowSettingsHolder) level).freeterraforged$getFlowSettings();
		FlowSettingsSyncState state = PLAYER_SETTINGS_STATE.computeIfAbsent(player, ignored -> new FlowSettingsSyncState());
		if (state.update(level.dimension(), settings)) {
			sendFlowSettingsToPlayer(player, settings);
		}
	}

	@Environment(EnvType.CLIENT)
	public static void registerClientReceiver() {
		NetworkManager.registerReceiver(NetworkManager.Side.S2C, FlowFieldSyncPayload.ID, (buf, context) -> {
			FlowFieldSyncPayload payload = FlowFieldSyncPayload.fromBuffer(buf);
			context.queue(() -> FTFClientPayloadHandler.handleFlowFieldSync(payload, context.getPlayer()));
		});
		NetworkManager.registerReceiver(NetworkManager.Side.S2C, FlowSettingsSyncPayload.ID, (buf, context) -> {
			FlowSettingsSyncPayload payload = FlowSettingsSyncPayload.fromBuffer(buf);
			context.queue(() -> FTFClientPayloadHandler.handleFlowSettingsSync(payload, context.getPlayer()));
		});
	}
}
