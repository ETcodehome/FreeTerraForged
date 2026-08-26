package raccoonman.reterraforged.world.worldgen.biome;

import java.util.Arrays;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;

/**
 * Keeps cave-biome ownership behind a complete biome-quart shell around the terrain surface.
 *
 * <p>A local vertical-depth check protects horizontal ground but cannot protect a cliff face: a
 * point may be far below its own column's summit while being immediately beside exterior air. The
 * minimum surface height across the cell's block footprint plus a one-quart border supplies the
 * missing lateral constraint. Requiring one complete quart above that minimum protects the
 * queried 4x4x4 biome cell on its top, sides, and diagonals.</p>
 *
 * <p>The neighborhood height is independent of Y, so it is cached per sampler and horizontal quart
 * coordinate. This avoids multiplying the terrain lookups by every vertical biome cell.</p>
 */
public final class UndergroundBiomeSurfaceProtection {
	public static final int HARD_SHELL_BLOCKS = QuartPos.SIZE;
	public static final int TRANSITION_BLOCKS = 24;
	static final int REQUIRED_CLEARANCE_BLOCKS = QuartPos.SIZE + HARD_SHELL_BLOCKS;

	private static final float DEPTH_UNITS_PER_BLOCK = 1.0F / 128.0F;
	private static final float SURFACE_DEPTH = NoiseRouterData.GLOBAL_OFFSET + 0.5F;
	private static final int CACHE_SIZE = 1024;
	private static final int CACHE_MASK = CACHE_SIZE - 1;
	private static final int SURFACE_CACHE_SIZE = 4096;
	private static final int SURFACE_CACHE_MASK = SURFACE_CACHE_SIZE - 1;
	private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

	private UndergroundBiomeSurfaceProtection() {
	}

	/**
	 * Returns the maximum fraction of configured cave-biome coverage allowed at this quart cell.
	 * Zero is the hard surface shell; one is the fully underground region beyond the transition.
	 */
	public static float coverageFactor(
		Climate.Sampler sampler,
		Climate.TargetPoint target,
		int quartX,
		int quartY,
		int quartZ
	) {
		GeneratorContext context = (Object) sampler instanceof RTFClimateSampler rtfSampler
			? rtfSampler.getUndergroundBiomeSurfaceContext()
			: null;
		if (context == null) {
			float localClearance = (
				Climate.unquantizeCoord(target.depth()) - SURFACE_DEPTH
			) / DEPTH_UNITS_PER_BLOCK;
			return coverageFactor(localClearance);
		}
		float minimumSurfaceY = CACHE.get().minimumSurfaceY(sampler, context, quartX, quartZ);
		float clearance = minimumSurfaceY - QuartPos.toBlock(quartY);
		return coverageFactor(clearance);
	}

	static float coverageFactor(float minimumSurfaceClearanceBlocks) {
		return Math.clamp(
			(minimumSurfaceClearanceBlocks - REQUIRED_CLEARANCE_BLOCKS) / TRANSITION_BLOCKS,
			0.0F,
			1.0F
		);
	}

	static float coverageFactor(
		SurfaceHeight surfaceHeight,
		int quartX,
		int quartY,
		int quartZ
	) {
		float minimumSurfaceY = minimumSurfaceY(surfaceHeight, quartX, quartZ);
		return coverageFactor(minimumSurfaceY - QuartPos.toBlock(quartY));
	}

	private static float minimumSurfaceY(SurfaceHeight surfaceHeight, int quartX, int quartZ) {
		float minimum = Float.POSITIVE_INFINITY;
		int originX = QuartPos.toBlock(quartX);
		int originZ = QuartPos.toBlock(quartZ);
		int minX = originX - HARD_SHELL_BLOCKS;
		int minZ = originZ - HARD_SHELL_BLOCKS;
		int maxX = originX + QuartPos.SIZE - 1 + HARD_SHELL_BLOCKS;
		int maxZ = originZ + QuartPos.SIZE - 1 + HARD_SHELL_BLOCKS;
		for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
			for (int blockX = minX; blockX <= maxX; blockX++) {
				minimum = Math.min(
					minimum,
					surfaceHeight.sample(blockX, blockZ)
				);
			}
		}
		return minimum;
	}

	/**
	 * Samples the stable terrain surface used by underground-biome protection.
	 * Placement code uses the same authority so decoration cannot disagree with
	 * biome selection because a tree or an earlier feature changed a heightmap.
	 */
	public static int sampleSurfaceY(
		GeneratorContext context,
		Cell cell,
		int blockX,
		int blockZ
	) {
		context.lookup.applyCell(
			cell.reset(),
			blockX,
			blockZ,
			false
		);
		return context.levels.scale(cell.height);
	}

	private static long key(int quartX, int quartZ) {
		return ((long) quartX << 32) ^ (quartZ & 0xFFFFFFFFL);
	}

	private static final class Cache {
		private final Slot first = new Slot();
		private final Slot second = new Slot();
		private long stamp;

		private float minimumSurfaceY(
			Climate.Sampler sampler,
			GeneratorContext context,
			int quartX,
			int quartZ
		) {
			Slot slot = this.slotFor(sampler, context);
			long key = key(quartX, quartZ);
			int index = (int) HashCommon.mix(key) & CACHE_MASK;
			if (slot.present[index] && slot.keys[index] == key) {
				return slot.minimumSurfaceY[index];
			}

			Cell cell = new Cell();
			float minimum = UndergroundBiomeSurfaceProtection.minimumSurfaceY(
				(x, z) -> slot.surfaceY(context, cell, x, z),
				quartX,
				quartZ
			);
			slot.present[index] = true;
			slot.keys[index] = key;
			slot.minimumSurfaceY[index] = minimum;
			return minimum;
		}

		private Slot slotFor(Climate.Sampler sampler, GeneratorContext context) {
			this.stamp++;
			if (this.first.sampler == sampler && this.first.context == context) {
				this.first.lastUse = this.stamp;
				return this.first;
			}
			if (this.second.sampler == sampler && this.second.context == context) {
				this.second.lastUse = this.stamp;
				return this.second;
			}
			Slot evicted = this.first.lastUse <= this.second.lastUse ? this.first : this.second;
			evicted.rebind(sampler, context);
			evicted.lastUse = this.stamp;
			return evicted;
		}
	}

	@FunctionalInterface
	interface SurfaceHeight {
		float sample(int blockX, int blockZ);
	}

	private static final class Slot {
		private Climate.Sampler sampler;
		private GeneratorContext context;
		private final long[] keys = new long[CACHE_SIZE];
		private final float[] minimumSurfaceY = new float[CACHE_SIZE];
		private final boolean[] present = new boolean[CACHE_SIZE];
		private final long[] surfaceKeys = new long[SURFACE_CACHE_SIZE];
		private final float[] surfaceY = new float[SURFACE_CACHE_SIZE];
		private final boolean[] surfacePresent = new boolean[SURFACE_CACHE_SIZE];
		private long lastUse;

		private float surfaceY(
			GeneratorContext context,
			Cell cell,
			int blockX,
			int blockZ
		) {
			long key = key(blockX, blockZ);
			int index = (int) HashCommon.mix(key) & SURFACE_CACHE_MASK;
			if (this.surfacePresent[index] && this.surfaceKeys[index] == key) {
				return this.surfaceY[index];
			}
			float value = UndergroundBiomeSurfaceProtection.sampleSurfaceY(
				context,
				cell,
				blockX,
				blockZ
			);
			this.surfacePresent[index] = true;
			this.surfaceKeys[index] = key;
			this.surfaceY[index] = value;
			return value;
		}

		private void rebind(Climate.Sampler sampler, GeneratorContext context) {
			this.sampler = sampler;
			this.context = context;
			Arrays.fill(this.present, false);
			Arrays.fill(this.surfacePresent, false);
		}
	}
}
