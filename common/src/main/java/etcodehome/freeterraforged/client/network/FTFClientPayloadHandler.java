package etcodehome.freeterraforged.client.network;

import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public class FTFClientPayloadHandler {

    public static void handleFlowFieldSync(FlowFieldSyncPayload payload, Player player) {
        if (player != null && player.level() instanceof ClientLevel clientLevel) {
            ChunkAccess chunk = clientLevel.getChunk(payload.pos().x, payload.pos().z, ChunkStatus.FULL, false);
            if (chunk instanceof IFlowFieldHolder holder) {
                holder.reterraforged$getFlowField().loadRawGrid(payload.rawGrid());
            }
        }
    }
}