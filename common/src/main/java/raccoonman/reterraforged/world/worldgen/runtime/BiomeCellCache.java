package raccoonman.reterraforged.world.worldgen.runtime;

import java.lang.ref.WeakReference;

/** Sampler-owned exact memoization of the 2D FTF cell/provider domain for a quart column. */
public final class BiomeCellCache<O> {
	private static final int CAPACITY = 1024;

	private final OwnerThreadCache<Entry<O>> values =
		new OwnerThreadCache<>(CAPACITY);

	public WorldgenPlans.SpatialResult find(O owner, int quartX, int quartZ) {
		Entry<O> entry = this.values.find(key(quartX, quartZ));
		return entry != null && entry.get() == owner ? entry.value : null;
	}

	public void store(O owner, int quartX, int quartZ, WorldgenPlans.SpatialResult value) {
		if (value != null) {
			this.values.store(key(quartX, quartZ), new Entry<>(owner, value));
		}
	}

	public void clear() {
		this.values.clear();
	}

	static int slot(int quartX, int quartZ) {
		return OwnerThreadCache.slot(key(quartX, quartZ), CAPACITY);
	}

	private static long key(int quartX, int quartZ) {
		return ((long) quartX << 32) ^ (quartZ & 0xFFFFFFFFL);
	}

	private static final class Entry<O> extends WeakReference<O> {
		private final WorldgenPlans.SpatialResult value;

		private Entry(O owner, WorldgenPlans.SpatialResult value) {
			super(owner);
			this.value = value;
		}
	}
}
