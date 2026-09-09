package etcodehome.freeterraforged.neoforge.network;

import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import etcodehome.freeterraforged.client.network.FTFClientPayloadHandler;

@EventBusSubscriber(modid = "reterraforged")
public class FTFNeoForgeNetworking {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(FlowFieldSyncPayload.TYPE, FlowFieldSyncPayload.CODEC, (payload, context) ->
                context.enqueueWork(() ->
                        FTFClientPayloadHandler.handleFlowFieldSync(payload, context.player())
                )
        );
    }
}