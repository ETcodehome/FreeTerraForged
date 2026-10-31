package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.List;

import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetViewModel.Section;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetViewModel.Setting;
import etcodehome.freeterraforged.client.gui.widget.ClipboardButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Scrollable, responsive grid of preset setting-family cards. */
final class PresetSectionGrid extends AbstractScrollWidget implements ContainerEventHandler {
	private static final int MAX_COLUMNS = 3;
	private static final int MIN_CARD_WIDTH = 220;
	private static final int MAX_CARD_WIDTH = 440;
	private static final int GRID_PADDING = 8;
	private static final int CARD_GAP = 8;
	private static final int CARD_PADDING = 6;
	private static final int CARD_HEADER_HEIGHT = 22;
	private static final int SETTING_HEIGHT = 20;
	private static final int SETTING_GAP = 4;

	private final Font font;
	private final List<Card> cards = new ArrayList<>();
	private final List<ClipboardButton> buttons = new ArrayList<>();
	private GuiEventListener focusedChild;
	private boolean dragging;
	private int innerHeight;

	PresetSectionGrid(int x, int y, int width, int height, Font font, List<Section> sections) {
		super(x, y, width, height, Component.translatableWithFallback(
			"freeterraforged.gui.preset_view.settings", "Preset settings"
		));
		this.font = font;
		Component copied = Component.translatableWithFallback(
			"freeterraforged.gui.preset_view.copied", "Copied to clipboard"
		);
		for (Section section : sections) {
			List<ClipboardButton> sectionButtons = new ArrayList<>();
			for (Setting setting : section.settings()) {
				ClipboardButton button = new ClipboardButton(
					0,
					0,
					MIN_CARD_WIDTH - CARD_PADDING * 2,
					SETTING_HEIGHT,
					setting.message(),
					setting::copiedText,
					setting.tooltip(),
					copied
				);
				sectionButtons.add(button);
				this.buttons.add(button);
			}
			this.cards.add(new Card(section.title(), sectionButtons));
		}
		this.layoutCards();
	}

	void tick() {
		this.buttons.forEach(ClipboardButton::tick);
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return this.buttons;
	}

	@Override
	public GuiEventListener getFocused() {
		return this.focusedChild;
	}

	@Override
	public void setFocused(GuiEventListener focused) {
		if (this.focusedChild != null) {
			this.focusedChild.setFocused(false);
		}
		this.focusedChild = focused;
		if (focused != null) {
			focused.setFocused(true);
			super.setFocused(true);
		}
	}

	@Override
	public boolean isDragging() {
		return this.dragging;
	}

	@Override
	public void setDragging(boolean dragging) {
		this.dragging = dragging;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (this.withinContentAreaPoint(mouseX, mouseY)) {
			for (ClipboardButton child : this.buttons) {
				if (child.mouseClicked(mouseX, mouseY, button)) {
					this.setFocused(child);
					this.setDragging(true);
					return true;
				}
			}
		}
		boolean handled = super.mouseClicked(mouseX, mouseY, button);
		if (handled) {
			super.setFocused(true);
		}
		return handled;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean childHandled = this.focusedChild != null && this.focusedChild.mouseReleased(mouseX, mouseY, button);
		this.setDragging(false);
		return super.mouseReleased(mouseX, mouseY, button) || childHandled;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (this.focusedChild != null && this.dragging
			&& this.focusedChild.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (this.focusedChild != null && this.focusedChild.keyPressed(keyCode, scanCode, modifiers)) {
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(graphics);
		graphics.enableScissor(this.getX() + 1, this.getY() + 1, this.getRight() - 1, this.getBottom() - 1);
		int scroll = (int) this.scrollAmount();
		for (Card card : this.cards) {
			int cardY = this.getY() + card.y - scroll;
			boolean cardVisible = cardY + card.height > this.getY() && cardY < this.getBottom();
			if (cardVisible) {
				this.renderCard(graphics, card, cardY, mouseX, mouseY, partialTick);
			} else {
				card.buttons.forEach(button -> button.visible = false);
			}
		}
		graphics.disableScissor();
		this.renderDecorations(graphics);
	}

	private void renderCard(
		GuiGraphics graphics,
		Card card,
		int y,
		int mouseX,
		int mouseY,
		float partialTick
	) {
		int x = this.getX() + card.x;
		int right = x + card.width;
		int bottom = y + card.height;
		graphics.fill(x, y, right, bottom, 0xD0101010);
		graphics.fill(x, y, right, y + 1, 0xFF606060);
		graphics.fill(x, bottom - 1, right, bottom, 0xFF303030);
		graphics.fill(x, y, x + 1, bottom, 0xFF606060);
		graphics.fill(right - 1, y, right, bottom, 0xFF303030);
		graphics.drawString(this.font, card.title, x + CARD_PADDING, y + 7, 0xFFFFFF, false);
		graphics.fill(x + CARD_PADDING, y + CARD_HEADER_HEIGHT - 2, right - CARD_PADDING, y + CARD_HEADER_HEIGHT - 1, 0xFF454545);

		int buttonY = y + CARD_HEADER_HEIGHT + CARD_PADDING;
		for (ClipboardButton button : card.buttons) {
			button.setX(x + CARD_PADDING);
			button.setY(buttonY);
			button.setWidth(card.width - CARD_PADDING * 2);
			button.visible = buttonY + SETTING_HEIGHT > this.getY() && buttonY < this.getBottom();
			if (button.visible) {
				button.render(graphics, mouseX, mouseY, partialTick);
			}
			buttonY += SETTING_HEIGHT + SETTING_GAP;
		}
	}

	private void layoutCards() {
		int contentWidth = Math.max(1, this.getWidth() - GRID_PADDING * 2);
		int columns = Math.max(1, Math.min(MAX_COLUMNS, (contentWidth + CARD_GAP) / (MIN_CARD_WIDTH + CARD_GAP)));
		columns = Math.min(columns, Math.max(1, this.cards.size()));
		int cardWidth = Math.max(1, Math.min(
			MAX_CARD_WIDTH,
			(contentWidth - CARD_GAP * (columns - 1)) / columns
		));
		int gridWidth = cardWidth * columns + CARD_GAP * (columns - 1);
		int gridX = GRID_PADDING + Math.max(0, (contentWidth - gridWidth) / 2);
		int[] columnHeights = new int[columns];
		java.util.Arrays.fill(columnHeights, GRID_PADDING);
		for (Card card : this.cards) {
			int column = shortestColumn(columnHeights);
			card.x = gridX + column * (cardWidth + CARD_GAP);
			card.y = columnHeights[column];
			card.width = cardWidth;
			card.height = CARD_HEADER_HEIGHT + CARD_PADDING * 2
				+ card.buttons.size() * SETTING_HEIGHT
				+ Math.max(0, card.buttons.size() - 1) * SETTING_GAP;
			columnHeights[column] += card.height + CARD_GAP;
		}
		int tallestColumn = java.util.Arrays.stream(columnHeights).max().orElse(GRID_PADDING);
		this.innerHeight = this.cards.isEmpty()
			? 0
			: Math.max(0, tallestColumn - CARD_GAP + GRID_PADDING);
	}

	private static int shortestColumn(int[] heights) {
		int shortest = 0;
		for (int column = 1; column < heights.length; column++) {
			if (heights[column] < heights[shortest]) {
				shortest = column;
			}
		}
		return shortest;
	}

	@Override
	protected int getInnerHeight() {
		return this.innerHeight;
	}

	@Override
	protected double scrollRate() {
		return SETTING_HEIGHT + SETTING_GAP;
	}

	@Override
	protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// renderWidget owns positioning so child hitboxes and tooltips follow the scrolled visuals.
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
		if (this.focusedChild instanceof ClipboardButton button) {
			output.add(NarratedElementType.POSITION, button.getMessage());
		}
		output.add(NarratedElementType.USAGE, Component.translatableWithFallback(
			"freeterraforged.gui.preset_view.narration", "Select a setting to copy its JSON value"
		));
	}

	private static final class Card {
		private final Component title;
		private final List<ClipboardButton> buttons;
		private int x;
		private int y;
		private int width;
		private int height;

		private Card(Component title, List<ClipboardButton> buttons) {
			this.title = title;
			this.buttons = buttons;
		}
	}
}
