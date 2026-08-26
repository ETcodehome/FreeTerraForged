package raccoonman.reterraforged.world.worldgen.biome;

import net.minecraft.world.level.biome.Climate;

/**
 * Carries the active climate sampler into TerraBlender's parameter-list selection.
 *
 * <p>TerraBlender resolves positional biomes from a {@link Climate.TargetPoint} and then cancels
 * the enclosing biome-source method. Recording the sampler when that target is created lets the
 * parameter selector apply terrain-aware surface protection before the cancellation, without
 * depending on TerraBlender's injection order or on any outer biome-provider implementation.</p>
 */
public final class UndergroundBiomeSurfaceQuery {
	private static final ThreadLocal<State> CURRENT = ThreadLocal.withInitial(State::new);

	private UndergroundBiomeSurfaceQuery() {
	}

	public static void record(
		Climate.Sampler sampler,
		Climate.TargetPoint target,
		int quartX,
		int quartY,
		int quartZ
	) {
		State state = CURRENT.get();
		state.sampler = sampler;
		state.target = target;
		state.quartX = quartX;
		state.quartY = quartY;
		state.quartZ = quartZ;
	}

	public static float coverageFactor(
		Climate.TargetPoint target,
		int quartX,
		int quartY,
		int quartZ
	) {
		State state = CURRENT.get();
		if (state.sampler == null
			|| state.target != target
			|| state.quartX != quartX
			|| state.quartY != quartY
			|| state.quartZ != quartZ) {
			return 1.0F;
		}
		return UndergroundBiomeSurfaceProtection.coverageFactor(
			state.sampler, target, quartX, quartY, quartZ
		);
	}

	private static final class State {
		private Climate.Sampler sampler;
		private Climate.TargetPoint target;
		private int quartX;
		private int quartY;
		private int quartZ;
	}
}
