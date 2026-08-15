package raccoonman.reterraforged.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.fabricmc.api.EnvType;
import raccoonman.reterraforged.client.network.RTFClientPayloadHandler;

public class RTFNetworking {

    public static void init() {
        if (Platform.isFabric()) {
            // Fabric Server requires registering the S2C payload type to allow sending packets.
            if (Platform.getEnv() == EnvType.SERVER) {
                NetworkManager.registerS2CPayloadType(FlowFieldSyncPayload.TYPE, FlowFieldSyncPayload.CODEC);
            } else {
                // Fabric S2C receiver registration MUST be guarded so it only executes on physical clients
                EnvExecutor.runInEnv(Env.CLIENT, () -> () -> {
                    NetworkManager.registerReceiver(
                            NetworkManager.Side.S2C,
                            FlowFieldSyncPayload.TYPE,
                            FlowFieldSyncPayload.CODEC,
                            RTFClientPayloadHandler::handleFlowFieldSync
                    );
                });
            }
        } else {
            // NeoForge registers S2C payload type + handler together via PayloadRegistrar on both server and client
            NetworkManager.registerReceiver(
                    NetworkManager.Side.S2C,
                    FlowFieldSyncPayload.TYPE,
                    FlowFieldSyncPayload.CODEC,
                    (payload, context) -> EnvExecutor.runInEnv(
                            Env.CLIENT,
                            () -> () -> RTFClientPayloadHandler.handleFlowFieldSync(payload, context)
                    )
            );
        }
    }
}