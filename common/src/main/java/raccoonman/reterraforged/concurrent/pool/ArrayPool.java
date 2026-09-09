package raccoonman.reterraforged.concurrent.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import raccoonman.reterraforged.concurrent.Resource;

public class ArrayPool<T> {
	private int capacity;
	private IntFunction<T[]> constructor;
	private List<Item<T>> pool;
	private Object lock;
	private boolean closed;

	public ArrayPool(int size, IntFunction<T[]> constructor) {
		if (size < 0) {
			throw new IllegalArgumentException("Array pool capacity must be non-negative");
		}
		this.lock = new Object();
		this.capacity = size;
		this.constructor = Objects.requireNonNull(constructor, "constructor");
		this.pool = new ArrayList<>(size);
	}

	public Resource<T[]> get(int arraySize) {
		if (arraySize < 0) {
			throw new IllegalArgumentException("Array size must be non-negative");
		}
		synchronized (this.lock) {
			if (this.closed) {
				throw new IllegalStateException("Array pool is closed");
			}
			if (this.pool.size() > 0) {
				Item<T> resource = this.pool.remove(this.pool.size() - 1);
				if (resource.get().length >= arraySize) {
					return resource.retain();
				}
			}
		}
		T[] constructed = Objects.requireNonNull(
			this.constructor.apply(arraySize), "Array constructor returned null"
		);
		if (constructed.length < arraySize) {
			throw new IllegalStateException(
				"Array constructor returned length " + constructed.length
					+ " for requested size " + arraySize
			);
		}
		return new Item<>(constructed, this);
	}

	private boolean restore(Item<T> item) {
		synchronized (this.lock) {
			if (!this.closed && this.pool.size() < this.capacity) {
				this.pool.add(item);
				return true;
			}
		}
		return false;
	}

	public void close() {
		synchronized (this.lock) {
			if (this.closed) {
				return;
			}
			this.closed = true;
			this.pool.clear();
		}
	}

	int retainedSize() {
		synchronized (this.lock) {
			return this.pool.size();
		}
	}

	public static <T> ArrayPool<T> of(int size, IntFunction<T[]> constructor) {
		return new ArrayPool<>(size, constructor);
	}

	public static <T> ArrayPool<T> of(int size, Supplier<T> supplier, IntFunction<T[]> constructor) {
		return new ArrayPool<>(size, new ArrayConstructor<>(supplier, constructor));
	}

	public static class Item<T> implements Resource<T[]> {
		private final T[] value;
		private final ArrayPool<T> pool;
		private final AtomicBoolean released = new AtomicBoolean();

		private Item(T[] value, ArrayPool<T> pool) {
			this.value = value;
			this.pool = pool;
		}

		@Override
		public T[] get() {
			return this.value;
		}

		@Override
		public boolean isOpen() {
			return !this.released.get();
		}

		@Override
		public void close() {
			if (this.released.compareAndSet(false, true)) {
				this.pool.restore(this);
			}
		}

		private Item<T> retain() {
			if (!this.released.compareAndSet(true, false)) {
				throw new IllegalStateException("Array pool returned an active resource");
			}
			return this;
		}
	}

	private static class ArrayConstructor<T> implements IntFunction<T[]> {
		private Supplier<T> element;
		private IntFunction<T[]> array;

		private ArrayConstructor(Supplier<T> element, IntFunction<T[]> array) {
			this.element = element;
			this.array = array;
		}

		@Override
		public T[] apply(int size) {
			T[] t = this.array.apply(size);
			for (int i = 0; i < t.length; ++i) {
				t[i] = this.element.get();
			}
			return t;
		}
	}
}
