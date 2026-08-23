package raccoonman.reterraforged.fabric.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;

public class RTFFabricNetworking {

    public static void init() {
        PayloadTypeRegistry.playS2C().register(FlowFieldSyncPayload.TYPE, FlowFieldSyncPayload.CODEC);
    }
}