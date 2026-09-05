package raccoonman.reterraforged.concurrent.cache.map;

import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

public class StampedLongMap<T> implements LongMap<T> {
	private StampedLock lock;
	private Long2ObjectOpenHashMap<T> map;

	public StampedLongMap(int size) {
		this.map = new Long2ObjectOpenHashMap<>(size);
		this.lock = new StampedLock();
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
		try {
			return this.map.computeIfAbsent(key, func);
		} finally {
			this.lock.unlockWrite(writeStamp);
		}
	}
}
