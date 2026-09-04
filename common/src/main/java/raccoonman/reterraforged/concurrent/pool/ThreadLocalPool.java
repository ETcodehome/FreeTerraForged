package raccoonman.reterraforged.concurrent.pool;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import raccoonman.reterraforged.concurrent.Resource;

public final class ThreadLocalPool<T> {
	private final int capacity;
	private final Supplier<T> factory;
	private final Consumer<T> cleaner;
	private final ThreadLocal<Pool<T>> local;

	public ThreadLocalPool(int capacity, Supplier<T> factory) {
		this(capacity, factory, ignored -> {
		});
	}

	public ThreadLocalPool(int capacity, Supplier<T> factory, Consumer<T> cleaner) {
		if (capacity < 0) {
			throw new IllegalArgumentException("Thread-local pool capacity must be non-negative");
		}
		this.capacity = capacity;
		this.factory = Objects.requireNonNull(factory, "factory");
		this.cleaner = Objects.requireNonNull(cleaner, "cleaner");
		this.local = ThreadLocal.withInitial(this::createPool);
	}

	public Resource<T> get() {
		return this.local.get().acquire();
	}

	private Pool<T> createPool() {
		return new Pool<>(this.capacity, this.factory, this.cleaner);
	}

	private static final class Pool<T> {
		private final int capacity;
		private final Supplier<T> factory;
		private final Consumer<T> cleaner;
		private final ArrayDeque<T> idle;

		private Pool(int capacity, Supplier<T> factory, Consumer<T> cleaner) {
			this.capacity = capacity;
			this.factory = factory;
			this.cleaner = cleaner;
			this.idle = new ArrayDeque<>(capacity);
		}

		private synchronized Resource<T> acquire() {
			T value = this.idle.pollLast();
			if (value == null) {
				value = Objects.requireNonNull(this.factory.get(), "Pool factory returned null");
			}
			return new Lease<>(value, this);
		}

		private synchronized void restore(T value) {
			this.cleaner.accept(value);
			if (this.idle.size() < this.capacity) {
				this.idle.addLast(value);
			}
		}
	}

	private static final class Lease<T> implements Resource<T> {
		private final T value;
		private final Pool<T> pool;
		private final AtomicBoolean closed = new AtomicBoolean();

		private Lease(T value, Pool<T> pool) {
			this.value = value;
			this.pool = pool;
		}

		@Override
		public T get() {
			if (this.closed.get()) {
				throw new IllegalStateException("Pooled resource lease is closed");
			}
			return this.value;
		}

		@Override
		public boolean isOpen() {
			return !this.closed.get();
		}

		@Override
		public void close() {
			if (this.closed.compareAndSet(false, true)) {
				this.pool.restore(this.value);
			}
		}
	}
}
