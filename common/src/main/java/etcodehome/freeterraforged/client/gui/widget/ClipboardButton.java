package etcodehome.freeterraforged.client.gui.widget;

import java.time.Duration;
import java.util.function.Supplier;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** A vanilla button that copies text and briefly confirms the action in its tooltip. */
public final class ClipboardButton extends Button {
	private static final Duration DEFAULT_TOOLTIP_DELAY = Duration.ofMillis(350L);
	private static final long COPIED_MESSAGE_DURATION_MS = 1_750L;

	private final Supplier<String> copiedText;
	private final Tooltip defaultTooltip;
	private final Tooltip copiedTooltip;
	private long copiedMessageExpiresAt;

	public ClipboardButton(
		int x,
		int y,
		int width,
		int height,
		Component message,
		Supplier<String> copiedText,
		Component defaultTooltip,
		Component copiedTooltip
	) {
		super(x, y, width, height, message, ignored -> {}, DEFAULT_NARRATION);
		this.copiedText = copiedText;
		this.defaultTooltip = Tooltip.create(defaultTooltip);
		this.copiedTooltip = Tooltip.create(copiedTooltip);
		this.restoreDefaultTooltip();
	}

	@Override
	public void onPress() {
		Minecraft.getInstance().keyboardHandler.setClipboard(this.copiedText.get());
		this.copiedMessageExpiresAt = Util.getMillis() + COPIED_MESSAGE_DURATION_MS;
		this.setTooltip(this.copiedTooltip);
		this.setTooltipDelay(Duration.ZERO);
	}

	public void tick() {
		if (this.copiedMessageExpiresAt != 0L && Util.getMillis() >= this.copiedMessageExpiresAt) {
			this.copiedMessageExpiresAt = 0L;
			this.restoreDefaultTooltip();
		}
	}

	private void restoreDefaultTooltip() {
		this.setTooltip(this.defaultTooltip);
		this.setTooltipDelay(DEFAULT_TOOLTIP_DELAY);
	}
}
