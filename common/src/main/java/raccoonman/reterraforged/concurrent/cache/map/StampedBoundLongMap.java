package raccoonman.reterraforged.concurrent.cache.map;

import java.util.function.LongFunction;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.concurrent.locks.StampedLock;

public class StampedBoundLongMap<T> implements LongMap<T> {
	private int capacity;
	private StampedLock lock;
	private Long2ObjectLinkedOpenHashMap<T> map;

	public StampedBoundLongMap(int size) {
		this.capacity = size;
		this.lock = new StampedLock();
		this.map = new Long2ObjectLinkedOpenHashMap<>(size);
	}

	@Override
	public int size() {
		long stamp = this.lock.readLock();
		try {
			return this.map.size();
		} finally {
			this.lock.unlockRead(stamp);
		}
	}

	@Override
	public void clear() {
		long stamp = this.lock.writeLock();
		try {
			this.map.clear();
		} finally {
			this.lock.unlockWrite(stamp);
		}
	}

	@Override
	public void remove(long key) {
		long stamp = this.lock.writeLock();
		try {
			this.map.remove(key);
		} finally {
			this.lock.unlockWrite(stamp);
		}
	}

	@Override
	public void remove(long key, Consumer<T> consumer) {
		long stamp = this.lock.writeLock();
		T t;
		try {
			t = this.map.remove(key);
		} finally {
			this.lock.unlockWrite(stamp);
		}
		if (t != null) {
			consumer.accept(t);
		}
	}

	@Override
	public boolean remove(long key, T expected, Consumer<T> consumer) {
		long stamp = this.lock.writeLock();
		T removed = null;
		try {
			if (this.map.get(key) == expected) {
				removed = this.map.remove(key);
			}
		} finally {
			this.lock.unlockWrite(stamp);
		}
		if (removed != null) {
			consumer.accept(removed);
			return true;
		}
		return false;
	}

	@Override
	public int removeIf(Predicate<T> predicate) {
		return this.removeIf(predicate, ignored -> {});
	}

	@Override
	public int removeIf(Predicate<T> predicate, Consumer<T> removal) {
		List<T> removed = new ArrayList<>();
		long stamp = this.lock.writeLock();
		try {
			ObjectIterator<Long2ObjectMap.Entry<T>> iterator = this.map.long2ObjectEntrySet().fastIterator();
			while (iterator.hasNext()) {
				T value = iterator.next().getValue();
				if (predicate.test(value)) {
					iterator.remove();
					removed.add(value);
				}
			}
		} finally {
			this.lock.unlockWrite(stamp);
		}
		LongMap.acceptAll(removed, removal);
		return removed.size();
	}

	@Override
	public void put(long key, T t) {
		long stamp = this.lock.writeLock();
		try {
			this.map.put(key, t);
		} finally {
			this.lock.unlockWrite(stamp);
		}
	}

	@Override
	public T get(long key) {
		long stamp = this.lock.readLock();
		try {
			return this.map.get(key);
		} finally {
			this.lock.unlockRead(stamp);
		}
	}

	@Override
	public T computeIfAbsent(long key, LongFunction<T> func) {
		return this.computeIfAbsent(key, func, ignored -> {});
	}

	@Override
	public T computeIfAbsent(long key, LongFunction<T> func, Consumer<T> eviction) {
		return this.computeIfAbsent(key, func, ignored -> true, eviction);
	}

	@Override
	public T computeIfAbsent(
		long key,
		LongFunction<T> func,
		Predicate<T> evictable,
		Consumer<T> eviction
	) {
		long readStamp = this.lock.readLock();
		try {
			T t = this.map.get(key);
			if (t != null) {
				return t;
			}
		} finally {
			this.lock.unlockRead(readStamp);
		}
		long writeStamp = this.lock.writeLock();
		List<T> evicted = new ArrayList<>();
		T created = null;
		RuntimeException runtimeFailure = null;
		Error errorFailure = null;
		try {
			T existing = this.map.get(key);
			if (existing != null) {
				return existing;
			}
			try {
				created = func.apply(key);
				this.map.put(key, created);
				this.evictValuesToCount(this.capacity, evictable, evicted, key, true);
			} catch (RuntimeException failure) {
				runtimeFailure = failure;
			} catch (Error failure) {
				errorFailure = failure;
			}
		} finally {
			this.lock.unlockWrite(writeStamp);
		}
		LongMap.acceptAll(evicted, eviction);
		if (runtimeFailure != null) {
			throw runtimeFailure;
		}
		if (errorFailure != null) {
			throw errorFailure;
		}
		return created;
	}

	@Override
	public void trim(Predicate<T> evictable, Consumer<T> eviction) {
		List<T> evicted = new ArrayList<>();
		long stamp = this.lock.writeLock();
		try {
			this.evictValuesToCount(this.capacity, evictable, evicted, 0L, false);
		} finally {
			this.lock.unlockWrite(stamp);
		}
		LongMap.acceptAll(evicted, eviction);
	}

	private void evictValuesToCount(
		int targetCount,
		Predicate<T> evictable,
		List<T> evicted,
		long protectedKey,
		boolean protectKey
	) {
		while (this.map.size() > targetCount) {
			ObjectIterator<Long2ObjectMap.Entry<T>> iterator =
				this.map.long2ObjectEntrySet().fastIterator();
			boolean removed = false;
			while (iterator.hasNext()) {
				Long2ObjectMap.Entry<T> entry = iterator.next();
				if (protectKey && entry.getLongKey() == protectedKey) {
					continue;
				}
				T candidate = entry.getValue();
				if (evictable.test(candidate)) {
					iterator.remove();
					evicted.add(candidate);
					removed = true;
					break;
				}
			}
			if (!removed) {
				break;
			}
		}
	}
}
