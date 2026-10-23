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

	// Canonical sampling offsets shared between prepopulate and on-demand paths
	// to guarantee bit-for-bit identical results and eliminate order dependence.
	private static final int[] SAMPLE_OFFSETS = {-3, -1, 1, 3, 5, 6};

	private static final ThreadLocal<int[]> SCALED_GRID_SCRATCH = ThreadLocal.withInitial(() -> new int[88 * 88]);
	private final OwnerThreadCache<Entry> values = new OwnerThreadCache<>(65536);

	/**
	 * Pre-populates the surface envelope cache for all quarts covering the given multi-chunk
	 * filter grid directly from the freshly initialized heightmap grid.
	 */
	public void prepopulateFromRegionGrid(
		GeneratorContext context,
		int regionChunkX,
		int regionChunkZ,
		Cell[] grid,
		int totalSize,
		int border,
		int regionChunks
	) {
		WorldLookup lookup = context.lookup;
		int maxAllowedY = context.levels.worldHeight - 1;
		int[] scaled = SCALED_GRID_SCRATCH.get();
		int requiredLen = totalSize * totalSize;
		if (scaled.length < requiredLen) {
			scaled = new int[requiredLen];
			SCALED_GRID_SCRATCH.set(scaled);
		}
		for (int i = 0; i < requiredLen; i++) {
			scaled[i] = Math.min(maxAllowedY, context.levels.scale(grid[i].height));
		}

		int baseQuartX = regionChunkX << 2;
		int baseQuartZ = regionChunkZ << 2;
		int quartCount = regionChunks << 2;
		int minQuart = -2;
		int maxQuart = quartCount + 1;

		for (int lqz = minQuart; lqz <= maxQuart; lqz++) {
			int qz = baseQuartZ + lqz;
			int gridBaseZ = border + (lqz << 2);
			for (int lqx = minQuart; lqx <= maxQuart; lqx++) {
				int qx = baseQuartX + lqx;
				long key = ((long) qx << 32) ^ (qz & 0xFFFFFFFFL);
				Entry existing = this.values.find(key);
				if (existing != null && existing.get() == lookup && existing.accurate) {
					continue;
				}
				int gridBaseX = border + (lqx << 2);
				int minimum = Integer.MAX_VALUE;
				for (int dz : SAMPLE_OFFSETS) {
					int rowOffset = (gridBaseZ + dz) * totalSize + gridBaseX;
					for (int dx : SAMPLE_OFFSETS) {
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

	/**
	 * Pre-populates the surface envelope cache for all quarts in {@code [-2..5] x [-2..5]}
	 * around a single chunk directly from the freshly initialized 40x40 heightmap grid.
	 */
	public void prepopulateFromChunkGrid(
		GeneratorContext context,
		int chunkX,
		int chunkZ,
		Cell[] grid,
		int totalSize,
		int border
	) {
		this.prepopulateFromRegionGrid(context, chunkX, chunkZ, grid, totalSize, border, 1);
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

	private static final class MruSlot {
		long key = Long.MAX_VALUE;
		int height;
		boolean accurate;
		WeakReference<WorldLookup> lookup = new WeakReference<>(null);
	}

	private static final ThreadLocal<MruSlot> THREAD_MRU = ThreadLocal.withInitial(MruSlot::new);

	int minimumSurfaceY(WorldLookup lookup, boolean accurate, int quartX, int quartZ, IntBinaryOperator surface) {
		long key = ((long) quartX << 32) ^ (quartZ & 0xFFFFFFFFL);
		MruSlot mru = THREAD_MRU.get();
		if (mru.key == key && mru.accurate == accurate && mru.lookup.get() == lookup) {
			if (WorldgenPerfTracker.ACTIVE) {
				WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
				ts.biomeEnvelopeCalls++;
				ts.biomeEnvelopeHits++;
			}
			return mru.height;
		}

		if (!WorldgenPerfTracker.ACTIVE) {
			Entry cached = this.values.find(key);
			int height;
			if (cached != null && cached.get() == lookup && cached.accurate == accurate) {
				height = cached.height;
			} else {
				height = minimumSurfaceY(quartX, quartZ, surface);
				this.values.store(key, new Entry(lookup, height, accurate));
			}
			mru.key = key;
			mru.height = height;
			mru.accurate = accurate;
			mru.lookup = new WeakReference<>(lookup);
			return height;
		}
		WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
		long t0 = System.nanoTime();
		ts.biomeEnvelopeCalls++;
		Entry cached = this.values.find(key);
		int height;
		if (cached != null && cached.get() == lookup && cached.accurate == accurate) {
			ts.biomeEnvelopeHits++;
			height = cached.height;
		} else {
			ts.biomeEnvelopeMisses++;
			height = minimumSurfaceY(quartX, quartZ, surface);
			this.values.store(key, new Entry(lookup, height, accurate));
		}
		ts.biomeEnvelopeNanos += (System.nanoTime() - t0);
		mru.key = key;
		mru.height = height;
		mru.accurate = accurate;
		mru.lookup = new WeakReference<>(lookup);
		return height;
	}

	static int minimumSurfaceY(int quartX, int quartZ, IntBinaryOperator surface) {
		int baseX = QuartPos.toBlock(quartX);
		int baseZ = QuartPos.toBlock(quartZ);
		int minimum = Integer.MAX_VALUE;
		for (int dz : SAMPLE_OFFSETS) {
			int z = baseZ + dz;
			for (int dx : SAMPLE_OFFSETS) {
				int y = surface.applyAsInt(baseX + dx, z);
				if (y < minimum) {
					minimum = y;
				}
			}
		}
		return minimum;
	}

	public void clear() {
		this.values.clear();
		THREAD_MRU.get().key = Long.MAX_VALUE;
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
