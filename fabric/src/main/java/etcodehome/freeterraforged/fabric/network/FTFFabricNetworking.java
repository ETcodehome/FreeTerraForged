package etcodehome.freeterraforged.fabric.network;

import etcodehome.freeterraforged.network.ActivePresetSnapshotPayload;
import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import etcodehome.freeterraforged.network.FlowSettingsSyncPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class FTFFabricNetworking {

    public static void init() {
		PayloadTypeRegistry.playS2C().register(ActivePresetSnapshotPayload.TYPE, ActivePresetSnapshotPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(FlowFieldSyncPayload.TYPE, FlowFieldSyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(FlowSettingsSyncPayload.TYPE, FlowSettingsSyncPayload.CODEC);
    }
}
