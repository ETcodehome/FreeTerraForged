package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

final class PreviewRequestPool implements AutoCloseable {
	private IPreviewHandler.PreparedContext current;
	private BiomePreview.CacheKey currentKey;
	private Creation creating;
	private long latestGeneration = -1L;
    private boolean closed;

	void advance(BiomePreview.CacheKey key) {
		Objects.requireNonNull(key, "key");
		IPreviewHandler.PreparedContext closing = null;
		Creation cancelling = null;
		synchronized (this) {
			this.requireOpenAndCurrent(key);
			if (key.generation() == this.latestGeneration) {
				return;
			}
			this.latestGeneration = key.generation();
			this.currentKey = key;
			if (this.current != null && !this.current.matches(key)) {
				closing = this.current;
				this.current = null;
			}
			if (this.creating != null && !this.creating.matches(key)) {
				cancelling = this.creating;
				this.creating = null;
			}
		}
		try {
			if (closing != null) {
				closing.close();
			}
		} finally {
			if (cancelling != null) {
				cancelling.cancel("Preview acquisition was superseded by a newer generation");
			}
		}
	}

	IPreviewHandler.PreparedContext.Lease acquire(
            BiomePreview.CacheKey key,
			BooleanSupplier requesterCancelled,
            Function<BooleanSupplier, IPreviewHandler.PreparedContext> factory
    ) {
        Objects.requireNonNull(key, "key");
		Objects.requireNonNull(requesterCancelled, "requesterCancelled");
        Objects.requireNonNull(factory, "factory");
		this.advance(key);

		Creation creation;
		boolean creator = false;
		synchronized (this) {
			this.requireOpenAndCurrent(key);
			IPreviewHandler.PreparedContext owner = this.current;
			if (owner != null) {
				if (!owner.matches(key)) {
					throw new IllegalStateException("Preview pool retained an owner for a different generation");
				}
				if (requesterCancelled.getAsBoolean()) {
					throw new CancellationException("Preview acquisition requester was superseded");
				}
				return owner.acquire();
			}
			creation = this.creating;
			if (creation == null) {
				creation = new Creation(key);
				this.creating = creation;
				creator = true;
			} else if (!creation.matches(key)) {
				throw new IllegalStateException("Preview pool retained a factory for a different generation");
			}
			creation.addSubscriber(requesterCancelled);
		}

        return creator
			? this.createOwner(creation, requesterCancelled, factory)
			: this.acquireCreatedOwner(creation, requesterCancelled);
    }

    @Override
	public void close() {
		IPreviewHandler.PreparedContext closing;
		Creation cancelling;
		synchronized (this) {
			if (this.closed) {
				return;
			}
			this.closed = true;
			closing = this.current;
			this.current = null;
			cancelling = this.creating;
			this.creating = null;
			this.currentKey = null;
		}
		try {
			if (closing != null) {
				closing.close();
			}
		} finally {
			if (cancelling != null) {
				cancelling.cancel("Preview request pool is closed");
			}
		}
	}

	private IPreviewHandler.PreparedContext.Lease createOwner(
		Creation creation,
		BooleanSupplier requesterCancelled,
		Function<BooleanSupplier, IPreviewHandler.PreparedContext> factory
	) {
		IPreviewHandler.PreparedContext replacement = null;
		try {
			creation.checkCancelled();
			replacement = Objects.requireNonNull(
				factory.apply(creation::isCancelled), "Preview request factory returned null"
			);
			creation.checkCancelled();
		} catch (Throwable failure) {
			if (replacement != null) {
				closeAfterFailure(replacement, failure);
			}
			if (!this.failCreation(creation, failure)) {
				CancellationException cancelled = new CancellationException(
					"Preview acquisition failed after its generation was superseded"
				);
				cancelled.addSuppressed(failure);
				throw cancelled;
			}
			throw propagate(failure);
		}
		if (!replacement.matches(creation.key)) {
			IllegalArgumentException failure = new IllegalArgumentException(
				"Preview request factory returned the wrong semantic owner"
			);
			closeAfterFailure(replacement, failure);
			this.failCreation(creation, failure);
			throw failure;
		}

		IPreviewHandler.PreparedContext.Lease lease;
		boolean published;
		try {
			synchronized (this) {
				if (this.closed || this.creating != creation
					|| this.currentKey == null || !this.currentKey.equals(creation.key)
					|| creation.isCancelled()) {
					lease = null;
					published = false;
				} else {
					lease = requesterCancelled.getAsBoolean() ? null : replacement.acquire();
					this.creating = null;
					this.current = replacement;
					published = true;
				}
			}
		} catch (RuntimeException | Error failure) {
			closeAfterFailure(replacement, failure);
			this.failCreation(creation, failure);
			throw failure;
		}
		if (!published) {
			CancellationException failure = new CancellationException(
				"Preview acquisition completed after its generation was superseded"
			);
			closeAfterFailure(replacement, failure);
			creation.future.completeExceptionally(failure);
			creation.releaseSubscribers();
			throw failure;
		}
		creation.future.complete(replacement);
		creation.releaseSubscribers();
		if (lease == null) {
			throw new CancellationException("Preview acquisition requester was superseded");
		}
		return lease;
	}

	private IPreviewHandler.PreparedContext.Lease acquireCreatedOwner(
		Creation creation,
		BooleanSupplier requesterCancelled
	) {
		IPreviewHandler.PreparedContext created;
		try {
			created = creation.future.join();
		} catch (CompletionException failure) {
			throw propagate(failure.getCause() == null ? failure : failure.getCause());
		}
		synchronized (this) {
			this.requireOpenAndCurrent(creation.key);
			if (this.current != created || !created.matches(creation.key)) {
				throw new CancellationException("Preview acquisition no longer owns the screen");
			}
			if (requesterCancelled.getAsBoolean()) {
				throw new CancellationException("Preview acquisition requester was superseded");
			}
			return created.acquire();
		}
	}

	private boolean failCreation(Creation creation, Throwable failure) {
		boolean active;
		synchronized (this) {
			active = this.creating == creation;
			if (active) {
				this.creating = null;
			}
		}
		if (active) {
			creation.future.completeExceptionally(failure);
			creation.releaseSubscribers();
		}
		return active;
	}

	private void requireOpenAndCurrent(BiomePreview.CacheKey key) {
		if (this.closed) {
			throw new CancellationException("Preview request pool is closed");
		}
		if (key.generation() < this.latestGeneration) {
			throw new CancellationException("A newer preview request already owns the screen");
		}
		if (key.generation() == this.latestGeneration && !key.equals(this.currentKey)) {
			throw new IllegalArgumentException(
				"One preview generation cannot describe two acquisition inputs"
			);
		}
	}

	private static RuntimeException propagate(Throwable failure) {
		if (failure instanceof RuntimeException runtime) {
			return runtime;
		}
		if (failure instanceof Error error) {
			throw error;
		}
		return new IllegalStateException("Preview request acquisition failed", failure);
	}

	private static void closeAfterFailure(
		IPreviewHandler.PreparedContext owner,
		Throwable failure
	) {
		try {
			owner.close();
		} catch (RuntimeException | Error cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

	private static final class Creation {
		private final BiomePreview.CacheKey key;
		private final CompletableFuture<IPreviewHandler.PreparedContext> future = new CompletableFuture<>();
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final List<BooleanSupplier> subscribers = new ArrayList<>();

		private Creation(BiomePreview.CacheKey key) {
			this.key = key;
		}

		private boolean matches(BiomePreview.CacheKey key) {
			return this.key.equals(key);
		}

		private synchronized void addSubscriber(BooleanSupplier subscriber) {
			if (!this.future.isDone()) {
				this.subscribers.add(subscriber);
			}
		}

		private synchronized boolean isCancelled() {
			if (this.cancelled.get()) {
				return true;
			}
			for (BooleanSupplier subscriber : this.subscribers) {
				if (!subscriber.getAsBoolean()) {
					return false;
				}
			}
			return !this.subscribers.isEmpty();
		}

		private void checkCancelled() {
			if (this.isCancelled()) {
				throw new CancellationException("Preview owner construction was superseded");
			}
		}

		private void cancel(String message) {
			this.cancelled.set(true);
			this.future.completeExceptionally(new CancellationException(message));
			this.releaseSubscribers();
		}

		private synchronized void releaseSubscribers() {
			this.subscribers.clear();
		}
	}
}
