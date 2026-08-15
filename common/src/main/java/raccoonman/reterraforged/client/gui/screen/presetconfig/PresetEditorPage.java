package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.io.IOException;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldOptions;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.client.gui.screen.page.BisectedPage;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.client.gui.widget.Slider;

public abstract class PresetEditorPage extends BisectedPage<PresetConfigScreen, AbstractWidget, AbstractWidget> {
	// Independent control components
	Slider zoom2D;
	Slider zoom3D;
	CycleButton<RenderMode> renderMode2D;
	CycleButton<RenderMode> renderMode3D;
	protected PresetEntry preset;

	// Static persistent state containers
	public static int minZoom = 1;
	public static int maxZoom = 100;
	public static double staticZoom2D = 68.0D;
	public static double staticZoom3D = 95.0D;
	public static RenderMode staticMode2D = RenderMode.BIOME;
	public static RenderMode staticMode3D = RenderMode.HYPSOMETRIC;

	private EditBox seedEdit;
	private Button seedRandomize;
	private Preview3D preview3D;
	private Preview2D preview2D;

	public PresetEditorPage(PresetConfigScreen screen, PresetEntry preset) {
		super(screen);

		this.preset = preset;
	}

	protected void regenerate() {

		if (this.preview3D != null) {
			this.preview3D.regenerate();
		}

		if (this.preview2D != null) {
			this.preview2D.regenerate();
		}
	}

	PresetConfigScreen getScreen() {
		return this.screen;
	}

	PreviewComputationCache previewCache() {
		return this.screen.previewCache();
	}

	@Override
	public void init() {
		super.init();
		this.cleanupWidgets();

		int startX = this.left.getX();
		int totalWidth = (this.right.getX() + this.right.getWidth()) - startX;
		int columnWidth = totalWidth / 3;

		// COLUMN 2 (CENTER): Move the settings list here so it keeps its background box
		this.left.setX(startX + columnWidth);
		this.left.setWidth(columnWidth);

		// BACKGROUND REMOVAL: Push the right container off-screen so its background box isn't rendered
		this.right.setX(-9999);
		this.right.setWidth(0);
		this.right.setHeight(0);

		this.createControls();

		// 2. Dynamically fit the 3D preview inside the newly resized right column
		if (this.preview3D != null) {
			int padding = 10;

			// Calculate a responsive width based on the column's new size
			int dynamicWidth = this.right.getWidth() - (padding * 2);
			int dynamicHeight = dynamicWidth; // Keep it a clean, un-stretched square

			// Push the fresh coordinates and dimensions to the widget
			this.preview3D.updateBounds(
					this.right.getX() + padding,
					this.right.getY() + padding,
					dynamicWidth,
					dynamicHeight
			);
		}

		int elementWidth = this.left.getRowWidth();
		int forceOffset = 2;

		int yButtonRow1 = this.left.getY();

		// 2D Viewport setup (No background container)
		this.initLeftPreviewColumn(0,4, forceOffset, elementWidth, yButtonRow1 + 5);

		// 3D Viewport setup (No background container)
		this.initRightPreviewColumn(startX + columnWidth * 2 + 24, 4, forceOffset, elementWidth, yButtonRow1 - 24 + 5);

		// fill out the remaining gap
		this.left.setX(startX + columnWidth - 20);
		this.left.setWidth(columnWidth + 44);
	}

	private void createControls() {
		// Zoom2D (High Precision)
		double initZoom2D = Optional.ofNullable(this.zoom2D).map(Slider::getLerpedValue).orElse(staticZoom2D);
		this.zoom2D = PresetWidgets.createIntSlider((int) Math.round(initZoom2D), minZoom, maxZoom, RTFTranslationKeys.GUI_SLIDER_ZOOM, (slider, value) -> {
			staticZoom2D = ((Slider) slider).getLerpedValue();
			if (this.preview2D != null) this.preview2D.regenerate();
			return value;
		});
		this.zoom2D.setValue((initZoom2D - 1.0D) / (100.0D - 1.0D));

		// Zoom3D (High Precision)
		double initZoom3D = Optional.ofNullable(this.zoom3D).map(Slider::getLerpedValue).orElse(staticZoom3D);
		this.zoom3D = PresetWidgets.createIntSlider((int) Math.round(initZoom3D), minZoom, maxZoom, RTFTranslationKeys.GUI_SLIDER_ZOOM, (slider, value) -> {
			staticZoom3D = ((Slider) slider).getLerpedValue();
			if (this.preview3D != null) this.preview3D.regenerate();
			return value;
		});
		this.zoom3D.setValue((initZoom3D - 1.0D) / (100.0D - 1.0D));

		this.renderMode2D = PresetWidgets.createCycle(ImmutableList.copyOf(RenderMode.values()), this.renderMode2D != null ? this.renderMode2D.getValue() : staticMode2D, RTFTranslationKeys.GUI_BUTTON_RENDER_MODE, (button, value) -> {
			staticMode2D = value;
			if (this.preview2D != null) this.preview2D.refreshRenderMode(value);
		}, RenderMode::name);

		this.renderMode3D = PresetWidgets.createCycle(ImmutableList.copyOf(RenderMode.values()), this.renderMode3D != null ? this.renderMode3D.getValue() : staticMode3D, RTFTranslationKeys.GUI_BUTTON_RENDER_MODE, (button, value) -> {
			staticMode3D = value;
			if (this.preview3D != null) this.preview3D.refreshRenderMode(value);
		}, RenderMode::name);

		// Seed Text Input
		String currentSeed = this.getInitialSeedText();
		this.seedEdit = new EditBox(Minecraft.getInstance().font, 0, 0, 0, 20, Component.translatable(RTFTranslationKeys.GUI_BUTTON_SEED)) {
			@Override
			public boolean mouseClicked(double mouseX, double mouseY, int button) {
				boolean wasFocused = this.isFocused();
				boolean handled = super.mouseClicked(mouseX, mouseY, button);
				// Highlight text only when clicking to gain focus
				if (handled && !wasFocused) {
					this.setCursorPosition(this.getValue().length());
					this.setHighlightPos(0);
				}
				return handled;
			}
		};
		this.seedEdit.setTextColor(0xFFFFFF);
		this.seedEdit.setHint(Component.translatable(RTFTranslationKeys.GUI_BUTTON_SEED));
		this.seedEdit.setValue(currentSeed);
		this.seedEdit.setResponder((text) -> {
			this.screen.setSeed(text);
			this.regenerate();
		});

		// Randomize Seed Button
		this.seedRandomize = Button.builder(Component.literal("🎲"), (button) -> {
					String newSeed = String.valueOf(WorldOptions.randomSeed());
					this.seedEdit.setValue(newSeed);
					this.seedEdit.moveCursorToStart(false);
				})
				.tooltip(Tooltip.create(Component.translatable(RTFTranslationKeys.GUI_BUTTON_RANDOMIZE_SEED)))
				.bounds(0, 0, 20, 20)
				.build();
	}

	private String getInitialSeedText() {
		return this.screen.getSeed();
	}

	private void initLeftPreviewColumn(int columnX, int padding, int offset, int width, int yBase) {
		int x = columnX + padding + offset;

		// Viewport, registered first so that dropdowns display over the top
		this.preview2D = new Preview2D(this, x, yBase + 48, width, width);
		this.preview2D.regenerate();
		this.screen.addWidgetToScreen(this.preview2D);

		// Controls
		this.zoom2D.setX(x);
		this.zoom2D.setY(yBase);
		this.zoom2D.setWidth(width);
		this.zoom2D.setHeight(20);
		this.screen.addWidgetToScreen(this.zoom2D);

		this.renderMode2D.setX(x);
		this.renderMode2D.setY(yBase + 24);
		this.renderMode2D.setWidth(width);
		this.renderMode2D.setHeight(20);
		this.screen.addWidgetToScreen(this.renderMode2D);
	}

	private void initRightPreviewColumn(int columnX, int padding, int offset, int width, int yBase) {
		int x = columnX + padding + offset;

		// Viewport, registered first so that dropdowns display over the top
		int y3D = yBase + 48 + 24;
		this.preview3D = new Preview3D(this, x, y3D, width, width);
		this.preview3D.regenerate();
		this.screen.addWidgetToScreen(this.preview3D);

		// Seed Input Controls: Edit box on the left, randomize button on the right
		int buttonWidth = 20;
		int gap = 4;
		int editWidth = width - buttonWidth - gap;

		this.seedEdit.setX(x);
		this.seedEdit.setY(yBase);
		this.seedEdit.setWidth(editWidth);
		this.seedEdit.setHeight(20);

		// Resets cursor & clears selection highlight without selecting text
		this.seedEdit.moveCursorToStart(false);
		this.screen.addWidgetToScreen(this.seedEdit);

		this.seedRandomize.setX(x + editWidth + gap);
		this.seedRandomize.setY(yBase);
		this.seedRandomize.setWidth(buttonWidth);
		this.seedRandomize.setHeight(20);
		this.screen.addWidgetToScreen(this.seedRandomize);

		// Controls
		this.zoom3D.setX(x);
		this.zoom3D.setY(yBase + 24);
		this.zoom3D.setWidth(width);
		this.zoom3D.setHeight(20);
		this.screen.addWidgetToScreen(this.zoom3D);

		this.renderMode3D.setX(x);
		this.renderMode3D.setY(yBase + 48);
		this.renderMode3D.setWidth(width);
		this.renderMode3D.setHeight(20);
		this.screen.addWidgetToScreen(this.renderMode3D);
	}

	private void cleanupWidgets() {
		if (this.zoom2D != null) this.screen.removeWidgetFromScreen(this.zoom2D);
		if (this.zoom3D != null) this.screen.removeWidgetFromScreen(this.zoom3D);
		if (this.renderMode2D != null) this.screen.removeWidgetFromScreen(this.renderMode2D);
		if (this.renderMode3D != null) this.screen.removeWidgetFromScreen(this.renderMode3D);
		if (this.seedEdit != null) this.screen.removeWidgetFromScreen(this.seedEdit);
		if (this.seedRandomize != null) this.screen.removeWidgetFromScreen(this.seedRandomize);

		if (this.preview3D != null) {
			this.screen.removeWidgetFromScreen(this.preview3D);
			try { this.preview3D.close(); } catch (Exception e) { e.printStackTrace(); }
			this.preview3D = null;
		}
		if (this.preview2D != null) {
			this.screen.removeWidgetFromScreen(this.preview2D);
			try { this.preview2D.close(); } catch (Exception e) { e.printStackTrace(); }
			this.preview2D = null;
		}
	}

	@Override
	public void onCancel() {
		super.onCancel();
		try {
			if (this.preview3D != null) this.preview3D.close();
			if (this.preview2D != null) this.preview2D.close();
		} catch (Exception e) { e.printStackTrace(); }
		this.preview3D = null;
		this.preview2D = null;
	}

	@Override
	public void onSave() {
		super.onSave();
		try {
			this.screen.applyPreset(this.preset);
			this.preset.save();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
