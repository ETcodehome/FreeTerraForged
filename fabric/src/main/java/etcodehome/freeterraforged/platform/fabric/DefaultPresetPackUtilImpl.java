package etcodehome.freeterraforged.platform.fabric;

import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;

public final class DefaultPresetPackUtilImpl {
	private DefaultPresetPackUtilImpl() {
	}

	public static String defaultPresetPackId() {
		return FTFWorldPresets.DEFAULT_PRESET_PACK_ID.toString();
	}
}
