package raccoonman.reterraforged.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPools {
	public static final ExecutorService WORLD_GEN = Executors.newFixedThreadPool(
		availableProcessors(), namedDaemonFactory("RTF Worldgen")
	);
	public static final ExecutorService TILE_ADMISSION = Executors.newThreadPerTaskExecutor(
		Thread.ofVirtual().name("RTF Tile Admission-", 0L).factory()
	);

	public static int availableProcessors() {
		return Math.max(2, Runtime.getRuntime().availableProcessors());
	}

	public static int previewParallelism() {
		return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
	}

	private static ThreadFactory namedDaemonFactory(String prefix) {
		AtomicInteger sequence = new AtomicInteger();
		return task -> {
			Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
