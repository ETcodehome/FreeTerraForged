package etcodehome.freeterraforged.network;

import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.resources.ResourceLocation;

/** Snapshot destination owned by the {@code /ftf preview} feature. */
public final class PreviewSnapshotPurpose {
	public static final ResourceLocation OPEN = FTFCommon.location("preview_open");

	private PreviewSnapshotPurpose() {
	}
}
