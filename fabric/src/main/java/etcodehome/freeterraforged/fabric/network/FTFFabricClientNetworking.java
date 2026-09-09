package etcodehome.freeterraforged.fabric.network;

import etcodehome.freeterraforged.client.network.FTFClientPayloadHandler;
import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class FTFFabricClientNetworking {

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(FlowFieldSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        FTFClientPayloadHandler.handleFlowFieldSync(payload, context.player())
                )
        );
    }
}