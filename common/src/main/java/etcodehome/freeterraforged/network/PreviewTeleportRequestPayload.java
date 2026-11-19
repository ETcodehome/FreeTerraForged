package etcodehome.freeterraforged.network;

import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PreviewTeleportRequestPayload(
	String dimension,
	int x,
	int y,
	int z,
	boolean exactY
) implements CustomPacketPayload {
	public static final Type<PreviewTeleportRequestPayload> TYPE = new Type<>(
		FTFCommon.location("preview_teleport_request")
	);
	public static final StreamCodec<FriendlyByteBuf, PreviewTeleportRequestPayload> CODEC = StreamCodec.of(
		(buffer, payload) -> {
			buffer.writeUtf(payload.dimension, 256);
			buffer.writeInt(payload.x);
			buffer.writeInt(payload.y);
			buffer.writeInt(payload.z);
			buffer.writeBoolean(payload.exactY);
		},
		buffer -> new PreviewTeleportRequestPayload(
			buffer.readUtf(256),
			buffer.readInt(),
			buffer.readInt(),
			buffer.readInt(),
			buffer.readBoolean()
		)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
