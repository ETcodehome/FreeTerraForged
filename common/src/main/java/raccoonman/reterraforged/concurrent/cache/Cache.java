package raccoonman.reterraforged.concurrent.cache;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntFunction;
import java.util.function.LongFunction;

import raccoonman.reterraforged.concurrent.cache.map.LongMap;

public class Cache<V extends ExpiringEntry> implements AutoCloseable {
	public static final ScheduledExecutorService SCHEDULER = createScheduler();

	private static ScheduledExecutorService createScheduler() {
		ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, (r) -> {
			Thread thread = new Thread(r);
			thread.setName("CacheScheduler");
			thread.setDaemon(true);
			return thread;
		});
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}
	
    private LongMap<V> map;
    private long lifetimeMS;
    private volatile long timeout;
    private ScheduledFuture<?> poll;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    
    public Cache(int capacity, long expireTime, long pollInterval, TimeUnit unit, IntFunction<LongMap<V>> mapFunc) {
        this.timeout = 0L;
        this.map = mapFunc.apply(capacity);
        this.lifetimeMS = unit.toMillis(expireTime);
        
        long intervalMillis = unit.toMillis(pollInterval);
        this.poll = SCHEDULER.scheduleAtFixedRate(this::poll, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }
    
    public void remove(long key) {
		this.lifecycle.readLock().lock();
		try {
			this.requireOpen();
			this.map.remove(key, ExpiringEntry::close);
		} finally {
			this.lifecycle.readLock().unlock();
		}
    }
    
    public V get(long key) {
		this.lifecycle.readLock().lock();
		try {
			this.requireOpen();
			return this.map.get(key);
		} finally {
			this.lifecycle.readLock().unlock();
		}
    }

	public V getIfOpen(long key) {
		this.lifecycle.readLock().lock();
		try {
			return this.closed.get() ? null : this.map.get(key);
		} finally {
			this.lifecycle.readLock().unlock();
		}
	}

	public boolean removeIfOpen(long key, V expected) {
		this.lifecycle.readLock().lock();
		try {
			if (this.closed.get()) {
				return false;
			}
			return this.map.remove(key, expected, ExpiringEntry::close);
		} finally {
			this.lifecycle.readLock().unlock();
		}
	}
    
    public V computeIfAbsent(long key, LongFunction<V> func) {
		this.lifecycle.readLock().lock();
		try {
			this.requireOpen();
			return this.map.computeIfAbsent(
				key, func, ExpiringEntry::canEvict, ExpiringEntry::close
			);
		} finally {
			this.lifecycle.readLock().unlock();
		}
    }
    
	public void poll() {
		this.lifecycle.readLock().lock();
		try {
			if (this.closed.get()) {
				return;
			}
			this.timeout = System.currentTimeMillis() - this.lifetimeMS;
			this.map.removeIf((entry) -> {
				if (!entry.canEvict()) {
					return false;
				}
				return entry.getTimestamp() < this.timeout;
			}, ExpiringEntry::close);
		} finally {
			this.lifecycle.readLock().unlock();
		}
    }

	@Override
	public void close() {
		this.lifecycle.writeLock().lock();
		try {
			if (!this.closed.compareAndSet(false, true)) {
				return;
			}
			this.poll.cancel(false);
			this.map.removeIf(entry -> true, ExpiringEntry::close);
		} finally {
			this.lifecycle.writeLock().unlock();
		}
	}

	public void trim() {
		this.lifecycle.readLock().lock();
		try {
			if (!this.closed.get()) {
				this.map.trim(ExpiringEntry::canEvict, ExpiringEntry::close);
			}
		} finally {
			this.lifecycle.readLock().unlock();
		}
	}

	private void requireOpen() {
		if (this.closed.get()) {
			throw new IllegalStateException("Cache is closed");
		}
	}
}
