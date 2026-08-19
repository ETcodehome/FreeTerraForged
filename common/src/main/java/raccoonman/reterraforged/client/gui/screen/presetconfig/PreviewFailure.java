package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.RTFCommon;

/** A recoverable failure in one preview pane. */
final class PreviewFailure {
    private PreviewFailure() {
    }

    static PreviewFailure log(String operation, Throwable throwable) {
        RTFCommon.LOGGER.error(operation, unwrap(throwable));
        return new PreviewFailure();
    }

    static void renderUnavailable(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF151515);

        int iconWidth = Math.min(48, Math.max(32, width - 16));
        int iconHeight = Math.min(36, Math.max(28, height - 38));
        int iconX = x + (width - iconWidth) / 2;
        int iconY = y + Math.max(8, height / 2 - iconHeight - 8);

        // A small broken-image glyph: frame, sun, mountains, and a diagonal break.
        graphics.fill(iconX, iconY, iconX + iconWidth, iconY + 2, 0xFF777777);
        graphics.fill(iconX, iconY + iconHeight - 2, iconX + iconWidth, iconY + iconHeight, 0xFF777777);
        graphics.fill(iconX, iconY, iconX + 2, iconY + iconHeight, 0xFF777777);
        graphics.fill(iconX + iconWidth - 2, iconY, iconX + iconWidth, iconY + iconHeight, 0xFF777777);
        graphics.fill(iconX + 8, iconY + 8, iconX + 14, iconY + 14, 0xFFFFCC55);
        graphics.fill(iconX + 6, iconY + iconHeight - 10, iconX + 16, iconY + iconHeight - 4, 0xFF6688AA);
        graphics.fill(iconX + 13, iconY + iconHeight - 16, iconX + 23, iconY + iconHeight - 4, 0xFF6688AA);
        graphics.fill(iconX + 22, iconY + iconHeight - 12, iconX + iconWidth - 6, iconY + iconHeight - 4, 0xFF6688AA);
        for (int offset = 0; offset < iconWidth - 12; offset++) {
            int slashY = iconY + 5 + offset * iconHeight / Math.max(1, iconWidth - 12);
            graphics.fill(iconX + 6 + offset, slashY, iconX + 9 + offset, slashY + 3, 0xFFDD6666);
        }

        graphics.drawCenteredString(
            Minecraft.getInstance().font,
            Component.literal("Preview Unavailable"),
            x + width / 2,
            iconY + iconHeight + 8,
            0xFFE0E0E0
        );
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
