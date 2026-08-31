package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

/**
 * A screen-scoped cache for immutable preview results.
 *
 * Tiles are pooled/mutable objects in the generator, so callers receive leases
 * rather than the raw tile.  An entry is only recycled after it has been evicted
 * and its last lease is released.  This makes sharing between the 2D and 3D
 * previews safe while keeping the cache bounded to the current editor screen.
 */
final class PreviewComputationCache implements AutoCloseable {
    private static final int MAX_TILE_ENTRIES = 6;
    private static final int MAX_SIDECAR_ENTRIES = 8;

    private final LinkedHashMap<TileKey, TileEntry> tiles = new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<SidecarKey, CompletableFuture<BiomePreview.Sidecar>> sidecars = new LinkedHashMap<>(16, 0.75F, true);
    private boolean closed;

    synchronized TileLease acquire(TileKey key) {
        if (this.closed) {
            return null;
        }
        TileEntry entry = this.tiles.get(key);
        return entry == null ? null : entry.retain();
    }

    synchronized TileLease store(TileKey key, Tile tile) {
        Objects.requireNonNull(tile, "tile");
        if (this.closed) {
            tile.close();
            return null;
        }

        TileEntry existing = this.tiles.get(key);
        if (existing != null) {
            tile.close();
            return existing.retain();
        }

        TileEntry entry = new TileEntry(key, tile);
        this.tiles.put(key, entry);
        TileLease lease = entry.retain();
        this.trimTiles();
        return lease;
    }

    CompletableFuture<BiomePreview.Sidecar> sidecar(
        SidecarKey key,
        Supplier<BiomePreview.Sidecar> supplier
    ) {
        CompletableFuture<BiomePreview.Sidecar> future;
        boolean owner = false;
        synchronized (this) {
            if (this.closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Preview cache is closed"));
            }
            future = this.sidecars.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                this.sidecars.put(key, future);
                this.trimSidecars();
                owner = true;
            }
        }

        if (owner) {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
                synchronized (this) {
                    if (this.sidecars.get(key) == future) {
                        this.sidecars.remove(key);
                    }
                }
            } finally {
                synchronized (this) {
                    this.trimSidecars();
                }
            }
        }
        return future;
    }

    private void trimTiles() {
        Iterator<Map.Entry<TileKey, TileEntry>> iterator = this.tiles.entrySet().iterator();
        while (this.tiles.size() > MAX_TILE_ENTRIES && iterator.hasNext()) {
            TileEntry entry = iterator.next().getValue();
            if (entry.references == 0) {
                iterator.remove();
                entry.evict();
            }
        }
    }

    private void trimSidecars() {
        Iterator<Map.Entry<SidecarKey, CompletableFuture<BiomePreview.Sidecar>>> iterator = this.sidecars.entrySet().iterator();
        while (this.sidecars.size() > MAX_SIDECAR_ENTRIES && iterator.hasNext()) {
            Map.Entry<SidecarKey, CompletableFuture<BiomePreview.Sidecar>> entry = iterator.next();
            if (entry.getValue().isDone()) {
                iterator.remove();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (TileEntry entry : this.tiles.values()) {
            entry.evict();
        }
        this.tiles.clear();
        this.sidecars.clear();
    }

    record TileKey(BiomePreview.CacheKey revision, int centerX, int centerZ, int zoom, int size, boolean biomePipeline) {
    }

    record SidecarKey(BiomePreview.CacheKey revision, int centerX, int centerZ, int zoom, int size) {
    }

    final class TileLease implements AutoCloseable {
        private TileEntry entry;

        private TileLease(TileEntry entry) {
            this.entry = entry;
        }

        Tile tile() {
            TileEntry current = this.entry;
            if (current == null) {
                throw new IllegalStateException("Preview tile lease is closed");
            }
            return current.tile;
        }

        TileLease retain() {
            TileEntry current = this.entry;
            if (current == null) {
                throw new IllegalStateException("Preview tile lease is closed");
            }
            synchronized (PreviewComputationCache.this) {
                return current.retain();
            }
        }

        @Override
        public void close() {
            TileEntry current = this.entry;
            if (current == null) {
                return;
            }
            this.entry = null;
            synchronized (PreviewComputationCache.this) {
                current.release();
                PreviewComputationCache.this.trimTiles();
            }
        }
    }

    private final class TileEntry {
        private final TileKey key;
        private final Tile tile;
        private int references;
        private boolean evicted;
        private boolean recycled;

        private TileEntry(TileKey key, Tile tile) {
            this.key = key;
            this.tile = tile;
        }

        private TileLease retain() {
            if (this.recycled) {
                throw new IllegalStateException("Preview tile was recycled");
            }
            this.references++;
            return new TileLease(this);
        }

        private void release() {
            if (this.references <= 0) {
                throw new IllegalStateException("Preview tile lease underflow");
            }
            this.references--;
            this.recycleIfUnused();
        }

        private void evict() {
            this.evicted = true;
            this.recycleIfUnused();
        }

        private void recycleIfUnused() {
            if (this.evicted && this.references == 0 && !this.recycled) {
                this.recycled = true;
                this.tile.close();
            }
        }
    }
}
