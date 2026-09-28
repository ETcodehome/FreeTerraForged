package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.Optional;

import com.google.common.collect.ImmutableList;

import etcodehome.freeterraforged.client.gui.widget.Slider;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;
import etcodehome.freeterraforged.world.worldgen.noise.function.DistanceFunction;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;

public class WorldSettingsPage extends PresetEditorPage {
	private CycleButton<ContinentType> continentType;
	private CycleButton<DistanceFunction> continentShape;
	private Slider continentScale;
	private Slider continentJitter;
	private Slider continentSkipping;
	private Slider continentSizeVariance;
	private Slider continentNoiseOctaves;
	private Slider continentNoiseGain;
	private Slider continentNoiseLacunarity;

	private Slider islandInland;
	private Slider islandCoast;
	private Slider deepOcean;
	private Slider shallowOcean;
	private Slider beach;
	private Slider coast;
	private Slider inland;
	
	public CycleButton<SpawnType> spawnType;
	private Slider worldHeight;
	private Slider worldDepth;
	private Slider oceanDepth;
	private Slider seaLevel;
	private Slider lavaLevel;
	
	public WorldSettingsPage(PresetConfigScreen screen) {
		super(screen);
	}
	
	@Override
	public Component title() {
		return Component.translatable(FTFTranslationKeys.GUI_WORLD_SETTINGS_TITLE);
	}

	@Override
	public void init() {
		super.init();

		WorldSettings world = PresetManager.PM.worldSettings;
		WorldSettings.Continent continent = world.continent;
		WorldSettings.ControlPoints controlPoints = world.controlPoints;
		WorldSettings.Properties properties = world.properties;
		
		this.continentType = PresetWidgets.createCycle(
			ImmutableList.of(
				ContinentType.MULTI,
				ContinentType.SINGLE,
				ContinentType.MULTI_IMPROVED,
				ContinentType.UPLIFT
			),
			continent.continentType, FTFTranslationKeys.GUI_BUTTON_CONTINENT_TYPE,
			(button, value) -> {
				continent.continentType = value;
				this.applyContinentType(value);
				this.regenerate();
			}, 
			ContinentType::name
		);
		this.continentShape = PresetWidgets.createCycle(DistanceFunction.values(), continent.continentShape, FTFTranslationKeys.GUI_BUTTON_CONTINENT_SHAPE, (button, value) -> {
			continent.continentShape = value;
			this.regenerate();
		});
		this.continentScale = PresetWidgets.createIntSlider(continent.continentScale, 100, 10000, FTFTranslationKeys.GUI_SLIDER_CONTINENT_SCALE, (slider, value) -> {
			continent.continentScale = (int) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.continentJitter = PresetWidgets.createFloatSlider(continent.continentJitter, 0.5F, 1.0F, FTFTranslationKeys.GUI_SLIDER_CONTINENT_JITTER, (slider, value) -> {
			continent.continentJitter = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.continentSkipping = PresetWidgets.createFloatSlider(continent.continentSkipping, 0.0F, 1.0F, FTFTranslationKeys.GUI_SLIDER_CONTINENT_SKIPPING, (slider, value) -> {
			continent.continentSkipping = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.continentSizeVariance = PresetWidgets.createFloatSlider(continent.continentSizeVariance, 0.0F, 0.75F, FTFTranslationKeys.GUI_SLIDER_CONTINENT_SIZE_VARIANCE, (slider, value) -> {
			continent.continentSizeVariance = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.continentNoiseOctaves = PresetWidgets.createIntSlider(continent.continentNoiseOctaves, 1, 5, FTFTranslationKeys.GUI_SLIDER_CONTINENT_NOISE_OCTAVES, (slider, value) -> {
			continent.continentNoiseOctaves = (int) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.continentNoiseGain = PresetWidgets.createFloatSlider(continent.continentNoiseGain, 0.0F, 0.5F, FTFTranslationKeys.GUI_SLIDER_CONTINENT_NOISE_GAIN, (slider, value) -> {
			continent.continentNoiseGain = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.continentNoiseLacunarity = PresetWidgets.createFloatSlider(continent.continentNoiseLacunarity, 1.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_CONTINENT_NOISE_LACUNARITY, (slider, value) -> {
			continent.continentNoiseLacunarity = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		
		this.applyContinentType(this.continentType.getValue());

		this.islandInland = PresetWidgets.createFloatSlider(controlPoints.islandInland, 0.0F, 1.0F, FTFTranslationKeys.GUI_SLIDER_ISLAND_INLAND, (slider, value) -> {
			value = Math.min(value, this.islandCoast.getValue());
			controlPoints.islandInland = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.islandCoast = PresetWidgets.createFloatSlider(controlPoints.islandCoast, 0.0F, 1.0F, FTFTranslationKeys.GUI_SLIDER_ISLAND_COAST, (slider, value) -> {
			value = Math.min(value, this.deepOcean.getValue());
			controlPoints.islandCoast = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.deepOcean = PresetWidgets.createFloatSlider(controlPoints.deepOcean, 0.0F, 1.0F, FTFTranslationKeys.GUI_SLIDER_DEEP_OCEAN, (slider, value) -> {
			value = Mth.clamp(value, this.islandCoast.getValue(), this.shallowOcean.getValue());
			controlPoints.deepOcean = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.shallowOcean = PresetWidgets.createFloatSlider(controlPoints.shallowOcean, 0.0F, 1.0F, FTFTranslationKeys.GUI_SLIDER_SHALLOW_OCEAN, (slider, value) -> {
			value = Mth.clamp(value, this.deepOcean.getValue(), this.beach.getValue());
			controlPoints.shallowOcean = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.beach = PresetWidgets.createFloatSlider(controlPoints.beach, 0.0F, 1.0F, FTFTranslationKeys.GUI_SLIDER_BEACH, (slider, value) -> {
			value = Mth.clamp(value, this.shallowOcean.getValue(), this.coast.getValue());
			controlPoints.beach = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.coast = PresetWidgets.createFloatSlider(controlPoints.coast, 0.0F, 1.0F, FTFTranslationKeys.GUI_SLIDER_COAST, (slider, value) -> {
			value = Mth.clamp(value, this.beach.getValue(), this.inland.getValue());
			controlPoints.coast = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.inland = PresetWidgets.createFloatSlider(controlPoints.inland, 0.0F, 1.0F, FTFTranslationKeys.GUI_SLIDER_INLAND, (slider, value) -> {
			value = Math.max(value, this.coast.getValue());
			value = Math.max(value, 0.001);
			controlPoints.inland = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.spawnType = PresetWidgets.createCycle(SpawnType.values(), properties.spawnType, FTFTranslationKeys.GUI_BUTTON_SPAWN_TYPE, (button, value) -> {
			properties.spawnType = value;
			this.regenerate();
		});
		this.worldHeight = PresetWidgets.createIntSlider(properties.worldHeight, 0, 1024, FTFTranslationKeys.GUI_SLIDER_WORLD_HEIGHT, (slider, value) -> {
			int nearestMultiple = Math.max(getNearestMultiple(slider, (float) value, 16), 16);
			properties.worldHeight = nearestMultiple;
			this.updateUndergroundBiomeVerticalSize(properties);
			this.regenerate();
			return slider.getSliderValue(nearestMultiple);
		});
		this.worldDepth = PresetWidgets.createIntSlider(properties.worldDepth, 0, 1024, FTFTranslationKeys.GUI_SLIDER_WORLD_DEPTH, (slider, value) -> {
			int nearestMultiple = getNearestMultiple(slider, (float) value, 16);
			properties.worldDepth = nearestMultiple;
			this.updateOceanDepthRange(properties);
			this.updateUndergroundBiomeVerticalSize(properties);
			return slider.getSliderValue(nearestMultiple);
		});
		this.oceanDepth = PresetWidgets.createIntSlider(properties.oceanDepth, 10, properties.seaLevel + properties.worldDepth - 10, FTFTranslationKeys.GUI_SLIDER_OCEAN_DEPTH, (slider, value) -> {
			int depth = (int) slider.scaleValue(value);
			properties.oceanDepth = depth;
			this.regenerate();
			return value;
		});
		this.seaLevel = PresetWidgets.createIntSlider(properties.seaLevel, 0, 255, FTFTranslationKeys.GUI_SLIDER_SEA_LEVEL, (slider, value) -> {
			properties.seaLevel = (int) slider.scaleValue(value);
			this.updateOceanDepthRange(properties);
			this.regenerate();
			return value;
		});
		this.lavaLevel = PresetWidgets.createIntSlider(properties.lavaLevel, -1024, 128, FTFTranslationKeys.GUI_SLIDER_LAVA_LEVEL, (slider, value) -> {
			properties.lavaLevel = (int) slider.scaleValue(value);
			return value;
		});
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_CONTINENT));
		this.left.addWidget(this.continentType);
		this.left.addWidget(this.continentShape);
		this.left.addWidget(this.continentScale);
		this.left.addWidget(this.continentJitter);
		this.left.addWidget(this.continentSkipping);
		this.left.addWidget(this.continentSizeVariance);
		this.left.addWidget(this.continentNoiseOctaves);
		this.left.addWidget(this.continentNoiseGain);
		this.left.addWidget(this.continentNoiseLacunarity);

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_CONTROL_POINTS));
		this.left.addWidget(this.islandInland);
		this.left.addWidget(this.islandCoast);
		this.left.addWidget(this.deepOcean);
		this.left.addWidget(this.shallowOcean);
		this.left.addWidget(this.beach);
		this.left.addWidget(this.coast);
		this.left.addWidget(this.inland);
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_PROPERTIES));
		this.left.addWidget(this.spawnType);
		this.left.addWidget(this.worldHeight);
		this.left.addWidget(this.worldDepth);
		this.left.addWidget(this.oceanDepth);
		this.left.addWidget(this.seaLevel);
		this.left.addWidget(this.lavaLevel);
	}

	@Override
	public Optional<Page> previous() { return Optional.empty();	}

	@Override
	public Optional<Page> next() {
		return Optional.of(new SurfaceSettingsPage(this.screen));
	}
	
	private void applyContinentType(ContinentType type) {
		this.continentShape.active = type == ContinentType.MULTI || type == ContinentType.SINGLE;
		
		boolean isMultiImproved = type == ContinentType.MULTI_IMPROVED || type == ContinentType.UPLIFT;
		this.continentSkipping.active = isMultiImproved;
		this.continentSizeVariance.active = isMultiImproved;
		this.continentNoiseOctaves.active = isMultiImproved;
		this.continentNoiseGain.active = isMultiImproved;
		this.continentNoiseLacunarity.active = isMultiImproved;

		// ensures that terrains never drop below continental uplift resulting in floating rivers
		boolean isUpliftContinent = type == ContinentType.UPLIFT;
		TerrainSettings t = PresetManager.PM.terrainSettings;
		if(isUpliftContinent){
			t.general.globalVerticalScale = Math.max(t.general.globalVerticalScale, 1.0F);
			t.steppe.baseScale = Math.max(t.steppe.baseScale, 2.0F);
			t.plains.baseScale = Math.max(t.plains.baseScale, 2.0F);
			t.hills.baseScale = Math.max(t.hills.baseScale, 1.0F);
			t.dales.baseScale = Math.max(t.dales.baseScale, 1.0F);
			t.badlands.baseScale = Math.max(t.badlands.baseScale, 1.0F);
			t.mountains.baseScale = Math.max(t.mountains.baseScale, 1.0F);
			t.plateau.baseScale = Math.max(t.plateau.baseScale, 1.0F);
			t.volcano.baseScale = Math.max(t.volcano.baseScale, 1.0F);
		}
	}
	
	private void updateOceanDepthRange(WorldSettings.Properties properties) {
		this.oceanDepth.setRange(10, properties.seaLevel + properties.worldDepth - 10);
		properties.oceanDepth = (int) this.oceanDepth.scaleValue((float) this.oceanDepth.getValue());
	}

	private void updateUndergroundBiomeVerticalSize(WorldSettings.Properties properties) {
		ClimateSettings.BiomeShape biomeShape = PresetManager.PM.climateSettings.biomeShape;
		biomeShape.undergroundBiomeVerticalSize = Math.min(
			biomeShape.undergroundBiomeVerticalSize,
			PresetSettingsBounds.maximumUndergroundBiomeVerticalSize(properties.worldHeight, properties.worldDepth)
		);
	}

	private static int getNearestMultiple(Slider slider, float value, int multiple)  {
		int lerpedValue = (int) slider.lerpValue(value);
		int lerpedMultiple = (int) slider.lerpValue((float) slider.getSliderValue(multiple));
		return lerpedValue / lerpedMultiple * lerpedMultiple;
	}
}
