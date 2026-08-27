package raccoonman.reterraforged.client.gui.screen.presetconfig;

import raccoonman.reterraforged.data.worldgen.preset.settings.ClimateSettings;

final class PresetSettingsBounds {
	private static final int MAX_UNDERGROUND_BIOME_VERTICAL_SIZE = 512;

	private PresetSettingsBounds() {
	}

	static int maximumUndergroundBiomeVerticalSize(int worldHeight, int worldDepth) {
		return Math.min(
			MAX_UNDERGROUND_BIOME_VERTICAL_SIZE,
			ClimateSettings.BiomeShape.maximumUndergroundVerticalSize(worldHeight, worldDepth)
		);
	}
}
