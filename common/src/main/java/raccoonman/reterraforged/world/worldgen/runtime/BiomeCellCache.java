package raccoonman.reterraforged.world.worldgen.runtime;

/** Sampler-owned exact memoization of the 2D FTF cell/provider domain for a quart column. */
public final class BiomeCellCache<O> {
	private static final int CAPACITY = 1024;

	private final OwnerThreadCache<Entry<O>> values =
		new OwnerThreadCache<>(CAPACITY);

	public WorldgenPlans.SpatialResult find(O owner, int quartX, int quartZ) {
		Entry<O> entry = this.values.find(key(quartX, quartZ));
		return entry != null && entry.owner == owner ? entry.value : null;
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

	private record Entry<O>(O owner, WorldgenPlans.SpatialResult value) {
	}
}
