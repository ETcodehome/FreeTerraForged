package etcodehome.freeterraforged.fabric.network;

import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import etcodehome.freeterraforged.network.ActivePresetSnapshotPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import etcodehome.freeterraforged.network.FlowSettingsSyncPayload;
import etcodehome.freeterraforged.network.PreviewLocateRequestPayload;
import etcodehome.freeterraforged.network.PreviewLocationPayload;
import etcodehome.freeterraforged.server.command.PreviewLocateHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class FTFFabricNetworking {

    public static void init() {
		PayloadTypeRegistry.playS2C().register(ActivePresetSnapshotPayload.TYPE, ActivePresetSnapshotPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(FlowFieldSyncPayload.TYPE, FlowFieldSyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(FlowSettingsSyncPayload.TYPE, FlowSettingsSyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(PreviewLocationPayload.TYPE, PreviewLocationPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(PreviewLocateRequestPayload.TYPE, PreviewLocateRequestPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PreviewLocateRequestPayload.TYPE, (payload, context) ->
			context.server().execute(() -> PreviewLocateHandler.handle(payload, context.player()))
		);
    }
}
