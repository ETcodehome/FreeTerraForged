package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import etcodehome.freeterraforged.world.worldgen.search.TerrainSearchTargets;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;

/** Brigadier-backed completion popup for the preview navigation grammar. */
final class PreviewNavigationSuggestions extends AbstractWidget {
	private static final int ROW_HEIGHT = 12;
	private static final int MAX_VISIBLE = 7;
	private static final int FOREGROUND_DEPTH = 400;

	private final Font font;
	private final EditBox input;
	private final PreviewNavigation.Context context;
	private final Supplier<RegistryAccess> registries;
	private List<Suggestion> matches = List.of();
	private int selected;
	private int firstVisible;

	PreviewNavigationSuggestions(
		Font font,
		EditBox input,
		PreviewNavigation.Context context,
		Supplier<RegistryAccess> registries
	) {
		super(
			input.getX(), input.getBottom(), input.getWidth(), 0,
			Component.translatableWithFallback(
				"freeterraforged.gui.preset_preview.suggestions", "Preview navigation suggestions"
			)
		);
		this.font = font;
		this.input = input;
		this.context = context;
		this.registries = registries;
		this.visible = false;
	}

	void refresh() {
		if (!this.input.isFocused()) {
			this.hide();
			return;
		}
		String value = this.input.getValue();
		int cursor = this.input.getCursorPosition();
		this.matches = this.findMatches(value, cursor).getList();
		this.selected = 0;
		this.firstVisible = 0;
		this.visible = !this.matches.isEmpty();
		this.setHeight(Math.min(MAX_VISIBLE, this.matches.size()) * ROW_HEIGHT + 2);
	}

	void hide() {
		this.visible = false;
		this.matches = List.of();
		this.selected = 0;
		this.firstVisible = 0;
		this.setHeight(0);
	}

	boolean dismissIfOutside(double mouseX, double mouseY) {
		if (!this.visible || this.input.isMouseOver(mouseX, mouseY) || this.isMouseOver(mouseX, mouseY)) {
			return false;
		}
		this.hide();
		return true;
	}

	boolean handleInputKey(int keyCode) {
		if (!this.visible) {
			return false;
		}
		switch (keyCode) {
			case GLFW.GLFW_KEY_UP -> {
				this.moveSelection(-1);
				return true;
			}
			case GLFW.GLFW_KEY_DOWN -> {
				this.moveSelection(1);
				return true;
			}
			case GLFW.GLFW_KEY_TAB -> {
				this.applySelected();
				return true;
			}
			case GLFW.GLFW_KEY_ESCAPE -> {
				this.hide();
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (this.handleInputKey(keyCode)) {
			return true;
		}
		return this.input.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
		this.input.setFocused(focused);
		if (focused) {
			// A mouse completion transfers Screen focus to this popup after onClick.
			// Keep subsequent typing routed to the edit box without reopening the completed row.
			this.hide();
		}
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		return this.input.charTyped(codePoint, modifiers);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!this.visible || scrollY == 0.0D) {
			return false;
		}
		this.moveSelection(scrollY > 0.0D ? -1 : 1);
		return true;
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		int row = (int) ((mouseY - this.getY() - 1) / ROW_HEIGHT);
		int index = this.firstVisible + row;
		if (index >= 0 && index < this.matches.size()) {
			this.selected = index;
			this.applySelected();
			this.input.setFocused(true);
		}
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int visibleCount = Math.min(MAX_VISIBLE, this.matches.size() - this.firstVisible);
		if (visibleCount <= 0) {
			return;
		}

		graphics.pose().pushPose();
		graphics.pose().translate(0.0F, 0.0F, FOREGROUND_DEPTH);
		try {
			int bottom = this.getY() + visibleCount * ROW_HEIGHT + 2;
			graphics.fill(this.getX(), this.getY(), this.getRight(), bottom, 0xFF000000);
			for (int row = 0; row < visibleCount; row++) {
				int index = this.firstVisible + row;
				int rowY = this.getY() + 1 + row * ROW_HEIGHT;
				boolean hovered = mouseX >= this.getX() && mouseX < this.getRight()
					&& mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
				if (hovered) {
					this.selected = index;
				}
				if (index == this.selected) {
					graphics.fill(
						this.getX() + 1, rowY, this.getRight() - 1, rowY + ROW_HEIGHT, 0xFFAAAAAA
					);
				}
				String text = this.font.plainSubstrByWidth(
					this.matches.get(index).getText(), Math.max(0, this.getWidth() - 6)
				);
				graphics.drawString(
					this.font, text, this.getX() + 3, rowY + 2,
					index == this.selected ? 0xFF000000 : 0xFFFFFFFF, false
				);
			}
		} finally {
			graphics.pose().popPose();
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
		if (!this.matches.isEmpty()) {
			output.add(NarratedElementType.POSITION, Component.literal(
				(this.selected + 1) + " of " + this.matches.size()
			));
		}
	}

	private Suggestions findMatches(String value, int cursor) {
		String prefix = value.substring(0, Math.min(cursor, value.length()));
		int separator = prefix.indexOf(' ');
		if (separator < 0) {
			Stream<String> operations = Stream.of("terrain ", "biome ");
			if (this.context.supportsSpawn()) {
				operations = Stream.concat(Stream.of("spawn"), operations);
			}
			if (this.context.supportsHere()) {
				operations = Stream.concat(Stream.of("here"), operations);
			}
			return SharedSuggestionProvider.suggest(operations, new SuggestionsBuilder(prefix, 0)).join();
		}

		String operation = prefix.substring(0, separator);
		int argumentStart = separator + 1;
		while (argumentStart < prefix.length() && prefix.charAt(argumentStart) == ' ') {
			argumentStart++;
		}
		if (prefix.substring(argumentStart).contains(" ")) {
			return Suggestions.empty().join();
		}

		SuggestionsBuilder builder = new SuggestionsBuilder(prefix, argumentStart);
		if (operation.equalsIgnoreCase("terrain")) {
			return SharedSuggestionProvider.suggest(TerrainSearchTargets.names(), builder).join();
		}
		if (operation.equalsIgnoreCase("biome")) {
			var biomes = this.registries.get().lookupOrThrow(Registries.BIOME);
			return SharedSuggestionProvider.suggest(Stream.concat(
				biomes.listElementIds().map(key -> key.location().toString()),
				biomes.listTagIds().map(tag -> "#" + tag.location())
			), builder).join();
		}
		return Suggestions.empty().join();
	}

	private void moveSelection(int amount) {
		this.selected = Math.floorMod(this.selected + amount, this.matches.size());
		if (this.selected < this.firstVisible) {
			this.firstVisible = this.selected;
		} else if (this.selected >= this.firstVisible + MAX_VISIBLE) {
			this.firstVisible = this.selected - MAX_VISIBLE + 1;
		}
	}

	private void applySelected() {
		if (this.matches.isEmpty()) {
			return;
		}
		Suggestion suggestion = this.matches.get(this.selected);
		String completed = suggestion.apply(this.input.getValue());
		this.input.setValue(completed);
		this.input.setCursorPosition(completed.length());
		this.hide();
	}
}
