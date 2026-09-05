package raccoonman.reterraforged.fabric.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;
import raccoonman.reterraforged.network.FlowSettingsSyncPayload;

public class RTFFabricNetworking {

    public static void init() {
		PayloadTypeRegistry.playS2C().register(FlowFieldSyncPayload.TYPE, FlowFieldSyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(FlowSettingsSyncPayload.TYPE, FlowSettingsSyncPayload.CODEC);
    }
}
