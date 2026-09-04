package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Optional;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.client.gui.widget.Slider;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.ClimateSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public class UndergroundSettingsPage extends PresetEditorPage {
	private Slider entranceCaveProbability;
	private Slider cheeseCaveDepthOffset;
	private Slider cheeseCaveProbability;
	private Slider spaghettiProbability;
	private Slider noodleCaveProbability;
	private Slider ravineProbability;
	private CycleButton<Boolean> largeOreVeins;

	private Slider undergroundBiomeSize;
	private Slider undergroundBiomeVerticalSize;
	private Slider undergroundBiomeCoverage;
	private Slider undergroundBiomeClimateInfluence;
	private CycleButton<Boolean> undergroundBiomeBanding;
	
	public UndergroundSettingsPage(PresetConfigScreen screen, PresetEntry preset) {
		super(screen, preset);
	}

	@Override
	public Component title() {
		return Component.translatable(RTFTranslationKeys.GUI_UNDERGROUND_SETTINGS_TITLE);
	}

	@Override
	public void init() {
		super.init();

		Preset preset = this.preset.getPreset();
		CaveSettings caves = preset.caves();
		ClimateSettings climate = preset.climate();
		ClimateSettings.BiomeShape biomeShape = climate.biomeShape;

		int maximumUndergroundVerticalSize = PresetSettingsBounds.maximumUndergroundBiomeVerticalSize(
				preset.world().properties.worldHeight,
				preset.world().properties.worldDepth
		);

		biomeShape.undergroundBiomeVerticalSize = Math.min(
				biomeShape.undergroundBiomeVerticalSize,
				maximumUndergroundVerticalSize
		);

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

		this.undergroundBiomeSize = PresetWidgets.createIntSlider(biomeShape.undergroundBiomeSize, ClimateSettings.BiomeShape.MIN_BIOME_SIZE, ClimateSettings.BiomeShape.MAX_BIOME_SIZE, RTFTranslationKeys.GUI_SLIDER_UNDERGROUND_BIOME_SIZE, (slider, value) -> {
			biomeShape.undergroundBiomeSize = (int) slider.scaleValue(value);
			return value;
		});
		this.undergroundBiomeVerticalSize = PresetWidgets.createIntSlider(biomeShape.undergroundBiomeVerticalSize, ClimateSettings.BiomeShape.MIN_UNDERGROUND_VERTICAL_SIZE, maximumUndergroundVerticalSize, RTFTranslationKeys.GUI_SLIDER_UNDERGROUND_BIOME_VERTICAL_SIZE, (slider, value) -> {
			biomeShape.undergroundBiomeVerticalSize = (int) slider.scaleValue(value);
			return value;
		});
		this.undergroundBiomeCoverage = PresetWidgets.createFloatSlider(biomeShape.undergroundBiomeCoverage, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_UNDERGROUND_BIOME_COVERAGE, (slider, value) -> {
			biomeShape.undergroundBiomeCoverage = (float) slider.scaleValue(value);
			return value;
		});
		this.undergroundBiomeClimateInfluence = PresetWidgets.createFloatSlider(biomeShape.undergroundBiomeClimateInfluence, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_UNDERGROUND_BIOME_CLIMATE_INFLUENCE, (slider, value) -> {
			biomeShape.undergroundBiomeClimateInfluence = (float) slider.scaleValue(value);
			return value;
		});
		this.undergroundBiomeBanding = PresetWidgets.createToggle(biomeShape.undergroundBiomeBanding, RTFTranslationKeys.GUI_BUTTON_UNDERGROUND_BIOME_BANDING, (button, value) -> {
			biomeShape.undergroundBiomeBanding = value;
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

		this.left.addWidget(PresetWidgets.createLabel(RTFTranslationKeys.GUI_LABEL_UNDERGROUND_BIOMES));
		this.left.addWidget(this.undergroundBiomeSize);
		this.left.addWidget(this.undergroundBiomeVerticalSize);
		this.left.addWidget(this.undergroundBiomeCoverage);
		this.left.addWidget(this.undergroundBiomeClimateInfluence);
		this.left.addWidget(this.undergroundBiomeBanding);
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
