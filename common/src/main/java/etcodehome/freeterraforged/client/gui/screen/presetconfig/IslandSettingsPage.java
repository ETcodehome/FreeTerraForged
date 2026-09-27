package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.Optional;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager.PM;
import net.minecraft.network.chat.Component;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import etcodehome.freeterraforged.data.worldgen.preset.settings.IslandSettings;

public class IslandSettingsPage extends PresetEditorPage {

	public IslandSettingsPage(PresetConfigScreen screen) { super(screen); }

	@Override
	public Component title() { return Component.translatable(FTFTranslationKeys.GUI_ISLAND_SETTINGS_TITLE);	}
	
	@Override
	public void init() {
		super.init();

		IslandSettings s = PM.islandSettings;

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_ISLAND));
		this.left.addWidget(PresetWidgets.createToggle(s.spawnIslands, IslandSettings.spawnIslandsSetting, val -> s.spawnIslands = val, this::regenerate));

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_ISLAND_CHANCES));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.density, IslandSettings.densitySetting, val -> s.density = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.macroDensityPercentage, IslandSettings.macroDensityPercentageSetting, val -> s.macroDensityPercentage = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.volcanoChance, IslandSettings.volcanoChanceSetting, val -> s.volcanoChance = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.mountainChance, IslandSettings.mountainChanceSetting, val -> s.mountainChance = val, this::regenerate));

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_ISLAND_SCALES));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.size, IslandSettings.sizeSetting, val -> s.size = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.height, IslandSettings.heightSetting, val -> s.height = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.baseScale, IslandSettings.baseScaleSetting, val -> s.baseScale = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.horizontalScale, IslandSettings.horizontalScaleSetting, val -> s.horizontalScale = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.verticalScale, IslandSettings.verticalScaleSetting, val -> s.verticalScale = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.mountainScale, IslandSettings.mountainScaleSetting, val -> s.mountainScale = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.volcanismScale, IslandSettings.volcanismScaleSetting, val -> s.volcanismScale = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.mountainHorizontalScale, IslandSettings.mountainHorizontalScaleSetting, val -> s.mountainHorizontalScale = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.volcanismHorizontalScale, IslandSettings.volcanismHorizontalScaleSetting, val -> s.volcanismHorizontalScale = val, this::regenerate));

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_ISLAND_TRANSITIONS));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.offshoreDepth, IslandSettings.offshoreDepthSetting, val -> s.offshoreDepth = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.beachWidth, IslandSettings.beachWidthSetting, val -> s.beachWidth = val, this::regenerate));
		this.left.addWidget(PresetWidgets.createFloatSlider(s.beachCoverage, IslandSettings.beachCoverageSetting, val -> s.beachCoverage = val, this::regenerate));
	}

	@Override
	public Optional<Page> previous() { return Optional.of(new RiverSettingsPage(this.screen)); }

	@Override
	public Optional<Page> next() { return Optional.of(new FilterSettingsPage(this.screen));	}
}