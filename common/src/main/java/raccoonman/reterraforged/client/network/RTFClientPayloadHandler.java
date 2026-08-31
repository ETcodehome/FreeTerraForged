package raccoonman.reterraforged.client.network;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;
import raccoonman.reterraforged.network.FlowSettingsSyncPayload;
import raccoonman.reterraforged.world.worldgen.IFlowSettingsHolder;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

public class RTFClientPayloadHandler {

    public static void handleFlowFieldSync(FlowFieldSyncPayload payload, Player player) {
        if (player != null && player.level() instanceof ClientLevel clientLevel) {
            ChunkAccess chunk = clientLevel.getChunk(payload.pos().x, payload.pos().z, ChunkStatus.FULL, false);
            if (chunk instanceof IFlowFieldHolder holder) {
                holder.reterraforged$getFlowField().loadRawGrid(payload.rawGrid());
			}
		}
	}

	public static void handleFlowSettingsSync(FlowSettingsSyncPayload payload, Player player) {
		if (player != null && player.level() instanceof ClientLevel clientLevel) {
			((IFlowSettingsHolder) clientLevel).reterraforged$setFlowSettings(payload.settings());
		}
	}
}
