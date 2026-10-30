package etcodehome.freeterraforged.neoforge.network;

import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import etcodehome.freeterraforged.network.ActivePresetSnapshotPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import etcodehome.freeterraforged.client.network.FTFClientPayloadHandler;
import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import etcodehome.freeterraforged.network.FlowSettingsSyncPayload;
import etcodehome.freeterraforged.network.PreviewLocateRequestPayload;
import etcodehome.freeterraforged.network.PreviewLocationPayload;
import etcodehome.freeterraforged.server.command.PreviewLocateHandler;
import net.minecraft.server.level.ServerPlayer;

@EventBusSubscriber(modid = "freeterraforged")
public class FTFNeoForgeNetworking {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
		registrar.playToClient(ActivePresetSnapshotPayload.TYPE, ActivePresetSnapshotPayload.CODEC, (payload, context) ->
			context.enqueueWork(() -> FTFClientPayloadHandler.handleActivePresetSnapshot(payload))
		);
		registrar.playToClient(FlowFieldSyncPayload.TYPE, FlowFieldSyncPayload.CODEC, (payload, context) ->
                context.enqueueWork(() ->
                        FTFClientPayloadHandler.handleFlowFieldSync(payload, context.player())
                )
		);
		registrar.playToClient(FlowSettingsSyncPayload.TYPE, FlowSettingsSyncPayload.CODEC, (payload, context) ->
				context.enqueueWork(() ->
						FTFClientPayloadHandler.handleFlowSettingsSync(payload, context.player())
				)
		);
		registrar.playToClient(PreviewLocationPayload.TYPE, PreviewLocationPayload.CODEC, (payload, context) ->
			context.enqueueWork(() -> FTFClientPayloadHandler.handlePreviewLocation(payload))
		);
		registrar.playToServer(PreviewLocateRequestPayload.TYPE, PreviewLocateRequestPayload.CODEC, (payload, context) ->
			context.enqueueWork(() -> PreviewLocateHandler.handle(payload, (ServerPlayer) context.player()))
		);
    }
}
