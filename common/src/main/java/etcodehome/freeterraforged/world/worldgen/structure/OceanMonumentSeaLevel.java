package etcodehome.freeterraforged.world.worldgen.structure;

import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.RandomState;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;

public final class OceanMonumentSeaLevel {
	private static final int NOT_RTF = Integer.MIN_VALUE;

	private OceanMonumentSeaLevel() {
	}

	public static int configured(WorldGenLevel level) {
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		if ((Object) randomState instanceof FTFRandomState ftfRandomState
			&& ftfRandomState.generatorContext() != null) {
			Preset preset = ftfRandomState.preset();
			if (preset != null) {
				return preset.world().properties.seaLevel;
			}
		}
		return NOT_RTF;
	}

	public static int effective(WorldGenLevel level) {
		int configured = configured(level);
		return configured == NOT_RTF ? level.getSeaLevel() : configured;
	}
}
