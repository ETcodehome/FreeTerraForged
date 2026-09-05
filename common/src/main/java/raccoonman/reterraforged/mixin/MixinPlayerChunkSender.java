package raccoonman.reterraforged.mixin;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;
import raccoonman.reterraforged.network.FlowSettingsSyncPayload;
import raccoonman.reterraforged.network.FlowSettingsSyncState;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;
import raccoonman.reterraforged.world.worldgen.FlowSettingsSnapshot;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;
import raccoonman.reterraforged.world.worldgen.IFlowSettingsHolder;

@Mixin(PlayerChunkSender.class)
public class MixinPlayerChunkSender {
	@Unique
	private final FlowSettingsSyncState reterraforged$flowSettings = new FlowSettingsSyncState();

	@Inject(method = "sendNextChunks", at = @At("HEAD"))
	private void reterraforged$syncFlowSettings(ServerPlayer player, CallbackInfo callback) {
		ServerLevel level = player.serverLevel();
		FlowSettingsSnapshot settings = ((IFlowSettingsHolder) level).reterraforged$getFlowSettings();
		if (this.reterraforged$flowSettings.update(level.dimension(), settings)) {
			player.connection.send(new ClientboundCustomPayloadPacket(new FlowSettingsSyncPayload(settings)));
		}
	}

    @Inject(
            method = "sendChunk",
            at = @At("TAIL")
    )
    private static void onSendChunk(ServerGamePacketListenerImpl listener, ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
        if (chunk instanceof IFlowFieldHolder holder) {
            ChunkFlowField flowField = holder.reterraforged$getFlowField();

            if (flowField != null && flowField.hasRivers()) {
                listener.send(new ClientboundCustomPayloadPacket(
						new FlowFieldSyncPayload(chunk.getPos(), flowField.getRawGrid())
                ));
            }
        }
    }
}
