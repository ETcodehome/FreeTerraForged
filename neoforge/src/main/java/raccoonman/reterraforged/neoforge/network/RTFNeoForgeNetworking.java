package raccoonman.reterraforged.neoforge.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import raccoonman.reterraforged.client.network.RTFClientPayloadHandler;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;
import raccoonman.reterraforged.network.FlowSettingsSyncPayload;

@EventBusSubscriber(modid = "reterraforged")
public class RTFNeoForgeNetworking {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
		registrar.playToClient(FlowFieldSyncPayload.TYPE, FlowFieldSyncPayload.CODEC, (payload, context) ->
                context.enqueueWork(() ->
                        RTFClientPayloadHandler.handleFlowFieldSync(payload, context.player())
                )
		);
		registrar.playToClient(FlowSettingsSyncPayload.TYPE, FlowSettingsSyncPayload.CODEC, (payload, context) ->
				context.enqueueWork(() ->
						RTFClientPayloadHandler.handleFlowSettingsSync(payload, context.player())
				)
		);
    }
}
