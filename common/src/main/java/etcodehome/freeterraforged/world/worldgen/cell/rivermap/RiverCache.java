package etcodehome.freeterraforged.world.worldgen.cell.rivermap;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import etcodehome.freeterraforged.concurrent.cache.Cache;
import etcodehome.freeterraforged.concurrent.cache.map.StampedLongMap;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

public class RiverCache implements AutoCloseable {
    private static final class ThreadLocalCache {
        WeakReference<RiverCache> owner = new WeakReference<>(null);
        long lastKey = Long.MIN_VALUE;
        Rivermap lastRivermap;
    }

    private static final ThreadLocal<ThreadLocalCache> THREAD_CACHE = ThreadLocal.withInitial(ThreadLocalCache::new);

    protected RiverGenerator generator;
    protected Cache<Rivermap> cache;
    
    public RiverCache(RiverGenerator generator) {
        this.cache = new Cache<>(32, 5L, 1L, TimeUnit.MINUTES, StampedLongMap::new);
        this.generator = generator;
    }
    
    public Rivermap getRivers(int x, int z) {
        long key = PosUtil.pack(x, z);
        ThreadLocalCache tc = THREAD_CACHE.get();
        if (tc.owner.get() == this && tc.lastRivermap != null && tc.lastKey == key) {
            return tc.lastRivermap;
        }
        Rivermap rivermap = this.cache.computeIfAbsent(key, id -> {
        	return this.generator.generateRivers(PosUtil.unpackLeft(id), PosUtil.unpackRight(id), id);
        });
        tc.owner = new WeakReference<>(this);
        tc.lastKey = key;
        tc.lastRivermap = rivermap;
        return rivermap;
    }

	@Override
	public void close() {
		this.cache.close();
	}
}
