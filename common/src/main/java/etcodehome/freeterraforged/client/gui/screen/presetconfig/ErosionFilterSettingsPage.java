package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.Optional;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import net.minecraft.network.chat.Component;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ErosionFilterSettings;

class ErosionFilterSettingsPage extends PresetEditorPage {

	public ErosionFilterSettingsPage(PresetConfigScreen screen) {
		super(screen);
	}

	@Override
	public void init() {
		super.init();

		ErosionFilterSettings s = PresetManager.PM.erosionFilterSettings;

		this.left.addWidget(PresetWidgets.createFloatSlider(s.scale, ErosionFilterSettings.scaleSetting, val -> s.scale = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.strengthMultiplier, ErosionFilterSettings.strengthMultiplierSetting, val -> s.strengthMultiplier = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.gullySharpness, ErosionFilterSettings.gullySharpnessSetting, val -> s.gullySharpness = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.gullySlopeAdhesion, ErosionFilterSettings.gullySlopeAdhesionSetting, val -> s.gullySlopeAdhesion = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createIntSlider(s.flowOctaves, ErosionFilterSettings.flowOctavesSetting, val -> s.flowOctaves = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.lacunarity, ErosionFilterSettings.lacunaritySetting, val -> s.lacunarity = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.gain, ErosionFilterSettings.gainSetting, val -> s.gain = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.phacelleScale, ErosionFilterSettings.phacelleScaleSetting, val -> s.phacelleScale = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.phacelleOffset, ErosionFilterSettings.phacelleOffsetSetting, val -> s.phacelleOffset = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.phacelleNormalization, ErosionFilterSettings.phacelleNormalizationSetting, val -> s.phacelleNormalization = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.slopeOnsetBase, ErosionFilterSettings.slopeOnsetBaseSetting, val -> s.slopeOnsetBase = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.slopeOnsetRidge, ErosionFilterSettings.slopeOnsetRidgeSetting, val -> s.slopeOnsetRidge = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.slopeOnsetOctave, ErosionFilterSettings.slopeOnsetOctaveSetting, val -> s.slopeOnsetOctave = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.roundingMin, ErosionFilterSettings.roundingMinSetting, val -> s.roundingMin = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.roundingMax, ErosionFilterSettings.roundingMaxSetting, val -> s.roundingMax = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.roundingDecay, ErosionFilterSettings.roundingDecaySetting, val -> s.roundingDecay = val, this::regenerate));

	}
		
	@Override
	public Component title() {
		return Component.translatable(FTFTranslationKeys.GUI_FILTER_SETTINGS_TITLE);
	}

	@Override
	public Optional<Page> previous() {
		return Optional.of(new IslandSettingsPage(this.screen));
	}

	@Override
	public Optional<Page> next() {
		return Optional.of(new MiscellaneousPage(this.screen));
	}

}
