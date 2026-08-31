package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.concurrent.atomic.AtomicReferenceArray;

import it.unimi.dsi.fastutil.HashCommon;

/**
 * Strictly bounded, owner-held exact cache with per-worker locality.
 *
 * <p>The shared table lets work migrate between threads. Lazily allocated locality tables prevent
 * unrelated worldgen workers from continually evicting one another's hot entries. Thread identity
 * selects only a cache stripe and never changes the computed value. All mutable storage remains
 * reachable from, and is retired with, the sampler/request owner; no thread-local state survives an
 * owner lifetime.</p>
 */
public final class OwnerThreadCache<V> {
	static final int STRIPE_COUNT = 32;
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
		private final AtomicReferenceArray<Entry<V>> values;

		private Table(int capacity) {
			this.values = new AtomicReferenceArray<>(capacity);
		}

		private V find(long key, int mask) {
			Entry<V> entry = this.values.get((int) HashCommon.mix(key) & mask);
			return entry != null && entry.key == key ? entry.value : null;
		}

		private void store(long key, V value, int mask) {
			this.values.set((int) HashCommon.mix(key) & mask, new Entry<>(key, value));
		}

		private void clear(int capacity) {
			for (int index = 0; index < capacity; index++) {
				this.values.set(index, null);
			}
		}
	}

	private record Entry<V>(long key, V value) {
	}
}
