package raccoonman.reterraforged.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class DropdownButton<T> extends AbstractWidget {
    private final List<T> options;
    private final Function<T, Component> nameProvider;
    private final Consumer<T> onSelect;
    private T selectedValue;
    private boolean isExpanded = false;
    private final int optionHeight = 20;

    public DropdownButton(int x, int y, int width, int height, List<T> options, T defaultValue, Function<T, Component> nameProvider, Consumer<T> onSelect) {
        super(x, y, width, height, Component.empty());
        this.options = options;
        this.selectedValue = defaultValue;
        this.nameProvider = nameProvider;
        this.onSelect = onSelect;
    }

    public T getValue() {
        return this.selectedValue;
    }

    /**
     * Checks if the dropdown is expanded and if the mouse is hovering over the open options menu.
     */
    public boolean isExpandedAndHovered(double mouseX, double mouseY) {
        if (!this.visible || !this.isExpanded) return false;

        int startY = this.getY() + this.height;
        int endY = startY + (this.options.size() * this.optionHeight);

        return mouseX >= this.getX() && mouseX < this.getX() + this.width
                && mouseY >= startY && mouseY < endY;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 1. Get the correct button texture sprite based on the current widget state
        net.minecraft.resources.ResourceLocation sprite;
        if (!this.active) {
            sprite = net.minecraft.resources.ResourceLocation.withDefaultNamespace("widget/button_disabled");
        } else if (this.isHoveredOrFocused()) {
            sprite = net.minecraft.resources.ResourceLocation.withDefaultNamespace("widget/button_highlighted");
        } else {
            sprite = net.minecraft.resources.ResourceLocation.withDefaultNamespace("widget/button");
        }

        // 2. Render the background automatically stretched to your widget's width/height
        guiGraphics.blitSprite(sprite, this.getX(), this.getY(), this.getWidth(), this.getHeight());

        // 3. Render current selection text + visual arrow indicator
        Component text = Component.literal(nameProvider.apply(selectedValue).getString() + (isExpanded ? " ▲" : " ▼"));
        guiGraphics.drawCenteredString(Minecraft.getInstance().font, text, this.getX() + this.width / 2, this.getY() + (this.height - 8) / 2, 0xFFFFFF);

        // 4. Render dropdown elements if expanded
        if (isExpanded) {
            int currentY = this.getY() + this.height;
            for (int i = 0; i < options.size(); i++) {
                T option = options.get(i);
                boolean isHoveringOption = mouseX >= this.getX() && mouseX < this.getX() + this.width && mouseY >= currentY && mouseY < currentY + optionHeight;

                // Draw dropdown row background
                guiGraphics.fill(this.getX(), currentY, this.getX() + this.width, currentY + optionHeight, isHoveringOption ? 0xAA555555 : 0xAA000000);
                // Draw option text
                guiGraphics.drawString(Minecraft.getInstance().font, nameProvider.apply(option), this.getX() + 6, currentY + (optionHeight - 8) / 2, 0xFFFFFF, false);

                currentY += optionHeight;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible) return false;

        // Clicking the main button toggles expansion
        if (this.clicked(mouseX, mouseY)) {
            this.isExpanded = !this.isExpanded;
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }

        // Clicking options when expanded
        if (this.isExpanded) {
            int currentY = this.getY() + this.height;
            for (T option : options) {
                if (mouseX >= this.getX() && mouseX < this.getX() + this.width && mouseY >= currentY && mouseY < currentY + optionHeight) {
                    this.selectedValue = option;
                    this.isExpanded = false;
                    this.onSelect.accept(option);
                    this.playDownSound(Minecraft.getInstance().getSoundManager());
                    return true;
                }
                currentY += optionHeight;
            }
            // Clicked outside the expanded container -> close menu
            this.isExpanded = false;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Tells the accessibility screen reader what the button is and what's selected
        narrationElementOutput.add(NarratedElementType.TITLE, Component.translatable("gui.narrate.button", nameProvider.apply(selectedValue)));
        if (isExpanded) {
            narrationElementOutput.add(NarratedElementType.USAGE, Component.literal("Dropdown expanded. Select an option."));
        }
    }
}