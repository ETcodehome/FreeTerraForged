package raccoonman.reterraforged.fabric;

import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.debug.FlowFieldDebugRenderer;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

public class RTFFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        Minecraft mc = Minecraft.getInstance();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            FlowFieldDebugRenderer.render(
                    context.matrixStack(),
                    context.camera(),
                    mc.renderBuffers().bufferSource()
            );
        });

        // Use Fabric's ClientPlayNetworking to attach the receiver to the payload registered in RTFCommon
        ClientPlayNetworking.registerGlobalReceiver(
                FlowFieldSyncPayload.TYPE,
                (payload, context) -> {
                    context.client().execute(() -> {
                        ClientLevel clientLevel = context.client().level;
                        if (clientLevel != null) {
                            ChunkAccess chunk = clientLevel.getChunk(payload.pos().x, payload.pos().z, ChunkStatus.FULL, false);
                            if (chunk instanceof IFlowFieldHolder holder) {
                                holder.reterraforged$getFlowField().loadRawGrid(payload.rawGrid());
                            }
                        }
                    });
                }
        );
    }
}