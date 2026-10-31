package etcodehome.freeterraforged.network;

import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PreviewLocationPayload(
	long requestId,
	String dimension,
	PreviewLocateRequestPayload.TargetType targetType,
	Status status,
	int x,
	int z,
	String target
) implements CustomPacketPayload {
	public static final Type<PreviewLocationPayload> TYPE = new Type<>(
		FTFCommon.location("preview_location")
	);
	public static final StreamCodec<FriendlyByteBuf, PreviewLocationPayload> CODEC = StreamCodec.of(
		(buffer, payload) -> {
			buffer.writeLong(payload.requestId);
			buffer.writeUtf(payload.dimension, 256);
			buffer.writeEnum(payload.targetType);
			buffer.writeEnum(payload.status);
			buffer.writeInt(payload.x);
			buffer.writeInt(payload.z);
			buffer.writeUtf(payload.target, 256);
		},
		buffer -> new PreviewLocationPayload(
			buffer.readLong(),
			buffer.readUtf(256),
			buffer.readEnum(PreviewLocateRequestPayload.TargetType.class),
			buffer.readEnum(Status.class),
			buffer.readInt(),
			buffer.readInt(),
			buffer.readUtf(256)
		)
	);

	public boolean found() {
		return this.status == Status.FOUND;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public enum Status {
		FOUND,
		NOT_FOUND,
		TIMED_OUT,
		IMPOSSIBLE,
		INVALID_TARGET,
		UNAVAILABLE,
		NO_PERMISSION,
		TARGET_REQUIRED,
		FAILED
	}
}
