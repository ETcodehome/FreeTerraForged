package raccoonman.reterraforged.world.worldgen.biome;

import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.runtime.OwnerThreadCache;

public final class UndergroundBiomeSurfaceProtection {
	public static final int HARD_SHELL_BLOCKS = QuartPos.SIZE;
	public static final int TRANSITION_BLOCKS = 24;
	static final int REQUIRED_CLEARANCE_BLOCKS = QuartPos.SIZE + HARD_SHELL_BLOCKS;

	private static final float DEPTH_UNITS_PER_BLOCK = 1.0F / 128.0F;
	private static final float SURFACE_DEPTH = NoiseRouterData.GLOBAL_OFFSET + 0.5F;
	private static final int CACHE_SIZE = 1024;
	private static final int SURFACE_CACHE_SIZE = 4096;

	private UndergroundBiomeSurfaceProtection() {
	}

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
		float minimumSurfaceY = ((RTFClimateSampler) (Object) sampler)
			.minimumSurfaceY(context, quartX, quartZ);
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

	public static final class Cache {
		private final OwnerThreadCache<Float> minimumSurface =
			new OwnerThreadCache<>(CACHE_SIZE);
		private final OwnerThreadCache<Float> surface =
			new OwnerThreadCache<>(SURFACE_CACHE_SIZE);

		public float minimumSurfaceY(
			GeneratorContext context,
			int quartX,
			int quartZ
		) {
			long key = key(quartX, quartZ);
			Float cached = this.minimumSurface.find(key);
			if (cached != null) {
				return cached;
			}

			Cell cell = new Cell();
			float minimum = UndergroundBiomeSurfaceProtection.minimumSurfaceY(
				(x, z) -> this.surfaceY(context, cell, x, z),
				quartX,
				quartZ
			);
			this.minimumSurface.store(key, minimum);
			return minimum;
		}

		private float surfaceY(
			GeneratorContext context,
			Cell cell,
			int blockX,
			int blockZ
		) {
			long key = key(blockX, blockZ);
			Float cached = this.surface.find(key);
			if (cached != null) {
				return cached;
			}
			float value = UndergroundBiomeSurfaceProtection.sampleSurfaceY(
				context,
				cell,
				blockX,
				blockZ
			);
			this.surface.store(key, value);
			return value;
		}

		public void clear() {
			this.minimumSurface.clear();
			this.surface.clear();
		}
	}

	@FunctionalInterface
	interface SurfaceHeight {
		float sample(int blockX, int blockZ);
	}
}
