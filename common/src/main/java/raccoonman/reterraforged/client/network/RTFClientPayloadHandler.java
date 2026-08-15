package raccoonman.reterraforged.client.network;

import dev.architectury.networking.NetworkManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

public class RTFClientPayloadHandler {

    public static void handleFlowFieldSync(FlowFieldSyncPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            Player player = context.getPlayer();
            if (player != null && player.level() instanceof ClientLevel clientLevel) {
                ChunkAccess chunk = clientLevel.getChunk(payload.pos().x, payload.pos().z, ChunkStatus.FULL, false);
                if (chunk instanceof IFlowFieldHolder holder) {
                    holder.reterraforged$getFlowField().loadRawGrid(payload.rawGrid());
                }
            }
        });
    }
}