package raccoonman.reterraforged.world.worldgen.biome;

import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;
import net.minecraft.world.level.biome.Climate;

/**
 * Thread-local memoization for climate samples. Keeping this cache at the sampler boundary lets
 * every biome selector observe the same point without caching or bypassing any selector's result.
 */
public final class ClimatePointCache {

    private static final ThreadLocal<ClimatePointCache> TL = ThreadLocal.withInitial(ClimatePointCache::new);
    private static final int SIZE = 1024; // Power of two for rapid bit-masking
    private static final int MASK = SIZE - 1;

    private final Slot a = new Slot();
    private final Slot b = new Slot();
    private long stamp;

    /**
     * @return the cached climate point for this sampler, or null on a cache miss.
     */
    public static Climate.TargetPoint find(final Object sampler, final int x, final int y, final int z) {
        final ClimatePointCache c = TL.get();
        final Slot slot = c.slotFor(sampler);
        final long key = key(x, y, z);
        final int idx = (int) HashCommon.mix(key) & MASK;

        final Climate.TargetPoint val = slot.vals[idx];
        if (val != null && slot.keys[idx] == key) {
            return val; // Cache Hit!
        }
        return null;
    }

    /**
     * Stores a fully evaluated climate point in the active sampler slot.
     */
    public static void store(final Object sampler, final int x, final int y, final int z, final Climate.TargetPoint target) {
        if (target == null) {
            return;
        }
        final ClimatePointCache c = TL.get();
        final Slot slot = c.slotFor(sampler);
        final long key = key(x, y, z);
        final int idx = (int) HashCommon.mix(key) & MASK;

        slot.keys[idx] = key;
        slot.vals[idx] = target;
    }

    /**
     * Packs 3D quart coordinates safely into a single 64-bit primitive key.
     * Quart coordinates comfortably fit: |x|,|z| < 2^23, |y| < 2^15.
     */
    private static long key(final int x, final int y, final int z) {
        return ((long) (y & 0xFFFF) << 48) | ((long) (x & 0xFFFFFF) << 24) | (z & 0xFFFFFFL);
    }

    private Slot slotFor(final Object sampler) {
        this.stamp++;
        if (this.a.sampler == sampler) {
            this.a.lastUse = this.stamp;
            return this.a;
        }
        if (this.b.sampler == sampler) {
            this.b.lastUse = this.stamp;
            return this.b;
        }
        // Evict the least recently used table and rebind it to this environment
        final Slot evict = this.a.lastUse <= this.b.lastUse ? this.a : this.b;
        evict.rebind(sampler);
        evict.lastUse = this.stamp;
        return evict;
    }

    private static final class Slot {
        Object sampler;
        final long[] keys = new long[SIZE];
        final Climate.TargetPoint[] vals = new Climate.TargetPoint[SIZE];
        long lastUse;

        void rebind(final Object newSampler) {
            Arrays.fill(this.vals, null); // Instantly clears active values safely
            this.sampler = newSampler;
        }
    }

    private ClimatePointCache() {}
}
