package etcodehome.freeterraforged.world.worldgen.perf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Coordinates repeatable in-game and headless worldgen benchmarks.
 *
 * <p>Can be triggered via:
 * <ul>
 *   <li>{@code /ftf benchmark [radius] [x] [z]} in-game or from server console</li>
 *   <li>JVM property {@code -Dfreeterraforged.benchmark.auto=true} on headless dedicated server startup</li>
 * </ul>
 */
public final class WorldgenBenchmarkRunner {
    private static final TicketType<Unit> BENCHMARK_TICKET =
        TicketType.create("ftf_benchmark", (a, b) -> 0);

    private static final AtomicInteger AUTO_OFFSET_COUNTER = new AtomicInteger(0);
    private static volatile ActiveBenchmark activeBenchmark;
    private static volatile boolean autoBenchmarkTriggered = false;

    private WorldgenBenchmarkRunner() {}

    public static boolean isRunning() {
        return activeBenchmark != null;
    }

    /**
     * Starts a benchmark at a fresh deterministic coordinate offset (default radius 6 -> 13x13 = 169 chunks).
     */
    public static boolean startDefaultBenchmark(CommandSourceStack source, int radius) {
        int runIndex = AUTO_OFFSET_COUNTER.getAndIncrement();
        int targetX = 100_000 + runIndex * 50_000;
        int targetZ = 100_000 + runIndex * 50_000;
        return startBenchmark(source, radius, targetX, targetZ, false);
    }

    public static boolean startBenchmark(
        CommandSourceStack source,
        int radius,
        int blockX,
        int blockZ,
        boolean shutdownOnComplete
    ) {
        if (activeBenchmark != null) {
            source.sendFailure(Component.literal("[FTF-Perf] A benchmark is already in progress."));
            return false;
        }
        int clampedRadius = Math.max(1, Math.min(radius, 24));
        ServerPlayer player = source.getPlayer();
        ServerLevel level = player != null ? (ServerLevel) player.getCommandSenderWorld() : source.getServer().overworld();

        // Teleport player if invoked by a player so client/server chunk tracking moves to the fresh coordinates
        if (player != null) {
            int surfaceY = level.getChunkSource().getGenerator().getBaseHeight(
                blockX,
                blockZ,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                level,
                level.getChunkSource().randomState()
            );
            int safeY = Math.max(level.getSeaLevel() + 2, Math.min(315, surfaceY + 3));
            player.teleportTo(level, blockX + 0.5D, safeY, blockZ + 0.5D, player.getYRot(), player.getXRot());
            source.sendSystemMessage(Component.literal(String.format(
                "[FTF-Perf] Teleported to (%d, %d, %d) for fresh chunk generation.",
                blockX, safeY, blockZ
            )));
        }

        int centerChunkX = SectionPosToChunk(blockX);
        int centerChunkZ = SectionPosToChunk(blockZ);
        int diameter = clampedRadius * 2 + 1;
        int totalChunks = diameter * diameter;

        String startMsg = String.format(
            "[FTF-Perf] Starting benchmark: radius=%d (%dx%d = %d FULL chunks) centered at block (%d, %d) [chunk (%d, %d)]...",
            clampedRadius, diameter, diameter, totalChunks, blockX, blockZ, centerChunkX, centerChunkZ
        );
        source.sendSystemMessage(Component.literal(startMsg));
        FTFCommon.LOGGER.info(startMsg);

        ServerChunkCache chunkSource = level.getChunkSource();
        List<ChunkPos> positions = new ArrayList<>(totalChunks);
        List<CompletableFuture<ChunkResult<ChunkAccess>>> futures = new ArrayList<>(totalChunks);

        // Start performance tracker right before scheduling chunk generation
        WorldgenPerfTracker.start();
        long startWallNanos = System.nanoTime();

        for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
            for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
                int cx = centerChunkX + dx;
                int cz = centerChunkZ + dz;
                ChunkPos pos = new ChunkPos(cx, cz);
                positions.add(pos);
                chunkSource.addRegionTicket(BENCHMARK_TICKET, pos, 0, Unit.INSTANCE);
            }
        }

        for (ChunkPos pos : positions) {
            futures.add(chunkSource.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true));
        }

        activeBenchmark = new ActiveBenchmark(
            source,
            level,
            positions,
            futures,
            clampedRadius,
            blockX,
            blockZ,
            startWallNanos,
            shutdownOnComplete
        );
        return true;
    }

    private static int SectionPosToChunk(int blockCoord) {
        return blockCoord >> 4;
    }

    /**
     * Called every server tick from {@code MixinMinecraftServer}.
     */
    public static void onServerTick(MinecraftServer server) {
        if (!autoBenchmarkTriggered && Boolean.getBoolean("freeterraforged.benchmark.auto")) {
            if (server.getTickCount() >= 20) {
                autoBenchmarkTriggered = true;
                int radius = Integer.getInteger("freeterraforged.benchmark.radius", 6);
                int x = Integer.getInteger("freeterraforged.benchmark.x", 100_000);
                int z = Integer.getInteger("freeterraforged.benchmark.z", 100_000);
                boolean exitOnComplete = Boolean.parseBoolean(
                    System.getProperty("freeterraforged.benchmark.exit", "true")
                );
                FTFCommon.LOGGER.info(
                    "[FTF-Perf] Auto-benchmark triggered via JVM properties (radius={}, x={}, z={}, exit={})",
                    radius, x, z, exitOnComplete
                );
                startBenchmark(server.createCommandSourceStack(), radius, x, z, exitOnComplete);
            }
        }

        ActiveBenchmark current = activeBenchmark;
        if (current == null) {
            return;
        }

        ServerChunkCache chunkSource = current.level.getChunkSource();

        int completed = 0;
        int total = current.futures.size();
        for (int i = 0; i < total; i++) {
            if (current.futures.get(i).isDone()) {
                completed++;
            }
        }

        long now = System.nanoTime();
        if (completed < total) {
            if (now - current.lastProgressLogNanos >= 2_000_000_000L) {
                current.lastProgressLogNanos = now;
                double elapsedSec = (now - current.startWallNanos) / 1_000_000_000.0;
                double pct = (completed * 100.0) / total;
                double cps = elapsedSec > 0.0 ? completed / elapsedSec : 0.0;
                String progressMsg = String.format(
                    "[FTF-Perf] Benchmark progress: %d / %d FULL chunks (%.1f%%) in %.2f s (%.2f chunks/s)",
                    completed, total, pct, elapsedSec, cps
                );
                current.source.sendSystemMessage(Component.literal(progressMsg));
                FTFCommon.LOGGER.info(progressMsg);
            }
            return;
        }

        // All target FULL chunks have finished generating!
        WorldgenPerfTracker.stop();
        activeBenchmark = null;

        // Release benchmark tickets
        for (ChunkPos pos : current.positions) {
            chunkSource.removeRegionTicket(BENCHMARK_TICKET, pos, 0, Unit.INSTANCE);
        }

        String header = String.format(
            "Benchmark Target: radius=%d (%d FULL chunks) at block (%d, %d)",
            current.radius, total, current.blockX, current.blockZ
        );
        String report = WorldgenPerfTracker.buildReport(header, total, total);

        // Log full report to server console/log
        for (String line : report.split("\n")) {
            if (!line.isBlank()) {
                FTFCommon.LOGGER.info("[FTF-Perf] {}", line);
            }
        }

        // Send concise summary lines to command source (player chat if in-game)
        if (current.source.getPlayer() != null) {
            for (String line : report.split("\n")) {
                if (!line.isBlank()) {
                    current.source.sendSystemMessage(Component.literal(line));
                }
            }
        }

        // Write report to disk under perf-reports/
        Path reportPath = writeReportFile(report);
        if (reportPath != null) {
            String savedMsg = "[FTF-Perf] Saved full report to: " + reportPath.toAbsolutePath();
            FTFCommon.LOGGER.info(savedMsg);
            current.source.sendSystemMessage(Component.literal(savedMsg));
        }

        if (current.shutdownOnComplete) {
            FTFCommon.LOGGER.info("[FTF-Perf] Auto-benchmark finished; initiating clean server shutdown.");
            server.halt(false);
            Thread exitWatchdog = new Thread(() -> {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException ignored) {
                }
                Runtime.getRuntime().halt(0);
            }, "FTF-Benchmark-Exit");
            exitWatchdog.setDaemon(true);
            exitWatchdog.start();
        }
    }

    public static Path writeReportFile(String report) {
        try {
            Path dir = Path.of("perf-reports");
            Files.createDirectories(dir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path timestamped = dir.resolve("ftf-perf-" + timestamp + ".txt");
            Path latest = dir.resolve("ftf-perf-latest.txt");
            Files.writeString(timestamped, report, StandardCharsets.UTF_8);
            Files.writeString(latest, report, StandardCharsets.UTF_8);
            return latest;
        } catch (IOException e) {
            FTFCommon.LOGGER.error("[FTF-Perf] Failed to write performance report to disk", e);
            return null;
        }
    }

    private static final class ActiveBenchmark {
        final CommandSourceStack source;
        final ServerLevel level;
        final List<ChunkPos> positions;
        final List<CompletableFuture<ChunkResult<ChunkAccess>>> futures;
        final int radius;
        final int blockX;
        final int blockZ;
        final long startWallNanos;
        final boolean shutdownOnComplete;
        long lastProgressLogNanos;

        ActiveBenchmark(
            CommandSourceStack source,
            ServerLevel level,
            List<ChunkPos> positions,
            List<CompletableFuture<ChunkResult<ChunkAccess>>> futures,
            int radius,
            int blockX,
            int blockZ,
            long startWallNanos,
            boolean shutdownOnComplete
        ) {
            this.source = source;
            this.level = level;
            this.positions = positions;
            this.futures = futures;
            this.radius = radius;
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.startWallNanos = startWallNanos;
            this.shutdownOnComplete = shutdownOnComplete;
            this.lastProgressLogNanos = startWallNanos;
        }
    }
}
