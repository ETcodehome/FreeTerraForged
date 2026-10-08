package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.Optional;

import etcodehome.freeterraforged.client.gui.widget.Slider;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import etcodehome.freeterraforged.data.worldgen.preset.settings.MiscellaneousSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.SurfaceSettings;

public class SurfaceSettingsPage extends PresetEditorPage {
	private Slider rockVariance;
	private Slider rockMin;
	private Slider dirtVariance;
	private Slider dirtMin;
	private CycleButton<Boolean> erosionDecorator;
	private Slider rockSteepness;
	private Slider screeSteepness;
	private Slider dirtSteepness;
	
	public SurfaceSettingsPage(PresetConfigScreen screen) {
		super(screen);
	}
	
	@Override
	public Component title() {
		return Component.translatable(FTFTranslationKeys.GUI_SURFACE_SETTINGS_TITLE);
	}

	@Override
	public void init() {
		super.init();
		
		Preset preset = this.preset.getPreset();
		SurfaceSettings surface = preset.surface();
		SurfaceSettings.Scree scree = surface.scree();
		MiscellaneousSettings miscellaneous = preset.miscellaneous();

		// Erosion Decorator

		this.erosionDecorator = PresetWidgets.createToggle(miscellaneous.erosionDecorator, FTFTranslationKeys.GUI_BUTTON_EROSION_DECORATOR, (button, value) -> {
			miscellaneous.erosionDecorator = value;
			this.rockSteepness.active = value;
			this.screeSteepness.active = value;
			this.dirtSteepness.active = value;
			this.rockMin.active = value;
			this.rockVariance.active = value;
			this.dirtMin.active = value;
			this.dirtVariance.active = value;
		});
		this.rockSteepness = PresetWidgets.createFloatSlider(scree.rockSteepness, 0.0F, 3.0F, FTFTranslationKeys.GUI_SLIDER_ROCK_STEEPNESS, (slider, value) -> {
			value = Math.max(value, this.screeSteepness.getValue());
			scree.rockSteepness = (float) slider.scaleValue(value);
			return value;
		});
		this.screeSteepness = PresetWidgets.createFloatSlider(scree.screeSteepness, 0.0F, 3.0F, FTFTranslationKeys.GUI_SLIDER_SCREE_STEEPNESS, (slider, value) -> {
			value = Math.max(value, this.dirtSteepness.getValue());
			value = Math.min(value, this.rockSteepness.getValue());
			scree.screeSteepness = (float) slider.scaleValue(value);
			return value;
		});
		this.dirtSteepness = PresetWidgets.createFloatSlider(scree.dirtSteepness, 0.0F, 3.0F, FTFTranslationKeys.GUI_SLIDER_DIRT_STEEPNESS, (slider, value) -> {
			value = Math.min(value, this.screeSteepness.getValue());
			scree.dirtSteepness = (float) slider.scaleValue(value);
			return value;
		});

		// Transitions Decorator

		this.rockVariance = PresetWidgets.createIntSlider(scree.rockVariance, 0, 256, FTFTranslationKeys.GUI_SLIDER_ROCK_VARIANCE, (slider, value) -> {
			scree.rockVariance = (int) slider.scaleValue(value);
			return value;
		});
		this.rockMin = PresetWidgets.createIntSlider(scree.rockMin, -1024, 1024, FTFTranslationKeys.GUI_SLIDER_ROCK_MIN, (slider, value) -> {
			value = Math.max(value, this.dirtMin.getValue());
			scree.rockMin = (int) slider.scaleValue(value);
			return value;
		});
		this.dirtVariance = PresetWidgets.createIntSlider(scree.dirtVariance, 0, 256, FTFTranslationKeys.GUI_SLIDER_DIRT_VARIANCE, (slider, value) -> {
			scree.dirtVariance = (int) slider.scaleValue(value);
			return value;
		});
		this.dirtMin = PresetWidgets.createIntSlider(scree.dirtMin, -1024, 1024, FTFTranslationKeys.GUI_SLIDER_DIRT_MIN, (slider, value) -> {
			value = Math.min(value, this.rockMin.getValue());
			scree.dirtMin = (int) slider.scaleValue(value);
			return value;
		});

		// Add the widgets to the layout

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_EROSION_DECORATOR));
		this.left.addWidget(this.erosionDecorator);

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_SCREE_THRESHOLDS));
		this.left.addWidget(this.rockSteepness);
		this.left.addWidget(this.screeSteepness);
		this.left.addWidget(this.dirtSteepness);

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_TRANSITIONS));
		this.left.addWidget(this.rockVariance);
		this.left.addWidget(this.rockMin);
		this.left.addWidget(this.dirtVariance);
		this.left.addWidget(this.dirtMin);
	}

	@Override
	public Optional<Page> previous() {
		return Optional.of(new WorldSettingsPage(this.screen));
	}

	@Override
	public Optional<Page> next() {
		return Optional.of(new UndergroundSettingsPage(this.screen));
	}
}
