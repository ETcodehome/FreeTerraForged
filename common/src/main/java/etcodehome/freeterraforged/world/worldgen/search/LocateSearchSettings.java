package etcodehome.freeterraforged.world.worldgen.search;

/** Shared policy for FTF locate commands and preview navigation. */
public record LocateSearchSettings(
	long timeoutSeconds,
	int workerThreads
) {
	public static final long DEFAULT_TIMEOUT_SECONDS = 60L;
	public static final int DEFAULT_WORKER_THREADS = Math.max(
		1, Runtime.getRuntime().availableProcessors() / 2
	);
	public static final int MAX_WORKER_THREADS = Math.max(
		1, Runtime.getRuntime().availableProcessors() * 2
	);

	public LocateSearchSettings {
		if (timeoutSeconds < 1L) {
			throw new IllegalArgumentException("timeoutSeconds must be positive");
		}
		if (workerThreads < 1 || workerThreads > MAX_WORKER_THREADS) {
			throw new IllegalArgumentException(
				"workerThreads must be between 1 and " + MAX_WORKER_THREADS
			);
		}
	}

	public static LocateSearchSettings defaults() {
		return new LocateSearchSettings(
			DEFAULT_TIMEOUT_SECONDS,
			DEFAULT_WORKER_THREADS
		);
	}
}
