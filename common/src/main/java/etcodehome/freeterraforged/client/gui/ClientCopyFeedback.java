package etcodehome.freeterraforged.client.gui;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.Util;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Tracks transient hover feedback for FTF's clickable command output. */
public final class ClientCopyFeedback {
	private static final String PRESET_COPY_HOVER_KEY = FTFCommon.MOD_ID + ".commands.preset.copy";
	private static final long COPIED_MESSAGE_DURATION_MS = 1_750L;

	private static String copiedValue;
	private static long copiedMessageExpiresAt;

	private ClientCopyFeedback() {
	}

	public static void recordSuccessfulClick(@Nullable Style style) {
		ClickEvent click = copyEvent(style);
		if (click == null || !hasPresetCopyMarker(style)) {
			return;
		}
		copiedValue = click.getValue();
		copiedMessageExpiresAt = Util.getMillis() + COPIED_MESSAGE_DURATION_MS;
	}

	public static boolean shouldShowCopied(@Nullable Style style) {
		if (copiedValue == null || Util.getMillis() >= copiedMessageExpiresAt) {
			copiedValue = null;
			copiedMessageExpiresAt = 0L;
			return false;
		}
		ClickEvent click = copyEvent(style);
		return click != null && copiedValue.equals(click.getValue()) && hasPresetCopyMarker(style);
	}

	@Nullable
	private static ClickEvent copyEvent(@Nullable Style style) {
		if (style == null) {
			return null;
		}
		ClickEvent click = style.getClickEvent();
		return click != null && click.getAction() == ClickEvent.Action.COPY_TO_CLIPBOARD ? click : null;
	}

	private static boolean hasPresetCopyMarker(@Nullable Style style) {
		if (style == null) {
			return false;
		}
		HoverEvent hover = style.getHoverEvent();
		Component text = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_TEXT);
		return text != null
			&& text.getContents() instanceof TranslatableContents contents
			&& PRESET_COPY_HOVER_KEY.equals(contents.getKey());
	}
}
