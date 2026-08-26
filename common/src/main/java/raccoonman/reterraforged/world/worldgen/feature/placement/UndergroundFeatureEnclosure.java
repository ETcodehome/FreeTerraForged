package raccoonman.reterraforged.world.worldgen.feature.placement;

import java.util.Optional;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection;
import raccoonman.reterraforged.world.worldgen.cell.Cell;

/**
 * Keeps rescued underground-feature origins behind the same terrain-relative
 * hard shell used by cave-biome selection.
 *
 * <p>The check is deliberately local rather than a global cave-connectivity
 * test. A cave with an entrance is still eligible once its placement position
 * is four complete blocks behind the exposed terrain envelope. Surface heights
 * are cached independently from candidate Y, so each horizontal column in a
 * rescue run pays for its neighborhood only once.</p>
 */
final class UndergroundFeatureEnclosure {
	static final int BUFFER_BLOCKS = UndergroundBiomeSurfaceProtection.HARD_SHELL_BLOCKS;
	private static final int REQUIRED_SURFACE_DIFFERENCE = BUFFER_BLOCKS + 1;

	private UndergroundFeatureEnclosure() {
	}

	static Optional<Guard> create(PlacementContext context) {
		Object randomState = context.getLevel().getLevel().getChunkSource().randomState();
		if (randomState instanceof RTFRandomState rtfRandomState) {
			GeneratorContext generatorContext = rtfRandomState.generatorContext();
			if (generatorContext != null) {
				return Optional.of(new Guard(generatorContext));
			}
		}
		return Optional.empty();
	}

	static int maximumPlacementY(SurfaceHeight surfaceHeight, int blockX, int blockZ) {
		int minimumSurfaceY = Integer.MAX_VALUE;
		for (int z = blockZ - BUFFER_BLOCKS; z <= blockZ + BUFFER_BLOCKS; z++) {
			for (int x = blockX - BUFFER_BLOCKS; x <= blockX + BUFFER_BLOCKS; x++) {
				minimumSurfaceY = Math.min(minimumSurfaceY, surfaceHeight.sample(x, z));
			}
		}
		return minimumSurfaceY - REQUIRED_SURFACE_DIFFERENCE;
	}

	@FunctionalInterface
	interface SurfaceHeight {
		int sample(int blockX, int blockZ);
	}

	static final class Guard {
		private final GeneratorContext context;
		private final Cell cell = new Cell();
		private final Long2IntOpenHashMap surfaceHeights = new Long2IntOpenHashMap();
		private final Long2IntOpenHashMap maximumPlacementY = new Long2IntOpenHashMap();

		private Guard(GeneratorContext context) {
			this.context = context;
			this.surfaceHeights.defaultReturnValue(Integer.MIN_VALUE);
			this.maximumPlacementY.defaultReturnValue(Integer.MIN_VALUE);
		}

		boolean isProtected(BlockPos placement) {
			long key = key(placement.getX(), placement.getZ());
			int maximumY = this.maximumPlacementY.get(key);
			if (maximumY == Integer.MIN_VALUE) {
				maximumY = UndergroundFeatureEnclosure.maximumPlacementY(
					this::surfaceY,
					placement.getX(),
					placement.getZ()
				);
				this.maximumPlacementY.put(key, maximumY);
			}
			return placement.getY() <= maximumY;
		}

		private int surfaceY(int blockX, int blockZ) {
			long key = key(blockX, blockZ);
			int surfaceY = this.surfaceHeights.get(key);
			if (surfaceY == Integer.MIN_VALUE) {
				surfaceY = UndergroundBiomeSurfaceProtection.sampleSurfaceY(
					this.context,
					this.cell,
					blockX,
					blockZ
				);
				this.surfaceHeights.put(key, surfaceY);
			}
			return surfaceY;
		}
	}

	private static long key(int blockX, int blockZ) {
		return ((long) blockX << 32) ^ (blockZ & 0xFFFFFFFFL);
	}
}
