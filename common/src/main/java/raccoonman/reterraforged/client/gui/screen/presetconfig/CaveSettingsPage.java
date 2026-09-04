package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Optional;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.client.gui.widget.Slider;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public class CaveSettingsPage extends PresetEditorPage {
	private Slider entranceCaveProbability;
	private Slider cheeseCaveDepthOffset;
	private Slider cheeseCaveProbability;
	private Slider spaghettiProbability;
	private Slider noodleCaveProbability;
	private Slider ravineProbability;
	private CycleButton<Boolean> largeOreVeins;
	
	public CaveSettingsPage(PresetConfigScreen screen, PresetEntry preset) {
		super(screen, preset);
	}

	@Override
	public Component title() {
		return Component.translatable(RTFTranslationKeys.GUI_CAVE_SETTINGS_TITLE);
	}

	@Override
	public void init() {
		super.init();

		Preset preset = this.preset.getPreset();
		CaveSettings caves = preset.caves();

		this.entranceCaveProbability = PresetWidgets.createFloatSlider(caves.entranceCaveProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ENTRANCE_CAVE_PROBABILITY, (slider, value) -> {
			caves.entranceCaveProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.cheeseCaveDepthOffset = PresetWidgets.createFloatSlider(caves.cheeseCaveDepthOffset, 1.5625F, 10.0F, RTFTranslationKeys.GUI_SLIDER_CHEESE_CAVE_DEPTH_OFFSET, (slider, value) -> {
			caves.cheeseCaveDepthOffset = (float) slider.scaleValue(value);
			return value;
		});
		this.cheeseCaveProbability = PresetWidgets.createFloatSlider(caves.cheeseCaveProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_CHEESE_CAVE_PROBABILITY, (slider, value) -> {
			caves.cheeseCaveProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.spaghettiProbability = PresetWidgets.createFloatSlider(caves.spaghettiCaveProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_SPAGHETTI_CAVE_PROBABILITY, (slider, value) -> {
			caves.spaghettiCaveProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.noodleCaveProbability = PresetWidgets.createFloatSlider(caves.noodleCaveProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_NOODLE_CAVE_PROBABILITY, (slider, value) -> {
			caves.noodleCaveProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.ravineProbability = PresetWidgets.createFloatSlider(caves.ravineCarverProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_RAVINE_CARVER_PROBABILITY, (slider, value) -> {
			caves.ravineCarverProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.largeOreVeins = PresetWidgets.createToggle(caves.largeOreVeins, RTFTranslationKeys.GUI_BUTTON_LARGE_ORE_VEINS, (button, value) -> {
			caves.largeOreVeins = value;
		});

		this.left.addWidget(PresetWidgets.createLabel(RTFTranslationKeys.GUI_LABEL_NOISE_CAVES));
		this.left.addWidget(this.entranceCaveProbability);
		this.left.addWidget(this.cheeseCaveDepthOffset);
		this.left.addWidget(this.cheeseCaveProbability);
		this.left.addWidget(this.spaghettiProbability);
		this.left.addWidget(this.noodleCaveProbability);

		this.left.addWidget(PresetWidgets.createLabel(RTFTranslationKeys.GUI_LABEL_CARVERS));
		this.left.addWidget(this.ravineProbability);
		this.left.addWidget(this.largeOreVeins);
	}
	
	@Override
	public Optional<Page> previous() {
		return Optional.of(new SurfaceSettingsPage(this.screen, this.preset));
	}

	@Override
	public Optional<Page> next() {
		return Optional.of(new ClimateSettingsPage(this.screen, this.preset));
	}
}
