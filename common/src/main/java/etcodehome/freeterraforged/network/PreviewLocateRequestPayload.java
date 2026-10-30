package etcodehome.freeterraforged.network;

import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PreviewLocateRequestPayload(
	long requestId,
	TargetType targetType,
	String target,
	String dimension
) implements CustomPacketPayload {
	public static final Type<PreviewLocateRequestPayload> TYPE = new Type<>(
		FTFCommon.location("preview_locate_request")
	);
	public static final StreamCodec<FriendlyByteBuf, PreviewLocateRequestPayload> CODEC = StreamCodec.of(
		(buffer, payload) -> {
			buffer.writeLong(payload.requestId);
			buffer.writeEnum(payload.targetType);
			buffer.writeUtf(payload.target, 256);
			buffer.writeUtf(payload.dimension, 256);
		},
		buffer -> new PreviewLocateRequestPayload(
			buffer.readLong(),
			buffer.readEnum(TargetType.class),
			buffer.readUtf(256),
			buffer.readUtf(256)
		)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public enum TargetType {
		TERRAIN,
		BIOME,
		CANCEL
	}
}
