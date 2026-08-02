package raccoonman.reterraforged.world.worldgen.terrablender;

import java.util.function.IntSupplier;

import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

/**
 * Selects a TerraBlender region without evaluating region noise when only the default region exists.
 */
public final class TerraBlenderRegionSelector {
	private TerraBlenderRegionSelector() {
	}

	public static boolean needsUniqueness(int regionCount) {
		return regionCount > 1;
	}

	public static int select(int maxIndex, double uniqueness, IntSupplier fallback) {
		if (maxIndex <= 0) {
			return 0;
		}
		if (Double.isNaN(uniqueness)) {
			return fallback.getAsInt();
		}
		return NoiseUtil.round(maxIndex * (float) uniqueness);
	}
}
