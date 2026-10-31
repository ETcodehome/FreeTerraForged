package etcodehome.freeterraforged.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ActivePresetSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Purpose-routed transport for a server-authoritative active-preset snapshot. */
public record ActivePresetSnapshotPayload(
	ResourceLocation purpose,
	ActivePresetSnapshot snapshot
) implements CustomPacketPayload {
	private static final int MAX_TEXT_LENGTH = 1_048_576;
	private static final int MAX_COMPRESSED_CONFIG_LENGTH = 1_048_576;
	private static final int MAX_CONFIG_LENGTH = 4 * 1024 * 1024;

	public static final Type<ActivePresetSnapshotPayload> TYPE = new Type<>(
		FTFCommon.location("active_preset_snapshot")
	);
	public static final StreamCodec<FriendlyByteBuf, ActivePresetSnapshotPayload> CODEC = StreamCodec.of(
		ActivePresetSnapshotPayload::encode,
		ActivePresetSnapshotPayload::decode
	);

	public ActivePresetSnapshotPayload {
		purpose = Objects.requireNonNull(purpose, "purpose");
		snapshot = Objects.requireNonNull(snapshot, "snapshot");
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static void encode(FriendlyByteBuf buffer, ActivePresetSnapshotPayload payload) {
		ActivePresetSnapshot snapshot = payload.snapshot();
		buffer.writeResourceLocation(payload.purpose());
		buffer.writeUtf(snapshot.presetJson(), MAX_TEXT_LENGTH);
		buffer.writeUtf(snapshot.dimension(), 256);
		buffer.writeLong(snapshot.seed());
		buffer.writeInt(snapshot.playerX());
		buffer.writeInt(snapshot.playerZ());
		buffer.writeInt(snapshot.spawnX());
		buffer.writeInt(snapshot.spawnZ());
		buffer.writeByteArray(compress(snapshot.biomeReplacements()));
	}

	private static ActivePresetSnapshotPayload decode(FriendlyByteBuf buffer) {
		ResourceLocation purpose = buffer.readResourceLocation();
		ActivePresetSnapshot snapshot = new ActivePresetSnapshot(
			buffer.readUtf(MAX_TEXT_LENGTH),
			buffer.readUtf(256),
			buffer.readLong(),
			buffer.readInt(),
			buffer.readInt(),
			buffer.readInt(),
			buffer.readInt(),
			decompress(buffer.readByteArray(MAX_COMPRESSED_CONFIG_LENGTH))
		);
		return new ActivePresetSnapshotPayload(purpose, snapshot);
	}

	private static byte[] compress(String value) {
		byte[] input = value.getBytes(StandardCharsets.UTF_8);
		if (input.length > MAX_CONFIG_LENGTH) {
			throw new IllegalArgumentException("Biome replacement config exceeds the 4 MiB payload limit");
		}
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
				gzip.write(input);
			}
			byte[] compressed = bytes.toByteArray();
			if (compressed.length > MAX_COMPRESSED_CONFIG_LENGTH) {
				throw new IllegalArgumentException("Compressed biome replacement config exceeds the 1 MiB payload limit");
			}
			return compressed;
		} catch (IOException failure) {
			throw new IllegalStateException("Failed to compress biome replacement config", failure);
		}
	}

	private static String decompress(byte[] compressed) {
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			byte[] value = gzip.readNBytes(MAX_CONFIG_LENGTH + 1);
			if (value.length > MAX_CONFIG_LENGTH || gzip.read() != -1) {
				throw new IllegalArgumentException("Biome replacement config exceeds the 4 MiB payload limit");
			}
			return new String(value, StandardCharsets.UTF_8);
		} catch (IOException failure) {
			throw new IllegalArgumentException("Invalid compressed biome replacement config", failure);
		}
	}
}
