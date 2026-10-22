package etcodehome.freeterraforged.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenBenchmarkRunner;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.RandomState;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class FTFCommands {

    private static final ExecutorService execPool = Executors.newVirtualThreadPerTaskExecutor();
    private static final AtomicReference<CompletableFuture> heightmapTask = new AtomicReference<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ftf")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("heightmap")
                        .then(Commands.literal("area")
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                .executes(context -> {
                                                                    int z1 = context.getArgument("z1", Integer.class);
                                                                    int x1 = context.getArgument("x1", Integer.class);
                                                                    int x2 = context.getArgument("x2", Integer.class);
                                                                    int z2 = context.getArgument("z2", Integer.class);
                                                                    ServerLevel serverLevel = deduceServerLevel(context.getSource());
                                                                    startHeightmapProcess(context, serverLevel, x1, z1, x2, z2);
                                                                    return 1;
                                                                }))))))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("blocks", IntegerArgumentType.integer())
                                        .executes(context -> {
                                            Integer blocks = context.getArgument("blocks", Integer.class);

                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayer();
                                            ServerLevel serverLevel = deduceServerLevel(source);

                                            int z1 = (player == null ? 0 : player.getBlockZ()) + blocks;
                                            int x1 = (player == null ? 0 : player.getBlockX()) + blocks;
                                            int x2 = (player == null ? 0 : player.getBlockX()) - blocks;
                                            int z2 = (player == null ? 0 : player.getBlockZ()) - blocks;

                                            startHeightmapProcess(context, serverLevel, x1, z1, x2, z2);

                                            return 1;
                                        }))))
                .then(Commands.literal("benchmark")
                        .executes(context -> {
                            return WorldgenBenchmarkRunner.startDefaultBenchmark(
                                context.getSource(), WorldgenBenchmarkRunner.DEFAULT_RADIUS
                            ) ? 1 : 0;
                        })
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(context -> {
                                    int radius = IntegerArgumentType.getInteger(context, "radius");
                                    return WorldgenBenchmarkRunner.startDefaultBenchmark(context.getSource(), radius) ? 1 : 0;
                                })
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> {
                                                    int radius = IntegerArgumentType.getInteger(context, "radius");
                                                    int x = IntegerArgumentType.getInteger(context, "x");
                                                    int z = IntegerArgumentType.getInteger(context, "z");
                                                    return WorldgenBenchmarkRunner.startBenchmark(context.getSource(), radius, x, z, false) ? 1 : 0;
                                                })))))
                .then(Commands.literal("perf")
                        .then(Commands.literal("start")
                                .executes(context -> {
                                    WorldgenPerfTracker.start();
                                    context.getSource().sendSystemMessage(Component.literal("[FTF-Perf] Started worldgen performance tracking."));
                                    return 1;
                                }))
                        .then(Commands.literal("stop")
                                .executes(context -> {
                                    WorldgenPerfTracker.stop();
                                    sendPerfReport(context.getSource(), "Manual profiling session stopped");
                                    return 1;
                                }))
                        .then(Commands.literal("report")
                                .executes(context -> {
                                    sendPerfReport(context.getSource(), "Live profiling snapshot");
                                    return 1;
                                }))
                        .then(Commands.literal("reset")
                                .executes(context -> {
                                    WorldgenPerfTracker.reset();
                                    context.getSource().sendSystemMessage(Component.literal("[FTF-Perf] Reset worldgen performance counters."));
                                    return 1;
                                })))
        );
    }

    private static void sendPerfReport(CommandSourceStack source, String title) {
        String report = WorldgenPerfTracker.buildReport(title);
        for (String line : report.split("\n")) {
            if (!line.isBlank()) {
                FTFCommon.LOGGER.info("[FTF-Perf] {}", line);
                source.sendSystemMessage(Component.literal(line));
            }
        }
        Path saved = WorldgenBenchmarkRunner.writeReportFile(report);
        if (saved != null) {
            source.sendSystemMessage(Component.literal("[FTF-Perf] Saved report to: " + saved.toAbsolutePath()));
        }
    }

    private static ServerLevel deduceServerLevel(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return source.getServer().overworld();
        }
        return (ServerLevel) player.getCommandSenderWorld();
    }

    private static void startHeightmapProcess(CommandContext<CommandSourceStack> context,
                                              ServerLevel level,
                                              int x1, int z1, int x2, int z2) {
        if (heightmapTask.get() != null) {
            context.getSource().sendFailure(Component.literal("Task already started"));
            return;
        }

        context.getSource().sendSystemMessage(Component.literal("Heightmap generation started in background..."));

        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            generateHeightmaps(context, level, x1, x2, z1, z2);
            return 0;
        }, execPool).exceptionallyAsync(throwable -> {
            throwable.printStackTrace();
            context.getSource().sendFailure(Component.literal("Heightmap generation failed: " + throwable.getMessage()));
            heightmapTask.set(null);
            return 0;
        }).thenApplyAsync(integer -> {
            heightmapTask.set(null);
            return 0;
        });
        heightmapTask.set(future);
    }

    private static void generateHeightmaps(CommandContext<CommandSourceStack> context,
                                           ServerLevel serverLevel,
                                           int x1, int x2,
                                           int z1, int z2) {
        long startTime = System.currentTimeMillis();

        RandomState randomState = serverLevel.getChunkSource().randomState();
        FTFRandomState ftfRandomState = (FTFRandomState) (Object) randomState;
        GeneratorContext generatorContext = ftfRandomState.generatorContext();

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int CHUNK = 512;
        int totalW = maxX - minX + 1;
        int totalH = maxZ - minZ + 1;
        int chunksX = (totalW + CHUNK - 1) / CHUNK;
        int chunksZ = (totalH + CHUNK - 1) / CHUNK;

        Path outputDir = Path.of("heightmap_chunks");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Phase 1: Generate heightmap sections safely in the background thread (no thread starvation)
        Cell cell = new Cell();
        for (int cx = 0; cx < chunksX; cx++) {
            for (int cz = 0; cz < chunksZ; cz++) {
                int startX = minX + cx * CHUNK;
                int startZ = minZ + cz * CHUNK;
                int w = Math.min(CHUNK, totalW - cx * CHUNK);
                int h = Math.min(CHUNK, totalH - cz * CHUNK);

                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
                WritableRaster raster = img.getRaster();

                for (int x = 0; x < w; x++) {
                    int worldX = startX + x;
                    for (int z = 0; z < h; z++) {
                        int worldZ = startZ + z;
                        generatorContext.lookup.applyCell(cell.reset(), worldX, worldZ, false, false);
                        float height = generatorContext.levels.getNormalizedElevation(cell.height);
                        int gray = (int) (Math.clamp(height, 0f, 1f) * 65535);
                        raster.setSample(x, z, 0, gray);
                    }
                }

                try {
                    File tempFile = outputDir.resolve(String.format("chunk_%d_%d.png", cx, cz)).toFile();
                    ImageIO.write(img, "png", tempFile);
                    context.getSource().sendSystemMessage(Component.literal("Generated heightmap " + cx + "," + cz));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        // Phase 2: Stitch heightmap sections into a single final image
        BufferedImage full = new BufferedImage(totalW, totalH, BufferedImage.TYPE_USHORT_GRAY);
        Graphics2D g2d = full.createGraphics();

        try {
            for (int cx = 0; cx < chunksX; cx++) {
                for (int cz = 0; cz < chunksZ; cz++) {
                    File chunkFile = outputDir.resolve(String.format("chunk_%d_%d.png", cx, cz)).toFile();
                    if (chunkFile.exists()) {
                        BufferedImage chunkImg = ImageIO.read(chunkFile);
                        if (chunkImg != null) {
                            g2d.drawImage(chunkImg, cx * CHUNK, cz * CHUNK, null);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to stitch heightmap images", e);
        } finally {
            g2d.dispose();
        }

        // Phase 3: Save the final stitched image with timing report
        long durationMs = System.currentTimeMillis() - startTime;
        double durationSec = durationMs / 1000.0;

        try {
            SimpleDateFormat fmt = new SimpleDateFormat("ddMMyyyyhhmm");
            File finalFile = new File(String.format("heightmap_%s.png", fmt.format(new Date())));
            ImageIO.write(full, "png", finalFile);
            context.getSource().sendSystemMessage(Component.literal(
                    String.format("Successfully generated %s in %.2f seconds", finalFile.getName(), durationSec)
            ));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write final heightmap", e);
        }

        // Phase 4: Clean up temporary files and directory
        for (int cx = 0; cx < chunksX; cx++) {
            for (int cz = 0; cz < chunksZ; cz++) {
                Path chunkPath = outputDir.resolve(String.format("chunk_%d_%d.png", cx, cz));
                try {
                    Files.deleteIfExists(chunkPath);
                } catch (IOException ignored) {
                }
            }
        }
        try {
            Files.deleteIfExists(outputDir);
        } catch (IOException ignored) {
        }
    }
}
