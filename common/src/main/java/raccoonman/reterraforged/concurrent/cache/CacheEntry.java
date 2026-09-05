package raccoonman.reterraforged.concurrent.cache;

import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import raccoonman.reterraforged.concurrent.task.LazyCallable;

public class CacheEntry<T> extends LazyCallable<T> implements ExpiringEntry {
	private volatile long timestamp;
	private Future<T> task;
	private final SafeCloseable producer;
	private final AtomicBoolean closed = new AtomicBoolean();

	public CacheEntry(Future<T> task) {
		this(task, task instanceof SafeCloseable owned ? owned : null);
	}

	private CacheEntry(Future<T> task, SafeCloseable producer) {
		this.task = task;
		this.producer = producer;
		this.timestamp = System.currentTimeMillis();
	}

	@Override
	public T get() {
		this.timestamp = System.currentTimeMillis();
		return super.get();
	}

	@Override
	public boolean isDone() {
		return this.task.isDone();
	}

	@Override
	public boolean canEvict() {
		return this.task.isDone();
	}

	@Override
	public long getTimestamp() {
		return this.timestamp;
	}

	@Override
	public void close() {
		if (!this.closed.compareAndSet(false, true)) {
			return;
		}
		T closing = this.value;
		if (closing == null && this.task.isDone() && !this.task.isCancelled()) {
			try {
				closing = this.get();
			} catch (RuntimeException | Error ignored) {
			}
			closeValue(closing);
			return;
		}
		if (closing != null) {
			closeValue(closing);
			return;
		}
		if (this.task instanceof CompletableFuture<?> future) {
			future.whenComplete((value, failure) -> {
				if (failure == null) {
					closeValue(value);
				}
			});
		}
		if (this.producer != null) {
			this.producer.close();
		}
	}

	private static void closeValue(Object value) {
		if (value instanceof SafeCloseable closeable) {
			closeable.close();
			return;
		}
		if (value instanceof AutoCloseable closeable) {
			try {
				closeable.close();
			} catch (Exception failure) {
				throw new IllegalStateException("Failed closing cached value", failure);
			}
		}
	}

	@Override
	protected T create() {
		if (this.task instanceof ForkJoinTask<T> task) {
			return task.join();
		}
		try {
			return this.task.get();
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			CancellationException cancellation = new CancellationException(
				"Interrupted waiting for cached computation"
			);
			cancellation.initCause(failure);
			throw cancellation;
		} catch (ExecutionException failure) {
			Throwable cause = failure.getCause() == null ? failure : failure.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException("Cached computation failed", cause);
		}
	}

	public static <T> CacheEntry<T> supply(Future<T> task) {
		return new CacheEntry<>(task);
	}

	public static <T> CacheEntry<T> supply(Future<T> task, SafeCloseable producer) {
		return new CacheEntry<>(task, producer);
	}
}
