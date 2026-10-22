package etcodehome.freeterraforged.world.worldgen.biome;

import java.lang.ref.WeakReference;
import java.util.function.IntBinaryOperator;

import net.minecraft.core.QuartPos;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import etcodehome.freeterraforged.world.worldgen.runtime.OwnerThreadCache;

public final class BiomeSurfaceEnvelope {
	// BiomeManager subtracts two blocks, then chooses either corner of a quart cube.
	// A cell at 4*q can therefore affect blocks [4*q-2, 4*q+5]. Include the adjacent
	// column as well: a solid face above that column's skyline is exterior too.
	static final int MIN_OFFSET = -3;
	static final int MAX_OFFSET = 6;
	static final int MAX_VERTICAL_OFFSET = 5;
	private static final ThreadLocal<int[]> SCALED_GRID_SCRATCH = ThreadLocal.withInitial(() -> new int[40 * 40]);
	private final OwnerThreadCache<Entry> values = new OwnerThreadCache<>(16384);

	/**
	 * Pre-populates the surface envelope cache for all quarts in {@code [-2..5] x [-2..5]}
	 * around the chunk directly from the freshly initialized 40x40 heightmap grid
	 * (before filters modify {@code cell.height}).
	 */
	public void prepopulateFromChunkGrid(
		GeneratorContext context,
		int chunkX,
		int chunkZ,
		Cell[] grid,
		int totalSize,
		int border
	) {
		if (totalSize < 40 || border < 12) {
			return;
		}
		WorldLookup lookup = context.lookup;
		int maxAllowedY = context.levels.worldHeight - 1;
		int[] scaled = SCALED_GRID_SCRATCH.get();
		int len = totalSize * totalSize;
		for (int i = 0; i < len; i++) {
			scaled[i] = Math.min(maxAllowedY, context.levels.scale(grid[i].height));
		}

		int baseQuartX = chunkX << 2;
		int baseQuartZ = chunkZ << 2;
		for (int lqz = -2; lqz <= 5; lqz++) {
			int qz = baseQuartZ + lqz;
			int gridBaseZ = border + (lqz << 2);
			for (int lqx = -2; lqx <= 5; lqx++) {
				int qx = baseQuartX + lqx;
				long key = ((long) qx << 32) ^ (qz & 0xFFFFFFFFL);
				Entry existing = this.values.find(key);
				if (existing != null && existing.get() == lookup && existing.accurate) {
					continue;
				}
				int gridBaseX = border + (lqx << 2);
				int minimum = Integer.MAX_VALUE;
				for (int dz = MIN_OFFSET; dz <= MAX_OFFSET; dz++) {
					int rowOffset = (gridBaseZ + dz) * totalSize + gridBaseX;
					for (int dx = MIN_OFFSET; dx <= MAX_OFFSET; dx++) {
						int y = scaled[rowOffset + dx];
						if (y < minimum) {
							minimum = y;
						}
					}
				}
				this.values.store(key, new Entry(lookup, minimum, true));
			}
		}
	}

	public int minimumSurfaceY(GeneratorContext context, int quartX, int quartZ) {
		return this.minimumSurfaceY(context.lookup, true, quartX, quartZ, (x, z) -> {
			WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.ACTIVE ? WorldgenPerfTracker.stats() : null;
			WorldgenPerfTracker.ApplyCellCaller prev = null;
			if (ts != null) {
				prev = ts.currentCaller;
				ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.BIOME_ENVELOPE;
			}
			try {
				Cell cell = etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler.getCachedOrLookup(
					context.lookup, x, z, false
				);
				return Math.min(context.levels.worldHeight - 1, context.levels.scale(cell.height));
			} finally {
				if (ts != null) {
					ts.currentCaller = prev;
				}
			}
		});
	}

	int minimumSurfaceY(WorldLookup lookup, boolean accurate, int quartX, int quartZ, IntBinaryOperator surface) {
		long key = ((long) quartX << 32) ^ (quartZ & 0xFFFFFFFFL);
		if (!WorldgenPerfTracker.ACTIVE) {
			Entry cached = this.values.find(key);
			if (cached != null && cached.get() == lookup && cached.accurate == accurate) return cached.height;
			int height = minimumSurfaceY(quartX, quartZ, surface);
			this.values.store(key, new Entry(lookup, height, accurate));
			return height;
		}
		WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
		long t0 = System.nanoTime();
		ts.biomeEnvelopeCalls++;
		Entry cached = this.values.find(key);
		if (cached != null && cached.get() == lookup && cached.accurate == accurate) {
			ts.biomeEnvelopeHits++;
			ts.biomeEnvelopeNanos += (System.nanoTime() - t0);
			return cached.height;
		}
		ts.biomeEnvelopeMisses++;
		int height = minimumSurfaceY(quartX, quartZ, surface);
		this.values.store(key, new Entry(lookup, height, accurate));
		ts.biomeEnvelopeNanos += (System.nanoTime() - t0);
		return height;
	}

	static int minimumSurfaceY(int quartX, int quartZ, IntBinaryOperator surface) {
		int baseX = QuartPos.toBlock(quartX);
		int baseZ = QuartPos.toBlock(quartZ);
		int minimum = Integer.MAX_VALUE;
		for (int dz = MIN_OFFSET; dz <= MAX_OFFSET; dz++) {
			for (int dx = MIN_OFFSET; dx <= MAX_OFFSET; dx++) {
				minimum = Math.min(minimum, surface.applyAsInt(baseX + dx, baseZ + dz));
			}
		}
		return minimum;
	}

	public void clear() {
		this.values.clear();
	}

	private static final class Entry extends WeakReference<WorldLookup> {
		private final int height;
		private final boolean accurate;
		private Entry(WorldLookup owner, int height, boolean accurate) {
			super(owner);
			this.height = height;
			this.accurate = accurate;
		}
	}
}
