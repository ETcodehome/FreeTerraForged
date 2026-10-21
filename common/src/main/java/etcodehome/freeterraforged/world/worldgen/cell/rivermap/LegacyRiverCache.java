package etcodehome.freeterraforged.world.worldgen.cell.rivermap;

import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;

public class LegacyRiverCache extends RiverCache {
    private static final class ThreadLocalCache {
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        Rivermap lastRivermap;
    }

    private static final ThreadLocal<ThreadLocalCache> THREAD_CACHE = ThreadLocal.withInitial(ThreadLocalCache::new);

    public LegacyRiverCache(RiverGenerator generator) {
        super(generator);
    }
    
    @Override
    public Rivermap getRivers(int x, int z) {
        ThreadLocalCache tc = THREAD_CACHE.get();
        if (tc.lastRivermap != null && tc.lastX == x && tc.lastZ == z) {
            return tc.lastRivermap;
        }
        Rivermap rivermap = this.cache.computeIfAbsent(NoiseUtil.seed(x, z), id -> this.generator.generateRivers(x, z, id));
        tc.lastX = x;
        tc.lastZ = z;
        tc.lastRivermap = rivermap;
        return rivermap;
    }
}
