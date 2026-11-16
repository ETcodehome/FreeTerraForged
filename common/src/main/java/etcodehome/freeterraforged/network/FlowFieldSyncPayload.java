package etcodehome.freeterraforged.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

public record FlowFieldSyncPayload(ChunkPos pos, byte[] rawGrid) {
    public static final ResourceLocation ID = new ResourceLocation("freeterraforged", "flow_sync");

    public FriendlyByteBuf toBuffer() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeLong(this.pos.toLong());
        buf.writeByteArray(this.rawGrid);
        return buf;
    }

    public static FlowFieldSyncPayload fromBuffer(FriendlyByteBuf buf) {
        return new FlowFieldSyncPayload(new ChunkPos(buf.readLong()), buf.readByteArray(256));
    }
}
