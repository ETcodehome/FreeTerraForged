package raccoonman.reterraforged.world.worldgen.biome;

import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Highly optimized, thread-local memoization cache for climate point samples.
 * Uses two independent direct-mapped hash tables to prevent chunk-filling sweeps
 * from evicting long-lived structure-probe lookups.
 */
public final class ClimatePointCache {

    private static final ThreadLocal<ClimatePointCache> TL = ThreadLocal.withInitial(ClimatePointCache::new);
    private static final int SIZE = 1024; // Power of two for rapid bit-masking
    private static final int MASK = SIZE - 1;

    private final Slot a = new Slot();
    private final Slot b = new Slot();
    private long stamp;

    /**
     * @return the cached biome holder for this context, or null on a cache miss.
     */
    public static Holder<Biome> find(final Object source, final Object sampler, final int x, final int y, final int z) {
        final ClimatePointCache c = TL.get();
        final Slot slot = c.slotFor(source, sampler);
        final long key = key(x, y, z);
        final int idx = (int) HashCommon.mix(key) & MASK;

        final Holder<Biome> val = slot.vals[idx];
        if (val != null && slot.keys[idx] == key) {
            return val; // Cache Hit!
        }
        return null;
    }

    /**
     * Stores a freshly evaluated biome holder in the active environment slot.
     */
    public static void store(final Object source, final Object sampler, final int x, final int y, final int z, final Holder<Biome> biome) {
        if (biome == null) {
            return;
        }
        final ClimatePointCache c = TL.get();
        final Slot slot = c.slotFor(source, sampler);
        final long key = key(x, y, z);
        final int idx = (int) HashCommon.mix(key) & MASK;

        slot.keys[idx] = key;
        slot.vals[idx] = biome;
    }

    /**
     * Packs 3D quart coordinates safely into a single 64-bit primitive key.
     * Quart coordinates comfortably fit: |x|,|z| < 2^23, |y| < 2^15.
     */
    private static long key(final int x, final int y, final int z) {
        return ((long) (y & 0xFFFF) << 48) | ((long) (x & 0xFFFFFF) << 24) | (z & 0xFFFFFFL);
    }

    private Slot slotFor(final Object source, final Object sampler) {
        this.stamp++;
        if (this.a.source == source && this.a.sampler == sampler) {
            this.a.lastUse = this.stamp;
            return this.a;
        }
        if (this.b.source == source && this.b.sampler == sampler) {
            this.b.lastUse = this.stamp;
            return this.b;
        }
        // Evict the least recently used table and rebind it to this environment
        final Slot evict = this.a.lastUse <= this.b.lastUse ? this.a : this.b;
        evict.rebind(source, sampler);
        evict.lastUse = this.stamp;
        return evict;
    }

    private static final class Slot {
        Object source;
        Object sampler;
        final long[] keys = new long[SIZE];
        @SuppressWarnings("unchecked")
        final Holder<Biome>[] vals = new Holder[SIZE];
        long lastUse;

        void rebind(final Object newSource, final Object newSampler) {
            Arrays.fill(this.vals, null); // Instantly clears active values safely
            this.source = newSource;
            this.sampler = newSampler;
        }
    }

    private ClimatePointCache() {}
}