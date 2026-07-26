package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.concurrent.cache.Cache;
import raccoonman.reterraforged.concurrent.cache.CacheEntry;
import raccoonman.reterraforged.concurrent.cache.CacheManager;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

public class TileCache implements TileFactory {
	private final int tileSize;
	private final boolean queue;
	private final Cache<CacheEntry<Entry>> cache;
	private final TileGenerator generator;

	public TileCache(int tileSize, boolean queue, TileGenerator generator) {
		this.tileSize = tileSize;
		this.queue = queue;
		this.cache = CacheManager.createCache(256, 60L, 20L, TimeUnit.SECONDS);
		this.generator = generator;
	}

	public TileGenerator getGenerator() {
		return this.generator;
	}

	@Nullable
	public Tile provideIfPresent(int tileX, int tileZ) {
		CacheEntry<Entry> entry = this.cache.get(PosUtil.pack(tileX, tileZ));
		if (entry != null) {
			Entry e = entry.get();
			// Ensure the entry is fully initialized and has not been closed by a concurrent drop
			if (e != null && !e.isClosed()) {
				return e.tile;
			}
		}
		return null;
	}

	@Override
	public Tile provide(int tileX, int tileZ) {
		CacheEntry<Entry> entry = this.computeEntry(tileX, tileZ);
		Entry e = entry.get();
		if (e == null) {
			throw new IllegalStateException("Failed to compute or retrieve Tile at (" + tileX + ", " + tileZ + ")");
		}
		return e.tile;
	}

	@Override
	public void queue(int tileX, int tileZ) {
		if (this.queue) {
			this.computeEntry(tileX, tileZ);
		}
	}

	@Override
	public void drop(int tileX, int tileZ) {
		long packedTilePos = PosUtil.pack(tileX, tileZ);
		CacheEntry<Entry> entry = this.cache.get(packedTilePos);

		// Gracefully handle cases where the entry was already evicted by TTL or removed
		if (entry != null) {
			Entry e = entry.get();
			if (e != null && e.drop()) {
				this.cache.remove(packedTilePos);
			}
		}
	}

	@Override
	public int chunkToTile(int chunkCoord) {
		return chunkCoord >> this.tileSize;
	}

	private CacheEntry<Entry> computeEntry(int tileX, int tileZ) {
		return this.cache.computeIfAbsent(PosUtil.pack(tileX, tileZ), (k) -> {
			return CacheEntry.supply(this.generator.generate(tileX, tileZ).thenApply(Entry::new));
		});
	}

	private static class Entry {
		private final AtomicInteger refCount = new AtomicInteger(0);
		private final AtomicBoolean closed = new AtomicBoolean(false);
		private final int chunkCount;
		private final Tile tile;

		public Entry(Tile tile) {
			int size = tile.getChunksSize().size();
			this.chunkCount = size * size;
			this.tile = tile;
		}

		public boolean drop() {
			if (this.refCount.incrementAndGet() >= this.chunkCount) {
				// Atomic CAS guarantees tile.close() runs EXACTLY once even with duplicate/racing calls
				if (this.closed.compareAndSet(false, true)) {
					this.tile.close();
					return true;
				}
			}
			return false;
		}

		public boolean isClosed() {
			return this.closed.get();
		}
	}
}