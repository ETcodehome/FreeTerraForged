package raccoonman.reterraforged.mixin;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

@Mixin(PlayerChunkSender.class)
public class MixinPlayerChunkSender {

    @Inject(
            method = "sendChunk",
            at = @At("TAIL")
    )
    private static void onSendChunk(ServerGamePacketListenerImpl listener, ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
        if (chunk instanceof IFlowFieldHolder holder) {
            ChunkFlowField flowField = holder.reterraforged$getFlowField();

            if (flowField != null && flowField.hasRivers()) {
                listener.send(new ClientboundCustomPayloadPacket(
                        new FlowFieldSyncPayload(chunk.getPos(), flowField.getRawGrid(), flowField.getSettings())
                ));
            }
        }
    }
}
