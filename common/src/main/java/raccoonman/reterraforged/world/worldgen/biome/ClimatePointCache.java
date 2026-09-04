package raccoonman.reterraforged.world.worldgen.biome;

import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.world.worldgen.runtime.OwnerThreadCache;
import java.lang.ref.WeakReference;

/**
 * Sampler-owned memoization for climate samples. Keeping this cache at the sampler boundary lets
 * every biome selector observe the same point without caching or bypassing any selector's result.
 * Immutable entries and atomic replacement make concurrent generation against one sampler safe.
 */
public final class ClimatePointCache {

    private static final int SIZE = 1024; // Power of two for rapid bit-masking
    private final OwnerThreadCache<Entry> values = new OwnerThreadCache<>(SIZE);

    /**
     * @return the cached climate point for this sampler, or null on a cache miss.
     */
    public Climate.TargetPoint find(final Object sampler, final int x, final int y, final int z) {
        final long key = key(x, y, z);
		Entry entry = this.values.find(key);
		return entry != null && entry.get() == sampler ? entry.target : null;
    }

    /**
     * Stores a fully evaluated climate point in the active sampler slot.
     */
    public void store(final Object sampler, final int x, final int y, final int z, final Climate.TargetPoint target) {
        if (target == null) {
            return;
        }
        final long key = key(x, y, z);
		this.values.store(key, new Entry(sampler, target));
    }

	public void clear() {
		this.values.clear();
	}

    /**
     * Packs 3D quart coordinates safely into a single 64-bit primitive key.
     * Quart coordinates comfortably fit: |x|,|z| < 2^23, |y| < 2^15.
     */
    private static long key(final int x, final int y, final int z) {
        return ((long) (y & 0xFFFF) << 48) | ((long) (x & 0xFFFFFF) << 24) | (z & 0xFFFFFFL);
    }

    public ClimatePointCache() {}

	private static final class Entry extends WeakReference<Object> {
		private final Climate.TargetPoint target;

		private Entry(Object sampler, Climate.TargetPoint target) {
			super(sampler);
			this.target = target;
		}
	}

}
