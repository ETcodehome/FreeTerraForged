package etcodehome.freeterraforged.world.worldgen.biome;

import java.lang.ref.WeakReference;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.QuartPos;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;

public final class BiomeSurfaceEnvelope {
	static final int MIN_OFFSET = -3;
	static final int MAX_OFFSET = 6;
	static final int MAX_VERTICAL_OFFSET = 5;

	private static final ThreadLocal<Cell> SCRATCH_CELL = ThreadLocal.withInitial(Cell::new);

	private static final class ThreadCache {
		private static final int TABLE_SIZE = 128;
		private static final int TABLE_MASK = TABLE_SIZE - 1;

		long lastKey = Long.MIN_VALUE;
		int lastMinY;
		WeakReference<WorldLookup> lastLookup = new WeakReference<>(null);

		final long[] tableKeys = new long[TABLE_SIZE];
		final int[] tableMinY = new int[TABLE_SIZE];
		final boolean[] tableValid = new boolean[TABLE_SIZE];

		ThreadCache() {
			java.util.Arrays.fill(this.tableKeys, Long.MIN_VALUE);
		}

		void clear() {
			this.lastKey = Long.MIN_VALUE;
			this.lastLookup = new WeakReference<>(null);
			java.util.Arrays.fill(this.tableValid, false);
		}
	}

	private static final ThreadLocal<ThreadCache> THREAD_CACHE = ThreadLocal.withInitial(ThreadCache::new);

	public BiomeSurfaceEnvelope() {
	}

	public void prepopulateFromRegionGrid(
		GeneratorContext context,
		int regionChunkX,
		int regionChunkZ,
		Cell[] grid,
		int totalSize,
		int border,
		int regionChunks
	) {
	}

	public void prepopulateFromChunkGrid(
		GeneratorContext context,
		int chunkX,
		int chunkZ,
		Cell[] grid,
		int totalSize,
		int border
	) {
	}

	public int minimumSurfaceY(GeneratorContext context, int quartX, int quartZ) {
		long key = ((long) quartX << 32) | (quartZ & 0xFFFFFFFFL);
		ThreadCache cache = THREAD_CACHE.get();
		WorldLookup lookup = context.lookup;

		if (cache.lastKey == key && cache.lastLookup.get() == lookup) {
			if (WorldgenPerfTracker.ACTIVE) {
				WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
				ts.biomeEnvelopeCalls++;
				ts.biomeEnvelopeHits++;
			}
			return cache.lastMinY;
		}

		int slot = (int) HashCommon.mix(key) & ThreadCache.TABLE_MASK;
		if (cache.tableValid[slot] && cache.tableKeys[slot] == key && cache.lastLookup.get() == lookup) {
			int minY = cache.tableMinY[slot];
			cache.lastKey = key;
			cache.lastMinY = minY;
			if (WorldgenPerfTracker.ACTIVE) {
				WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
				ts.biomeEnvelopeCalls++;
				ts.biomeEnvelopeHits++;
			}
			return minY;
		}

		int blockX = QuartPos.toBlock(quartX);
		int blockZ = QuartPos.toBlock(quartZ);
		WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.ACTIVE ? WorldgenPerfTracker.stats() : null;
		WorldgenPerfTracker.ApplyCellCaller prev = null;
		long t0 = (ts != null) ? System.nanoTime() : 0L;
		if (ts != null) {
			prev = ts.currentCaller;
			ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.BIOME_ENVELOPE;
			ts.biomeEnvelopeCalls++;
			ts.biomeEnvelopeMisses++;
		}
		try {
			Cell cell = SCRATCH_CELL.get();
			lookup.applyCell(cell.reset(), blockX, blockZ, false, false);
			int minY = context.levels.scale(cell.height);

			lookup.applyCell(cell.reset(), blockX + 3, blockZ, false, false);
			minY = Math.min(minY, context.levels.scale(cell.height));

			lookup.applyCell(cell.reset(), blockX, blockZ + 3, false, false);
			minY = Math.min(minY, context.levels.scale(cell.height));

			lookup.applyCell(cell.reset(), blockX + 3, blockZ + 3, false, false);
			minY = Math.min(minY, context.levels.scale(cell.height));

			int result = Math.min(context.levels.worldHeight - 1, minY);

			if (cache.lastLookup.get() != lookup) {
				cache.lastLookup = new WeakReference<>(lookup);
				java.util.Arrays.fill(cache.tableValid, false);
			}
			cache.tableKeys[slot] = key;
			cache.tableMinY[slot] = result;
			cache.tableValid[slot] = true;
			cache.lastKey = key;
			cache.lastMinY = result;

			return result;
		} finally {
			if (ts != null) {
				ts.biomeEnvelopeNanos += (System.nanoTime() - t0);
				ts.currentCaller = prev;
			}
		}
	}

	public void clear() {
		clearThreadCaches();
	}

	public static void clearThreadCaches() {
		THREAD_CACHE.get().clear();
	}
}
