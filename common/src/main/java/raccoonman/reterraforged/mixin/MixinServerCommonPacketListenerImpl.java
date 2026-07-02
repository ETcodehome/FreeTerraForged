package raccoonman.reterraforged.mixin;

import dev.architectury.networking.NetworkManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MixinServerCommonPacketListenerImpl {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("TAIL"))
    private void syncRiverFlowOnChunkSend(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket)) {
            return;
        }

        if ((Object) this instanceof ServerGamePacketListenerImpl gamePacketListener) {
            ServerPlayer player = gamePacketListener.player;
            int chunkX = chunkPacket.getX();
            int chunkZ = chunkPacket.getZ();
            ServerLevel level = player.serverLevel();

            // Using ChunkStatus.FULL guarantees we see the data without forcing a reload cascade
            ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);

            if (chunk != null) {
                if (chunk instanceof IFlowFieldHolder holder) {
                    ChunkFlowField flowField = holder.reterraforged$getFlowField();

                    // If the chunk has no rivers, quietly exit. It's just dry land!
                    if (!flowField.hasRivers()) {
                        return;
                    }

                    if (flowField.hasRivers()) {
                        NetworkManager.sendToPlayer(
                                player,
                                new FlowFieldSyncPayload(chunk.getPos(), flowField.getRawGrid())
                        );
                    }
                }
            }
        }
    }
}