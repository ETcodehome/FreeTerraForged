package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import it.unimi.dsi.fastutil.HashCommon;

public final class OwnerThreadCache<V> {
	static final int STRIPE_COUNT = 8;
	private static final int STRIPE_MASK = STRIPE_COUNT - 1;

	private final int capacity;
	private final int mask;
	private final Table<V> shared;
	private final AtomicReferenceArray<Table<V>> stripes =
		new AtomicReferenceArray<>(STRIPE_COUNT);

	public OwnerThreadCache(int capacity) {
		if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
			throw new IllegalArgumentException("Cache capacity must be a positive power of two");
		}
		this.capacity = capacity;
		this.mask = capacity - 1;
		this.shared = new Table<>(capacity);
	}

	public V find(long key) {
		Table<V> local = this.localTable();
		V value = local.find(key, this.mask);
		if (value != null) {
			return value;
		}
		value = this.shared.find(key, this.mask);
		if (value != null) {
			local.store(key, value, this.mask);
		}
		return value;
	}

	public void store(long key, V value) {
		if (value == null) {
			return;
		}
		this.localTable().store(key, value, this.mask);
		this.shared.store(key, value, this.mask);
	}

	public void clear() {
		this.shared.clear(this.capacity);
		for (int stripe = 0; stripe < STRIPE_COUNT; stripe++) {
			Table<V> table = this.stripes.get(stripe);
			if (table != null) {
				table.clear(this.capacity);
			}
		}
	}

	private Table<V> localTable() {
		int stripe = stripeIndex(Thread.currentThread().threadId());
		Table<V> table = this.stripes.get(stripe);
		if (table != null) {
			return table;
		}
		Table<V> created = new Table<>(this.capacity);
		if (this.stripes.compareAndSet(stripe, null, created)) {
			return created;
		}
		return this.stripes.get(stripe);
	}

	static int stripeIndex(long threadId) {
		return (int) threadId & STRIPE_MASK;
	}

	static int slot(long key, int capacity) {
		return (int) HashCommon.mix(key) & (capacity - 1);
	}

	private static final class Table<V> {
		private final AtomicLongArray sequences;
		private final long[] keys;
		private final AtomicReferenceArray<V> values;

		private Table(int capacity) {
			this.sequences = new AtomicLongArray(capacity);
			this.keys = new long[capacity];
			this.values = new AtomicReferenceArray<>(capacity);
		}

		private V find(long key, int mask) {
			int index = (int) HashCommon.mix(key) & mask;
			for (int attempt = 0; attempt < 3; attempt++) {
				long before = this.sequences.get(index);
				if ((before & 1L) != 0L) {
					Thread.onSpinWait();
					continue;
				}
				long storedKey = this.keys[index];
				V value = this.values.get(index);
				long after = this.sequences.get(index);
				if (before == after) {
					return value != null && storedKey == key ? value : null;
				}
			}
			return null;
		}

		private void store(long key, V value, int mask) {
			int index = (int) HashCommon.mix(key) & mask;
			long sequence = this.lock(index);
			this.keys[index] = key;
			this.values.set(index, value);
			this.sequences.set(index, sequence + 2L);
		}

		private void clear(int capacity) {
			for (int index = 0; index < capacity; index++) {
				long sequence = this.lock(index);
				this.values.set(index, null);
				this.sequences.set(index, sequence + 2L);
			}
		}

		private long lock(int index) {
			while (true) {
				long sequence = this.sequences.get(index);
				if ((sequence & 1L) == 0L
					&& this.sequences.compareAndSet(index, sequence, sequence + 1L)) {
					return sequence;
				}
				Thread.onSpinWait();
			}
		}
	}
}
