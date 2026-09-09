package etcodehome.freeterraforged.fabric.network;

import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class FTFFabricNetworking {

    public static void init() {
        PayloadTypeRegistry.playS2C().register(FlowFieldSyncPayload.TYPE, FlowFieldSyncPayload.CODEC);
    }
}