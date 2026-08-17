package raccoonman.reterraforged.world.worldgen.structure;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.RandomState;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;

public final class OceanMonumentSeaLevel {
	private static final int NOT_RTF = Integer.MIN_VALUE;

	private OceanMonumentSeaLevel() {
	}

	public static int configured(WorldGenLevel level) {
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		if ((Object) randomState instanceof RTFRandomState rtfRandomState
			&& rtfRandomState.generatorContext() != null) {
			Preset preset = rtfRandomState.preset();
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
