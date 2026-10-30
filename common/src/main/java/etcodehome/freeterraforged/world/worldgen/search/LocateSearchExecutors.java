package etcodehome.freeterraforged.world.worldgen.search;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared, bounded concurrency for server-side FTF locate work. */
public final class LocateSearchExecutors {
	private static final int WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
	private static final ThreadPoolExecutor PARALLEL = createExecutor(WORKERS, "FTF-Locate-");
	private static final ThreadPoolExecutor SERIAL = createExecutor(1, "FTF-Locate-Serial-");
	private static final ScheduledThreadPoolExecutor TIMER = createTimer();

	private LocateSearchExecutors() {
	}

	public static int parallelism() {
		return WORKERS;
	}

	public static Executor parallel() {
		return PARALLEL;
	}

	public static Executor serial() {
		return SERIAL;
	}

	public static ScheduledExecutorService timer() {
		return TIMER;
	}

	private static ThreadPoolExecutor createExecutor(int workers, String prefix) {
		AtomicInteger threads = new AtomicInteger();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(
			workers,
			workers,
			30L,
			TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(),
			task -> {
				Thread thread = new Thread(task, prefix + threads.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			}
		);
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

	private static ScheduledThreadPoolExecutor createTimer() {
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
			Thread thread = new Thread(task, "FTF-Locate-Timer");
			thread.setDaemon(true);
			return thread;
		});
		executor.setRemoveOnCancelPolicy(true);
		executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		return executor;
	}
}
