package etcodehome.freeterraforged.world.worldgen.densityfunction;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

public final class FTFDensityFunctionScanner {
	private static final String FTF_PACKAGE = "etcodehome.freeterraforged.";

	private FTFDensityFunctionScanner() {
	}

	public static boolean usesFTFTerrain(@Nullable NoiseRouter router, @Nullable DensityFunction... extraFunctions) {
		Detector detector = new Detector();

		try {
			if(router != null) {
				router.mapAll(detector);
			}

			if(extraFunctions != null) {
				for(DensityFunction function : extraFunctions) {
					if(function != null) {
						function.mapAll(detector);
					}
				}
			}
		} catch (Throwable t) {
			return true;
		}

		return detector.found;
	}

	public static boolean isFTFFunction(@Nullable DensityFunction function) {
		return function != null && function.getClass().getName().startsWith(FTF_PACKAGE);
	}

	private static final class Detector implements DensityFunction.Visitor {
		private boolean found;

		@Override
		public DensityFunction apply(DensityFunction function) {
			if(!this.found && isFTFFunction(function)) {
				this.found = true;
			}
			return function;
		}

		@Override
		public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noiseHolder) {
			return noiseHolder;
		}
	}
}
