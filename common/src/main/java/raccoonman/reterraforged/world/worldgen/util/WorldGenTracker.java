package raccoonman.reterraforged.world.worldgen.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public final class WorldGenTracker {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Thread-safe high-performance primitives
    public static final LongAdder TOTAL_NANOS = new LongAdder();
    public static final LongAdder TOTAL_CHUNKS = new LongAdder();
    public static final AtomicInteger ACTIVE_THREADS = new AtomicInteger(0);
    public static final AtomicInteger PEAK_CONCURRENCY = new AtomicInteger(0);
    public static final AtomicLong FIRST_START_NANOS = new AtomicLong(0);
    public static final AtomicLong LAST_END_NANOS = new AtomicLong(0);

    static {
        // Registers a thread that the JVM runs automatically when the game exits safely
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            long chunks = TOTAL_CHUNKS.sum();
            long totalMs = TOTAL_NANOS.sum() / 1_000_000; // Summed CPU-busy time across all threads

            if (chunks > 0 && totalMs > 0) {
                double avgMs = (double) totalMs / chunks;
                double chunksPerThreadSecond = ((double) chunks * 1000.0) / totalMs;

                int peakConcurrency = PEAK_CONCURRENCY.get();
                long wallClockMs = (LAST_END_NANOS.get() - FIRST_START_NANOS.get()) / 1_000_000;
                double realChunksPerSecond = wallClockMs > 0 ? ((double) chunks * 1000.0) / wallClockMs : 0.0;
                double idealChunksPerSecond = (1000.0 / avgMs) * peakConcurrency;
                double efficiency = idealChunksPerSecond > 0 ? (realChunksPerSecond / idealChunksPerSecond) * 100.0 : 0.0;

                LOGGER.info("""
                        =========================================================
                         ReTerraForged Native World Gen Performance Report
                         Total Chunks Generated   : {}
                         Total Thread Time Spent  : {} ms
                         Thread Time Per Chunk    : {} ms
                         Chunks per Thread Second : {}
                         Peak Threads In Use      : {}
                         Wall Clock Time          : {} ms
                         Real Chunks Per Second   : {}
                         Theoretical Max/Second   : {}
                         Parallel Efficiency      : {}%
                        =========================================================""",
                        chunks,
                        totalMs,
                        String.format("%.2f", avgMs),
                        String.format("%.2f", chunksPerThreadSecond),
                        peakConcurrency,
                        wallClockMs,
                        String.format("%.2f", realChunksPerSecond),
                        String.format("%.2f", idealChunksPerSecond),
                        String.format("%.1f", efficiency));
            }
        }, "RTF-Profiler-Shutdown-Hook"));
    }
}