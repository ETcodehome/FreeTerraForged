package etcodehome.freeterraforged.world.worldgen.cell.rivermap;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<Long, CompletableFuture<Rivermap>> inFlight = new ConcurrentHashMap<>();
    
    public RiverCache(RiverGenerator generator) {
        this.cache = new Cache<>(32, 5L, 1L, TimeUnit.MINUTES, StampedLongMap::new);
        this.generator = generator;
    }
    
    public Rivermap getRivers(int x, int z) {
        long key = PosUtil.pack(x, z);
        ThreadLocalCache tc = THREAD_CACHE.get();
        if (tc.owner.get() == this && tc.lastRivermap != null && tc.lastKey == key) {
            tc.lastRivermap.touch();
            return tc.lastRivermap;
        }

        Rivermap rivermap = this.cache.getIfOpen(key);
        if (rivermap == null) {
            rivermap = this.computeRivers(x, z, key);
        }

        rivermap.touch();
        tc.owner = new WeakReference<>(this);
        tc.lastKey = key;
        tc.lastRivermap = rivermap;
        return rivermap;
    }

    protected Rivermap computeRivers(int x, int z, long key) {
        CompletableFuture<Rivermap> promise = new CompletableFuture<>();
        CompletableFuture<Rivermap> existing = this.inFlight.putIfAbsent(key, promise);
        if (existing == null) {
            Rivermap rivermap;
            try {
                rivermap = this.cache.getIfOpen(key);
                if (rivermap == null) {
                    Rivermap generated = this.generateRivers(x, z, key);
                    Rivermap cached = this.cache.computeIfAbsentIfOpen(key, id -> generated);
                    rivermap = (cached != null) ? cached : generated;
                }
                promise.complete(rivermap);
                return rivermap;
            } catch (Throwable t) {
                promise.completeExceptionally(t);
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof Error err) throw err;
                throw new RuntimeException(t);
            } finally {
                this.inFlight.remove(key, promise);
            }
        } else {
            try {
                return existing.join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw ce;
            }
        }
    }

    protected Rivermap generateRivers(int x, int z, long key) {
        return this.generator.generateRivers(x, z, key);
    }

	@Override
	public void close() {
        this.inFlight.clear();
		this.cache.close();
	}
}
