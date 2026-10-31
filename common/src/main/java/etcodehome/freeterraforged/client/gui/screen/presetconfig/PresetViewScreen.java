package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetViewModel.Category;
import etcodehome.freeterraforged.client.gui.widget.ClipboardButton;
import etcodehome.freeterraforged.client.gui.widget.WidgetList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Native, read-only browser for a server-supplied active-preset snapshot. */
public final class PresetViewScreen extends Screen {
	private static final String KEY = "freeterraforged.gui.preset_view.";

	private final String presetJson;
	private final String dimension;
	private final JsonObject root;
	private final String presetName;
	private final List<Category> model;
	private String selectedCategory;
	private WidgetList<Button> categories;
	private PresetSectionGrid settings;
	private ClipboardButton copyJsonButton;

	public PresetViewScreen(String presetJson, String dimension) {
		super(Component.translatableWithFallback(KEY + "title", "Active FreeTerraForged Preset"));
		this.presetJson = presetJson;
		this.dimension = dimension;
		JsonElement parsed = JsonParser.parseString(presetJson);
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("Active preset snapshot must be a JSON object");
		}
		this.root = parsed.getAsJsonObject();
		this.presetName = readPresetName(this.root);
		this.model = PresetViewModel.create(this.root);
		this.selectedCategory = this.model.isEmpty() ? "" : this.model.getFirst().key();
	}

	@Override
	protected void init() {
		int top = 48;
		int bottom = this.height - 34;
		int contentHeight = Math.max(40, bottom - top);
		int margin = Math.max(8, Math.min(24, this.width / 30));
		int gap = 8;
		int categoryWidth = Math.max(112, Math.min(180, this.width / 5));
		int settingsWidth = Math.max(120, this.width - margin * 2 - categoryWidth - gap - 8);

		this.categories = new WidgetList<>(this.minecraft, categoryWidth, contentHeight, top, 24);
		this.categories.setX(margin);
		this.categories.setRenderSelected(false);
		for (Category category : this.model) {
			Button button = Button.builder(category.title(), ignored -> {
				this.selectedCategory = category.key();
				this.rebuildWidgets();
			}).bounds(0, 0, categoryWidth - 20, 20).build();
			button.active = !category.key().equals(this.selectedCategory);
			this.categories.addWidget(button);
			if (category.key().equals(this.selectedCategory)) {
				this.categories.select(button);
			}
		}

		Category selected = this.model.stream()
			.filter(category -> category.key().equals(this.selectedCategory))
			.findFirst()
			.orElseGet(() -> this.model.isEmpty() ? null : this.model.getFirst());
		this.settings = new PresetSectionGrid(
			margin + categoryWidth + gap,
			top,
			settingsWidth,
			contentHeight,
			this.font,
			selected == null ? List.of() : selected.sections()
		);

		this.addRenderableWidget(this.categories);
		this.addRenderableWidget(this.settings);

		int buttonWidth = Math.min(160, Math.max(120, (this.width - 24) / 2));
		int buttonY = this.height - 27;
		this.copyJsonButton = new ClipboardButton(
			this.width / 2 - buttonWidth - 3,
			buttonY,
			buttonWidth,
			20,
			Component.translatableWithFallback(KEY + "copy_json", "Copy Preset JSON"),
			() -> this.presetJson,
			Component.translatableWithFallback(KEY + "copy_json_tooltip", "Copy the complete active preset JSON"),
			Component.translatableWithFallback(KEY + "copied", "Copied to clipboard")
		);
		this.addRenderableWidget(this.copyJsonButton);
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignored -> this.onClose())
			.bounds(this.width / 2 + 3, buttonY, buttonWidth, 20).build());
	}

	@Override
	public void tick() {
		super.tick();
		if (this.settings != null) {
			this.settings.tick();
		}
		if (this.copyJsonButton != null) {
			this.copyJsonButton.tick();
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
		Component subtitle = Component.literal(this.presetName + "  •  " + this.dimension)
			.withStyle(ChatFormatting.GRAY);
		graphics.drawCenteredString(this.font, subtitle, this.width / 2, 26, 0xA0A0A0);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(null);
	}

	private static String readPresetName(JsonObject root) {
		JsonElement presentation = root.get("presentation");
		if (presentation != null && presentation.isJsonObject()) {
			JsonElement name = presentation.getAsJsonObject().get("presetName");
			if (name != null && name.isJsonPrimitive() && !name.getAsString().isBlank()) {
				return name.getAsString();
			}
		}
		return Component.translatableWithFallback(KEY + "unnamed", "Unnamed preset").getString();
	}
}
