package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Optional;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.client.gui.widget.Slider;
import raccoonman.reterraforged.data.worldgen.preset.settings.IslandSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public class IslandSettingsPage extends PresetEditorPage {
	private CycleButton<Boolean> enableArchipelago;
	private Slider islandDensity;
	private Slider islandSize;
	private Slider islandHeight;
	private Slider islandBaseScale;
	private Slider islandVerticalScale;
	private Slider islandHorizontalScale;
	private Slider mountainChance;
	private Slider mountainScale;
	private Slider volcanoChance;
	private Slider volcanismScale;
	private Slider offshoreDepth;
	private Slider beachWidth;
	private Slider beachCoverage;

	public IslandSettingsPage(PresetConfigScreen screen, PresetEntry preset) {
		super(screen, preset);
	}

	@Override
	public Component title() {
		return Component.translatable(RTFTranslationKeys.GUI_ISLAND_SETTINGS_TITLE);
	}
	
	@Override
	public void init() {
		super.init();

		Preset preset = this.preset.getPreset();
		IslandSettings island = preset.island();
		
		this.enableArchipelago = PresetWidgets.createToggle(island.enableArchipelago, RTFTranslationKeys.GUI_BUTTON_ENABLE_ARCHIPELAGO, (button, value) -> {
			island.enableArchipelago = value;
			this.regenerate();
		});
		this.islandDensity = PresetWidgets.createFloatSlider(island.islandDensity, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_DENSITY, (slider, value) -> {
			island.islandDensity = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.islandSize = PresetWidgets.createFloatSlider(island.islandSize, 50.0F, 500.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_SIZE, (slider, value) -> {
			island.islandSize = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.islandHeight = PresetWidgets.createFloatSlider(island.islandHeight, 0.1F, 3.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_HEIGHT, (slider, value) -> {
			island.islandHeight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.islandBaseScale = PresetWidgets.createFloatSlider(island.islandBaseScale, 0.1F, 2.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_BASE_SCALE, (slider, value) -> {
			island.islandBaseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.islandVerticalScale = PresetWidgets.createFloatSlider(island.islandVerticalScale, 0.1F, 3.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_VERTICAL_SCALE, (slider, value) -> {
			island.islandVerticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.islandHorizontalScale = PresetWidgets.createFloatSlider(island.islandHorizontalScale, 0.1F, 3.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_HORIZONTAL_SCALE, (slider, value) -> {
			island.islandHorizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.mountainChance = PresetWidgets.createFloatSlider(island.mountainChance, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_MOUNTAIN_CHANCE, (slider, value) -> {
			island.mountainChance = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.mountainScale = PresetWidgets.createFloatSlider(island.volcanoChance, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_MOUNTAIN_SCALE, (slider, value) -> {
			island.mountainScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.volcanoChance = PresetWidgets.createFloatSlider(island.volcanoChance, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_VOLCANO_CHANCE, (slider, value) -> {
			island.volcanoChance = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.volcanismScale = PresetWidgets.createFloatSlider(island.volcanoChance, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_VOLCANISM_SCALE, (slider, value) -> {
			island.volcanismScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.offshoreDepth = PresetWidgets.createFloatSlider(island.offshoreDepth, 0.1F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_OFFSHORE_DEPTH, (slider, value) -> {
			island.offshoreDepth = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.beachWidth = PresetWidgets.createFloatSlider(island.beachWidth, 0.05F, 0.5F, RTFTranslationKeys.GUI_SLIDER_ISLAND_BEACH_WIDTH, (slider, value) -> {
			island.beachWidth = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.beachCoverage = PresetWidgets.createFloatSlider(island.beachCoverage, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_BEACH_COVERAGE, (slider, value) -> {
			island.beachCoverage = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});

		this.left.addWidget(PresetWidgets.createLabel(RTFTranslationKeys.GUI_LABEL_ISLAND));
		this.left.addWidget(this.enableArchipelago);
		this.left.addWidget(this.islandDensity);
		this.left.addWidget(this.islandSize);
		this.left.addWidget(this.islandHeight);
		this.left.addWidget(this.islandBaseScale);
		this.left.addWidget(this.islandVerticalScale);
		this.left.addWidget(this.islandHorizontalScale);
		this.left.addWidget(this.mountainChance);
		this.left.addWidget(this.mountainScale);
		this.left.addWidget(this.volcanoChance);
		this.left.addWidget(this.volcanismScale);
		this.left.addWidget(this.offshoreDepth);
		this.left.addWidget(this.beachWidth);
		this.left.addWidget(this.beachCoverage);
	}

	@Override
	public Optional<Page> previous() {
		return Optional.of(new RiverSettingsPage(this.screen, this.preset));
	}

	@Override
	public Optional<Page> next() {
		return Optional.of(new FilterSettingsPage(this.screen, this.preset));
	}
}