package raccoonman.reterraforged.fabric.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import raccoonman.reterraforged.client.network.RTFClientPayloadHandler;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;

public class RTFFabricClientNetworking {

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(FlowFieldSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        RTFClientPayloadHandler.handleFlowFieldSync(payload, context.player())
                )
        );
    }
}