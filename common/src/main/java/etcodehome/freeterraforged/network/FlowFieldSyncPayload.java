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
        ChunkPos pos = new ChunkPos(buf.readLong());
        byte[] rawGrid = buf.readByteArray(256);
        return new FlowFieldSyncPayload(pos, rawGrid);
    }
}