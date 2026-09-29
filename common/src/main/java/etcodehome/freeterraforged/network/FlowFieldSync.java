package etcodehome.freeterraforged.network;

import dev.architectury.networking.NetworkManager;
import etcodehome.freeterraforged.client.network.FTFClientPayloadHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public class FlowFieldSync {

    public static void sendToPlayer(ServerPlayer player, ChunkPos pos, byte[] rawGrid) {
        FlowFieldSyncPayload payload = new FlowFieldSyncPayload(pos, rawGrid);
        NetworkManager.sendToPlayer(player, FlowFieldSyncPayload.ID, payload.toBuffer());
    }

    @Environment(EnvType.CLIENT)
    public static void registerClientReceiver() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, FlowFieldSyncPayload.ID, (buf, context) -> {
            FlowFieldSyncPayload payload = FlowFieldSyncPayload.fromBuffer(buf);
            context.queue(() -> FTFClientPayloadHandler.handleFlowFieldSync(payload, context.getPlayer()));
        });
    }
}
