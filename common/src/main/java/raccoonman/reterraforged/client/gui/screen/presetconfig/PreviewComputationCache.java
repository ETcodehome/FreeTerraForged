package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import raccoonman.reterraforged.RTFCommon;
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
    private final LinkedHashMap<TileKey, PendingTile> pendingTiles = new LinkedHashMap<>();
    private final LinkedHashMap<SidecarKey, PendingSidecar> sidecars = new LinkedHashMap<>(16, 0.75F, true);
    private BiomePreview.CacheKey currentRevision;
    private boolean closed;

    void advance(BiomePreview.CacheKey revision) {
        Objects.requireNonNull(revision, "revision");
        PendingTile[] cancellingTiles;
        PendingSidecar[] cancellingSidecars;
		List<Tile> retiring = new ArrayList<>();
        synchronized (this) {
            if (this.closed) {
                throw new CancellationException("Preview cache is closed");
            }
            if (this.currentRevision != null) {
                if (revision.generation() < this.currentRevision.generation()) {
                    throw new CancellationException(
                            "A newer preview generation already owns the cache"
                    );
                }
                if (revision.generation() == this.currentRevision.generation()) {
                    if (!revision.equals(this.currentRevision)) {
                        throw new IllegalArgumentException(
                                mismatch(this.currentRevision, revision)
                        );
                    }
                    return;
                }
            }

            this.currentRevision = revision;
            for (TileEntry entry : this.tiles.values()) {
				Tile retired = entry.evict();
				if (retired != null) retiring.add(retired);
            }
            this.tiles.clear();
            cancellingTiles = this.pendingTiles.values().toArray(PendingTile[]::new);
            this.pendingTiles.clear();
            cancellingSidecars = this.sidecars.values().toArray(PendingSidecar[]::new);
            this.sidecars.clear();
        }
        for (PendingTile pending : cancellingTiles) {
            pending.cancel("Preview tile belongs to a superseded acquisition generation");
        }
        for (PendingSidecar pending : cancellingSidecars) {
            pending.cancel("Preview sidecar belongs to a superseded acquisition generation");
        }
		throwIfFailed(closeTiles(retiring));
    }

    private static String mismatch(BiomePreview.CacheKey current, BiomePreview.CacheKey requested) {
        return "One preview generation cannot describe two acquisition inputs: generation="
                + requested.generation()
                + " seed=" + (current.seed() == requested.seed())
                + " preset=" + current.preset().equals(requested.preset())
                + " data=" + current.dataConfiguration().equals(requested.dataConfiguration())
                + " settings=" + current.settingsIdentity().equals(requested.settingsIdentity())
                + " tags=" + current.tagFingerprint().equals(requested.tagFingerprint())
                + " contributions=" + current.contributionRevision().equals(requested.contributionRevision())
                + " registries=" + (current.registrySnapshot() == requested.registrySnapshot())
                + " stem=" + (current.selectedStem() == requested.selectedStem())
                + " providers=" + (current.providers() == requested.providers());
    }

    CompletableFuture<TileLease> acquireOrGenerate(
        TileKey key,
        BooleanSupplier requesterCancelled,
        Function<BooleanSupplier, CompletableFuture<Tile>> factory
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requesterCancelled, "requesterCancelled");
        Objects.requireNonNull(factory, "factory");
        PendingTile pending;
        boolean owner = false;
        synchronized (this) {
            if (this.closed) {
                return CompletableFuture.failedFuture(new CancellationException("Preview cache is closed"));
            }
            try {
                this.requireCurrent(key.revision());
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            TileEntry existing = this.tiles.get(key);
            if (existing != null) {
                if (requesterCancelled.getAsBoolean()) {
                    return CompletableFuture.failedFuture(
                            new CancellationException("Preview tile request was superseded")
                    );
                }
                return CompletableFuture.completedFuture(existing.retain());
            }
            pending = this.pendingTiles.get(key);
            if (pending == null) {
                pending = new PendingTile(key);
                this.pendingTiles.put(key, pending);
                owner = true;
            }
            pending.addSubscriber(requesterCancelled);
        }

        PendingTile shared = pending;
        CompletableFuture<TileLease> lease = shared.result.thenApply(entry -> {
            synchronized (this) {
                if (requesterCancelled.getAsBoolean()) {
                    throw new CancellationException("Preview tile request was superseded");
                }
                this.requireCurrent(key.revision());
                if (this.tiles.get(key) != entry) {
                    throw new CancellationException("Preview tile no longer belongs to the active cache");
                }
                return entry.retain();
            }
        });
        if (owner) {
            shared.start(factory);
        }
        return lease;
    }

    private void finishPendingTile(PendingTile pending, Tile tile, Throwable failure) {
        TileEntry entry = null;
        Throwable terminalFailure = failure;
        boolean discard;
        synchronized (this) {
            boolean ownsSlot = this.pendingTiles.get(pending.key) == pending;
            discard = this.closed || pending.isCancelled() || !ownsSlot
                    || this.currentRevision == null || !this.currentRevision.equals(pending.key.revision());
            if (ownsSlot) {
                this.pendingTiles.remove(pending.key);
            }
            if (!discard && failure == null) {
                if (tile == null) {
                    terminalFailure = new IllegalStateException("Preview tile factory completed with null");
                } else {
                    entry = new TileEntry(tile);
                    this.tiles.put(pending.key, entry);
                }
            }
        }

        if (discard || terminalFailure != null) {
			if (terminalFailure == null) {
				terminalFailure = new CancellationException("Preview tile generation was superseded");
			}
            if (tile != null) {
				try {
					tile.close();
				} catch (RuntimeException | Error cleanupFailure) {
					terminalFailure.addSuppressed(cleanupFailure);
				}
            }
            pending.result.completeExceptionally(terminalFailure);
            pending.releaseSubscribers();
            return;
        }

        pending.result.complete(entry);
        pending.releaseSubscribers();
		List<Tile> retiring;
		synchronized (this) {
			retiring = this.trimTiles();
		}
		logRetirementFailures(closeTiles(retiring));
    }

    private final class PendingTile {
        private final TileKey key;
        private final CompletableFuture<TileEntry> result = new CompletableFuture<>();
        private final List<BooleanSupplier> subscribers = new ArrayList<>();
        private CompletableFuture<Tile> producer;
        private boolean cancelled;

        private PendingTile(TileKey key) {
            this.key = key;
        }

        private synchronized void addSubscriber(BooleanSupplier subscriber) {
            if (!this.result.isDone()) {
                this.subscribers.add(subscriber);
            }
        }

        private void start(Function<BooleanSupplier, CompletableFuture<Tile>> factory) {
            CompletableFuture<Tile> started;
            try {
                started = Objects.requireNonNull(
                        factory.apply(this::isCancelled), "Preview tile factory returned null"
                );
            } catch (Throwable failure) {
                PreviewComputationCache.this.finishPendingTile(this, null, failure);
                return;
            }
            synchronized (this) {
                this.producer = started;
                if (this.cancelled) {
                    started.cancel(true);
                }
            }
            started.whenComplete((tile, failure) ->
                    PreviewComputationCache.this.finishPendingTile(this, tile, failure)
            );
        }

        private synchronized boolean isCancelled() {
            if (this.cancelled) {
                return true;
            }
            for (BooleanSupplier subscriber : this.subscribers) {
                if (!subscriber.getAsBoolean()) {
                    return false;
                }
            }
            return !this.subscribers.isEmpty();
        }

        private synchronized void releaseSubscribers() {
            this.subscribers.clear();
        }

        private void cancel(String message) {
            CompletableFuture<Tile> cancelling;
            synchronized (this) {
                if (this.cancelled) {
                    return;
                }
                this.cancelled = true;
                cancelling = this.producer;
            }
            if (cancelling != null) {
                cancelling.cancel(true);
            }
            this.result.completeExceptionally(new CancellationException(message));
            this.releaseSubscribers();
        }
    }

    CompletableFuture<BiomePreview.Sidecar> sidecar(
        SidecarKey key,
        BooleanSupplier requesterCancelled,
        Function<BooleanSupplier, BiomePreview.Sidecar> factory
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requesterCancelled, "requesterCancelled");
        Objects.requireNonNull(factory, "factory");
        PendingSidecar pending;
        boolean owner = false;
        synchronized (this) {
            if (this.closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Preview cache is closed"));
            }
            try {
                this.requireCurrent(key.revision());
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            pending = this.sidecars.get(key);
            if (pending == null) {
                pending = new PendingSidecar(key);
                this.sidecars.put(key, pending);
                owner = true;
            }
            pending.addSubscriber(requesterCancelled);
        }

        if (owner) {
            pending.start(factory);
        }
        return pending.result.thenApply(sidecar -> {
            if (requesterCancelled.getAsBoolean()) {
                throw new CancellationException("Preview sidecar request was superseded");
            }
            return sidecar;
        });
    }

    private void finishPendingSidecar(
        PendingSidecar pending,
        BiomePreview.Sidecar sidecar,
        Throwable failure
    ) {
		if (failure == null && sidecar == null) {
			failure = new IllegalStateException("Preview sidecar factory completed with null");
		}
        boolean discard;
        synchronized (this) {
            boolean ownsSlot = this.sidecars.get(pending.key) == pending;
            discard = this.closed || pending.isCancelled() || !ownsSlot
                    || this.currentRevision == null || !this.currentRevision.equals(pending.key.revision());
            if (ownsSlot && (discard || failure != null)) {
                this.sidecars.remove(pending.key);
            }
        }
        if (discard || failure != null) {
            Throwable terminal = failure == null
                    ? new CancellationException("Preview sidecar generation was superseded")
                    : failure;
            pending.result.completeExceptionally(terminal);
            pending.releaseSubscribers();
            return;
        }
        pending.result.complete(sidecar);
        pending.releaseSubscribers();
        synchronized (this) {
            this.trimSidecars();
        }
    }

    private final class PendingSidecar {
        private final SidecarKey key;
        private final CompletableFuture<BiomePreview.Sidecar> result = new CompletableFuture<>();
        private final List<BooleanSupplier> subscribers = new ArrayList<>();
        private boolean cancelled;

        private PendingSidecar(SidecarKey key) {
            this.key = key;
        }

        private synchronized void addSubscriber(BooleanSupplier subscriber) {
            if (!this.result.isDone()) {
                this.subscribers.add(subscriber);
            }
        }

        private void start(Function<BooleanSupplier, BiomePreview.Sidecar> factory) {
            BiomePreview.Sidecar value = null;
            Throwable failure = null;
            try {
                value = factory.apply(this::isCancelled);
            } catch (Throwable throwable) {
                failure = throwable;
            }
            PreviewComputationCache.this.finishPendingSidecar(this, value, failure);
        }

        private synchronized boolean isCancelled() {
            if (this.cancelled) {
                return true;
            }
            for (BooleanSupplier subscriber : this.subscribers) {
                if (!subscriber.getAsBoolean()) {
                    return false;
                }
            }
            return !this.subscribers.isEmpty();
        }

        private synchronized void releaseSubscribers() {
            this.subscribers.clear();
        }

        private void cancel(String message) {
            synchronized (this) {
                if (this.cancelled) {
                    return;
                }
                this.cancelled = true;
            }
            this.result.completeExceptionally(new CancellationException(message));
            this.releaseSubscribers();
        }
    }

	private List<Tile> trimTiles() {
		List<Tile> retiring = new ArrayList<>();
        Iterator<Map.Entry<TileKey, TileEntry>> iterator = this.tiles.entrySet().iterator();
        while (this.tiles.size() > MAX_TILE_ENTRIES && iterator.hasNext()) {
            TileEntry entry = iterator.next().getValue();
            iterator.remove();
			Tile retired = entry.evict();
			if (retired != null) retiring.add(retired);
        }
		return retiring;
    }

    private void trimSidecars() {
        Iterator<Map.Entry<SidecarKey, PendingSidecar>> iterator = this.sidecars.entrySet().iterator();
        while (this.sidecars.size() > MAX_SIDECAR_ENTRIES && iterator.hasNext()) {
            Map.Entry<SidecarKey, PendingSidecar> entry = iterator.next();
            if (entry.getValue().result.isDone()) {
                iterator.remove();
            }
        }
    }

    @Override
    public void close() {
        PendingTile[] cancellingTiles;
        PendingSidecar[] cancellingSidecars;
		List<Tile> retiring = new ArrayList<>();
        synchronized (this) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            for (TileEntry entry : this.tiles.values()) {
				Tile retired = entry.evict();
				if (retired != null) retiring.add(retired);
            }
            this.tiles.clear();
            cancellingTiles = this.pendingTiles.values().toArray(PendingTile[]::new);
            this.pendingTiles.clear();
            cancellingSidecars = this.sidecars.values().toArray(PendingSidecar[]::new);
            this.sidecars.clear();
            this.currentRevision = null;
        }
        for (PendingTile pending : cancellingTiles) {
            pending.cancel("Preview cache is closed");
        }
        for (PendingSidecar pending : cancellingSidecars) {
            pending.cancel("Preview cache is closed");
        }
		throwIfFailed(closeTiles(retiring));
    }

    private void requireCurrent(BiomePreview.CacheKey revision) {
        Objects.requireNonNull(revision, "revision");
        if (this.currentRevision == null || !this.currentRevision.equals(revision)) {
            throw new java.util.concurrent.CancellationException(
                    "Preview result belongs to a stale acquisition generation"
            );
        }
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
			List<Tile> retiring;
            synchronized (PreviewComputationCache.this) {
				retiring = new ArrayList<>();
				Tile released = current.release();
				if (released != null) retiring.add(released);
				retiring.addAll(PreviewComputationCache.this.trimTiles());
            }
			throwIfFailed(closeTiles(retiring));
        }
    }

    private final class TileEntry {
        private final Tile tile;
        private int references;
        private boolean evicted;
        private boolean recycled;

        private TileEntry(Tile tile) {
            this.tile = tile;
        }

        private TileLease retain() {
            if (this.recycled) {
                throw new IllegalStateException("Preview tile was recycled");
            }
            this.references++;
            return new TileLease(this);
        }

        private Tile release() {
            if (this.references <= 0) {
                throw new IllegalStateException("Preview tile lease underflow");
            }
            this.references--;
			return this.recycleIfUnused();
        }

        private Tile evict() {
            this.evicted = true;
			return this.recycleIfUnused();
        }

        private Tile recycleIfUnused() {
            if (this.evicted && this.references == 0 && !this.recycled) {
                this.recycled = true;
				return this.tile;
            }
			return null;
        }
    }

	private static Throwable closeTiles(List<Tile> tiles) {
		Throwable failure = null;
		for (Tile tile : tiles) {
			try {
				tile.close();
			} catch (RuntimeException | Error closeFailure) {
				failure = mergeFailure(failure, closeFailure);
			}
		}
		return failure;
	}

	private static Throwable mergeFailure(Throwable current, Throwable next) {
		if (current == null) return next;
		if (next instanceof Error && !(current instanceof Error)) {
			next.addSuppressed(current);
			return next;
		}
		current.addSuppressed(next);
		return current;
	}

	private static void throwIfFailed(Throwable failure) {
		if (failure instanceof RuntimeException runtime) throw runtime;
		if (failure instanceof Error error) throw error;
	}

	private static void logRetirementFailures(Throwable failure) {
		if (failure != null) {
			RTFCommon.LOGGER.error("Failed retiring an evicted preview tile", failure);
		}
	}
}
