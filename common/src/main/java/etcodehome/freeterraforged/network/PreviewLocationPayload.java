package etcodehome.freeterraforged.network;

import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PreviewLocationPayload(
	long requestId,
	String dimension,
	boolean found,
	int x,
	int z,
	String message
) implements CustomPacketPayload {
	public static final Type<PreviewLocationPayload> TYPE = new Type<>(
		FTFCommon.location("preview_location")
	);
	public static final StreamCodec<FriendlyByteBuf, PreviewLocationPayload> CODEC = StreamCodec.of(
		(buffer, payload) -> {
			buffer.writeLong(payload.requestId);
			buffer.writeUtf(payload.dimension, 256);
			buffer.writeBoolean(payload.found);
			buffer.writeInt(payload.x);
			buffer.writeInt(payload.z);
			buffer.writeUtf(payload.message, 512);
		},
		buffer -> new PreviewLocationPayload(
			buffer.readLong(),
			buffer.readUtf(256),
			buffer.readBoolean(),
			buffer.readInt(),
			buffer.readInt(),
			buffer.readUtf(512)
		)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
