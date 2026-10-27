package etcodehome.freeterraforged.client.gui.screen.presetconfig;

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

import etcodehome.freeterraforged.world.worldgen.cell.heightmap.PreviewGrid;

final class PreviewComputationCache implements AutoCloseable {
    private static final int MAX_GRID_ENTRIES = 6;
    private static final int MAX_SIDECAR_ENTRIES = 8;

    private final LinkedHashMap<GridKey, GridEntry> grids = new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<GridKey, PendingGrid> pendingGrids = new LinkedHashMap<>();
    private final LinkedHashMap<SidecarKey, PendingSidecar> sidecars = new LinkedHashMap<>(16, 0.75F, true);
    private BiomePreview.CacheKey currentRevision;
    private boolean closed;

    void advance(BiomePreview.CacheKey revision) {
        Objects.requireNonNull(revision, "revision");
        PendingGrid[] cancellingGrids;
        PendingSidecar[] cancellingSidecars;
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
            for (GridEntry entry : this.grids.values()) {
                entry.evict();
            }
            this.grids.clear();
            cancellingGrids = this.pendingGrids.values().toArray(PendingGrid[]::new);
            this.pendingGrids.clear();
            cancellingSidecars = this.sidecars.values().toArray(PendingSidecar[]::new);
            this.sidecars.clear();
        }
        for (PendingGrid pending : cancellingGrids) {
            pending.cancel("Preview grid belongs to a superseded acquisition generation");
        }
        for (PendingSidecar pending : cancellingSidecars) {
            pending.cancel("Preview sidecar belongs to a superseded acquisition generation");
        }
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

    CompletableFuture<GridLease> acquireOrGenerate(
        GridKey key,
        BooleanSupplier requesterCancelled,
        Function<BooleanSupplier, CompletableFuture<PreviewGrid>> factory
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requesterCancelled, "requesterCancelled");
        Objects.requireNonNull(factory, "factory");
        PendingGrid pending;
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
            GridEntry existing = this.grids.get(key);
            if (existing != null) {
                if (requesterCancelled.getAsBoolean()) {
                    return CompletableFuture.failedFuture(
                            new CancellationException("Preview grid request was superseded")
                    );
                }
                return CompletableFuture.completedFuture(existing.retain());
            }
            pending = this.pendingGrids.get(key);
            if (pending == null) {
                pending = new PendingGrid(key);
                this.pendingGrids.put(key, pending);
                owner = true;
            }
            pending.addSubscriber(requesterCancelled);
        }

        PendingGrid shared = pending;
        CompletableFuture<GridLease> lease = shared.result.thenApply(entry -> {
            synchronized (this) {
                if (requesterCancelled.getAsBoolean()) {
                    throw new CancellationException("Preview grid request was superseded");
                }
                this.requireCurrent(key.revision());
                if (this.grids.get(key) != entry) {
                    throw new CancellationException("Preview grid no longer belongs to the active cache");
                }
                return entry.retain();
            }
        });
        if (owner) {
            shared.start(factory);
        }
        return lease;
    }

    private void finishPendingGrid(PendingGrid pending, PreviewGrid grid, Throwable failure) {
        GridEntry entry = null;
        Throwable terminalFailure = failure;
        boolean discard;
        synchronized (this) {
            boolean ownsSlot = this.pendingGrids.get(pending.key) == pending;
            discard = this.closed || pending.isCancelled() || !ownsSlot
                    || this.currentRevision == null || !this.currentRevision.equals(pending.key.revision());
            if (ownsSlot) {
                this.pendingGrids.remove(pending.key);
            }
            if (!discard && failure == null) {
                if (grid == null) {
                    terminalFailure = new IllegalStateException("Preview grid factory completed with null");
                } else {
                    entry = new GridEntry(grid);
                    this.grids.put(pending.key, entry);
                }
            }
        }

        if (discard || terminalFailure != null) {
            if (terminalFailure == null) {
                terminalFailure = new CancellationException("Preview grid generation was superseded");
            }
            pending.result.completeExceptionally(terminalFailure);
            pending.releaseSubscribers();
            return;
        }

        pending.result.complete(entry);
        pending.releaseSubscribers();
        synchronized (this) {
            this.trimGrids();
        }
    }

    private final class PendingGrid {
        private final GridKey key;
        private final CompletableFuture<GridEntry> result = new CompletableFuture<>();
        private final List<BooleanSupplier> subscribers = new ArrayList<>();
        private CompletableFuture<PreviewGrid> producer;
        private boolean cancelled;

        private PendingGrid(GridKey key) {
            this.key = key;
        }

        private synchronized void addSubscriber(BooleanSupplier subscriber) {
            if (!this.result.isDone()) {
                this.subscribers.add(subscriber);
            }
        }

        private void start(Function<BooleanSupplier, CompletableFuture<PreviewGrid>> factory) {
            CompletableFuture<PreviewGrid> started;
            try {
                started = Objects.requireNonNull(
                        factory.apply(this::isCancelled), "Preview grid factory returned null"
                );
            } catch (Throwable failure) {
                PreviewComputationCache.this.finishPendingGrid(this, null, failure);
                return;
            }
            synchronized (this) {
                this.producer = started;
                if (this.cancelled) {
                    started.cancel(true);
                }
            }
            started.whenComplete((grid, failure) ->
                    PreviewComputationCache.this.finishPendingGrid(this, grid, failure)
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
            CompletableFuture<PreviewGrid> cancelling;
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

    private void trimGrids() {
        Iterator<Map.Entry<GridKey, GridEntry>> iterator = this.grids.entrySet().iterator();
        while (this.grids.size() > MAX_GRID_ENTRIES && iterator.hasNext()) {
            GridEntry entry = iterator.next().getValue();
            iterator.remove();
            entry.evict();
        }
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
        PendingGrid[] cancellingGrids;
        PendingSidecar[] cancellingSidecars;
        synchronized (this) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            for (GridEntry entry : this.grids.values()) {
                entry.evict();
            }
            this.grids.clear();
            cancellingGrids = this.pendingGrids.values().toArray(PendingGrid[]::new);
            this.pendingGrids.clear();
            cancellingSidecars = this.sidecars.values().toArray(PendingSidecar[]::new);
            this.sidecars.clear();
            this.currentRevision = null;
        }
        for (PendingGrid pending : cancellingGrids) {
            pending.cancel("Preview cache is closed");
        }
        for (PendingSidecar pending : cancellingSidecars) {
            pending.cancel("Preview cache is closed");
        }
    }

    private void requireCurrent(BiomePreview.CacheKey revision) {
        Objects.requireNonNull(revision, "revision");
        if (this.currentRevision == null || !this.currentRevision.equals(revision)) {
            throw new CancellationException(
                    "Preview result belongs to a stale acquisition generation"
            );
        }
    }

    record GridKey(BiomePreview.CacheKey revision, int centerX, int centerZ, int zoom, int size, boolean biomePipeline) {
    }

    record SidecarKey(BiomePreview.CacheKey revision, int centerX, int centerZ, int zoom, int size) {
    }

    final class GridLease implements AutoCloseable {
        private GridEntry entry;

        private GridLease(GridEntry entry) {
            this.entry = entry;
        }

        PreviewGrid grid() {
            GridEntry current = this.entry;
            if (current == null) {
                throw new IllegalStateException("Preview grid lease is closed");
            }
            return current.grid;
        }

        GridLease retain() {
            GridEntry current = this.entry;
            if (current == null) {
                throw new IllegalStateException("Preview grid lease is closed");
            }
            synchronized (PreviewComputationCache.this) {
                return current.retain();
            }
        }

        @Override
        public void close() {
            GridEntry current = this.entry;
            if (current == null) {
                return;
            }
            this.entry = null;
            synchronized (PreviewComputationCache.this) {
                current.release();
                PreviewComputationCache.this.trimGrids();
            }
        }
    }

    private final class GridEntry {
        private final PreviewGrid grid;
        private int references;
        private boolean evicted;

        private GridEntry(PreviewGrid grid) {
            this.grid = grid;
        }

        private GridLease retain() {
            this.references++;
            return new GridLease(this);
        }

        private void release() {
            if (this.references <= 0) {
                throw new IllegalStateException("Preview grid lease underflow");
            }
            this.references--;
        }

        private void evict() {
            this.evicted = true;
        }
    }
}
