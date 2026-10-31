package etcodehome.freeterraforged.network;

import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.resources.ResourceLocation;

/** Snapshot destinations owned by the {@code /ftf preset} feature. */
public final class PresetSnapshotPurpose {
	public static final ResourceLocation COPY = FTFCommon.location("preset_copy");
	public static final ResourceLocation VIEW = FTFCommon.location("preset_view");

	private PresetSnapshotPurpose() {
	}
}
