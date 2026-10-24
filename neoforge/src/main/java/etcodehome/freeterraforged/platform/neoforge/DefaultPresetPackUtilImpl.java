package etcodehome.freeterraforged.platform.neoforge;

import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;

public final class DefaultPresetPackUtilImpl {
	private DefaultPresetPackUtilImpl() {
	}

	public static String defaultPresetPackId() {
		return "mod/" + FTFWorldPresets.DEFAULT_PRESET_PACK_ID;
	}
}
