package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.concurrent.cache.Cache;
import raccoonman.reterraforged.concurrent.cache.CacheEntry;
import raccoonman.reterraforged.concurrent.cache.SafeCloseable;
import raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap;
import raccoonman.reterraforged.concurrent.RetainedResource;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

public class TileCache implements AutoCloseable {
	private static final int MIN_TILE_ENTRIES = 4;
	private static final int MAX_TILE_ENTRIES = 32;
	private static final int MAX_CACHED_CELL_COUNT = 1 << 20;
	private final int tileSize;
	private final boolean queue;
	private final Cache<CacheEntry<Entry>> cache;
	private final TileGenerator generator;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicBoolean prefetchScheduled = new AtomicBoolean();

	public TileCache(int tileSize, boolean queue, TileGenerator generator) {
		this.tileSize = tileSize;
		this.queue = queue;
		this.cache = new Cache<>(cacheCapacity(generator.getTileCellCount()), 60L, 20L, TimeUnit.SECONDS, StampedBoundLongMap::new);
		this.generator = generator;
	}

	public static int cacheCapacity(int cellsPerTile) {
		if (cellsPerTile <= 0) {
			throw new IllegalArgumentException("Tile cell count must be positive");
		}
		return Math.max(MIN_TILE_ENTRIES, Math.min(
			MAX_TILE_ENTRIES,
			MAX_CACHED_CELL_COUNT / cellsPerTile
		));
	}

	public TileGenerator getGenerator() {
		return this.generator;
	}

	@Nullable
	public Lease acquireIfPresent(int tileX, int tileZ) {
		long packedTilePos = PosUtil.pack(tileX, tileZ);
		CacheEntry<Entry> entry = this.cache.getIfOpen(packedTilePos);
		if (entry != null && entry.isDone()) {
			Entry e;
			try {
				e = entry.get();
			} catch (RuntimeException | Error failure) {
				this.cache.removeIfOpen(packedTilePos, entry);
				throw failure;
			}
			RetainedResource.Lease<Tile> retained = e == null ? null : e.acquire();
			if (retained != null) {
				return new Lease(retained);
			}
		}
		return null;
	}

	public Lease acquire(int tileX, int tileZ) {
		long packedTilePos = PosUtil.pack(tileX, tileZ);
		while (true) {
			CacheEntry<Entry> entry = this.computeEntry(packedTilePos, tileX, tileZ);
			Entry value;
			try {
				value = entry.get();
			} catch (RuntimeException | Error failure) {
				this.cache.removeIfOpen(packedTilePos, entry);
				throw failure;
			}
			if (value == null) {
				throw new IllegalStateException("Failed to compute or retrieve Tile at (" + tileX + ", " + tileZ + ")");
			}
			RetainedResource.Lease<Tile> retained = value.acquire();
			if (retained != null) {
				return new Lease(retained);
			}
			this.cache.removeIfOpen(packedTilePos, entry);
		}
	}

	public Lease acquireAtChunk(int chunkX, int chunkZ) {
		return this.acquire(this.chunkToTile(chunkX), this.chunkToTile(chunkZ));
	}

	public void queue(int tileX, int tileZ) {
		if (!this.queue || this.closed.get() || !this.prefetchScheduled.compareAndSet(false, true)) {
			return;
		}
		try {
			ThreadPools.TILE_ADMISSION.execute(() -> {
				try {
					if (!this.closed.get()) {
						this.computeEntry(tileX, tileZ);
					}
				} catch (RuntimeException failure) {
					if (!this.closed.get()
						&& !(failure instanceof java.util.concurrent.CancellationException)) {
						raccoonman.reterraforged.RTFCommon.LOGGER.warn(
							"Advisory terrain-tile prefetch failed; demand lookup will retry", failure
						);
					}
				} finally {
					this.prefetchScheduled.set(false);
				}
			});
		} catch (RuntimeException | Error failure) {
			this.prefetchScheduled.set(false);
			if (failure instanceof Error error) {
				throw error;
			}
			if (!this.closed.get()) {
				raccoonman.reterraforged.RTFCommon.LOGGER.warn(
					"Terrain-tile prefetch submission was rejected; demand lookup will retry", failure
				);
			}
		}
	}

	public void queueAtChunk(int chunkX, int chunkZ) {
		this.queue(this.chunkToTile(chunkX), this.chunkToTile(chunkZ));
	}

	public int chunkToTile(int chunkCoord) {
		return chunkCoord >> this.tileSize;
	}

	private CacheEntry<Entry> computeEntry(int tileX, int tileZ) {
		return this.computeEntry(PosUtil.pack(tileX, tileZ), tileX, tileZ);
	}

	private CacheEntry<Entry> computeEntry(long packedTilePos, int tileX, int tileZ) {
		return this.cache.computeIfAbsent(packedTilePos, (k) -> {
			java.util.concurrent.CompletableFuture<Tile> producer = this.generator.generate(tileX, tileZ);
			if (!(producer instanceof SafeCloseable ownedProducer)) {
				producer.whenComplete((tile, failure) -> {
					if (failure == null && tile != null) {
						tile.close();
					}
				});
				producer.cancel(true);
				throw new IllegalStateException("Tile generation did not expose cancellable ownership");
			}
			java.util.concurrent.CompletableFuture<Entry> task = producer.thenApply(tile -> {
				try {
					return new Entry(tile);
				} catch (RuntimeException | Error failure) {
					try {
						tile.close();
					} catch (RuntimeException | Error cleanupFailure) {
						failure.addSuppressed(cleanupFailure);
					}
					throw failure;
				}
			});
			return CacheEntry.supply(task, ownedProducer);
		});
	}

	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			this.cache.close();
		}
	}

	private static class Entry implements SafeCloseable {
		private final RetainedResource<Tile> tile;

		public Entry(Tile tile) {
			this.tile = new RetainedResource<>(tile);
		}

		@Nullable
		public RetainedResource.Lease<Tile> acquire() {
			return this.tile.acquire();
		}

		@Override
		public void close() {
			this.tile.close();
		}
	}

	public static final class Lease implements AutoCloseable {
		private RetainedResource.Lease<Tile> retained;

		private Lease(RetainedResource.Lease<Tile> retained) {
			this.retained = retained;
		}

		public Tile tile() {
			RetainedResource.Lease<Tile> current = this.retained;
			if (current == null) {
				throw new IllegalStateException("Tile lease is closed");
			}
			return current.value();
		}

		@Override
		public void close() {
			RetainedResource.Lease<Tile> releasing = this.retained;
			if (releasing != null) {
				this.retained = null;
				releasing.close();
			}
		}
	}
}
