package raccoonman.reterraforged.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import raccoonman.reterraforged.world.worldgen.FlowSettingsSnapshot;

public record FlowSettingsSyncPayload(FlowSettingsSnapshot settings) implements CustomPacketPayload {
	public static final Type<FlowSettingsSyncPayload> TYPE = new Type<>(
		ResourceLocation.fromNamespaceAndPath("reterraforged", "flow_settings_sync")
	);
	public static final StreamCodec<FriendlyByteBuf, FlowSettingsSyncPayload> CODEC = StreamCodec.of(
		(buffer, payload) -> buffer.writeByte(payload.settings.encode()),
		buffer -> new FlowSettingsSyncPayload(FlowSettingsSnapshot.decode(buffer.readByte()))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
