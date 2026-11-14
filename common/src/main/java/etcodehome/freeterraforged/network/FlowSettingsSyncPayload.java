package etcodehome.freeterraforged.network;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.FlowSettingsSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record FlowSettingsSyncPayload(FlowSettingsSnapshot settings) {
	public static final ResourceLocation ID = FTFCommon.location("flow_settings_sync");

	public FriendlyByteBuf toBuffer() {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeByte(this.settings.encode());
		return buffer;
	}

	public static FlowSettingsSyncPayload fromBuffer(FriendlyByteBuf buffer) {
		return new FlowSettingsSyncPayload(FlowSettingsSnapshot.decode(buffer.readByte()));
	}
}
