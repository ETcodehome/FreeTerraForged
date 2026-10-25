package etcodehome.freeterraforged.data.worldgen.preset;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;

public final class FTFWorldPresetDataPacks {
	private FTFWorldPresetDataPacks() {
	}

	public static boolean selectsFreeTerraForged(String levelType) {
		return levelType != null && (
			levelType.equalsIgnoreCase(FTFWorldPresets.SERVER_LEVEL_TYPE)
				|| levelType.equalsIgnoreCase(FTFWorldPresets.LEGACY_SERVER_LEVEL_TYPE)
				|| levelType.equalsIgnoreCase(FTFWorldPresets.FREETERRAFORGED.location().toString())
		);
	}

	public static WorldDataConfiguration setDefaultPresetEnabled(
		WorldDataConfiguration configuration,
		boolean enabled
	) {
		DataPackConfig current = configuration.dataPacks();
		DataPackConfig updated = setDefaultPresetEnabled(current, enabled);
		return updated == current ? configuration : new WorldDataConfiguration(updated, configuration.enabledFeatures());
	}

	public static DataPackConfig setDefaultPresetEnabled(DataPackConfig current, boolean enabled) {
		String packId = FTFWorldPresets.defaultPresetPackId();
		List<String> enabledPacks = new ArrayList<>(current.getEnabled());
		List<String> disabledPacks = new ArrayList<>(current.getDisabled());

		if (enabled) {
			if (!enabledPacks.contains(packId)) {
				enabledPacks.add(packId);
			}
			disabledPacks.remove(packId);
		} else {
			enabledPacks.remove(packId);
		}

		if (enabledPacks.equals(current.getEnabled()) && disabledPacks.equals(current.getDisabled())) {
			return current;
		}

		return new DataPackConfig(enabledPacks, disabledPacks);
	}
}
