package etcodehome.freeterraforged.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainMapGenerator;
import etcodehome.freeterraforged.server.command.LocateTerrainCommand;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenBenchmarkRunner;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
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
    private static final AtomicReference<CompletableFuture> reliefmapTask = new AtomicReference<>();

    public static CompletableFuture<?> getCurrentHeightmapTask() {
        return heightmapTask.get();
    }

    public static CompletableFuture<?> getCurrentReliefmapTask() {
        return reliefmapTask.get();
    }

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
                                            BlockPos center = player != null ? player.blockPosition() :
                                                    (source.getEntity() != null ? source.getEntity().blockPosition() : serverLevel.getSharedSpawnPos());

                                            int z1 = center.getZ() + blocks;
                                            int x1 = center.getX() + blocks;
                                            int x2 = center.getX() - blocks;
                                            int z2 = center.getZ() - blocks;

                                            startHeightmapProcess(context, serverLevel, x1, z1, x2, z2);

                                            return 1;
                                        }))))
                .then(Commands.literal("reliefmap")
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
                                                                    startReliefmapProcess(context, serverLevel, x1, z1, x2, z2);
                                                                    return 1;
                                                                }))))))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("blocks", IntegerArgumentType.integer())
                                        .executes(context -> {
                                            Integer blocks = context.getArgument("blocks", Integer.class);

                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayer();
                                            ServerLevel serverLevel = deduceServerLevel(source);
                                            BlockPos center = player != null ? player.blockPosition() :
                                                    (source.getEntity() != null ? source.getEntity().blockPosition() : serverLevel.getSharedSpawnPos());

                                            int z1 = center.getZ() + blocks;
                                            int x1 = center.getX() + blocks;
                                            int x2 = center.getX() - blocks;
                                            int z2 = center.getZ() - blocks;

                                            startReliefmapProcess(context, serverLevel, x1, z1, x2, z2);

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
                .then(LocateTerrainCommand.build())
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
        LocateTerrainCommand.TargetLevel target = LocateTerrainCommand.findTargetLevel(source);
        if (target != null) {
            return target.level();
        }
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

        RandomState randomState = level.getChunkSource().randomState();
        if (!((Object) randomState instanceof FTFRandomState ftfRandomState) || ftfRandomState.generatorContext() == null) {
            context.getSource().sendFailure(Component.literal("Current world is not generated by FreeTerraForged."));
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
        if (!((Object) randomState instanceof FTFRandomState ftfRandomState) || ftfRandomState.generatorContext() == null) {
            throw new IllegalStateException("Current world is not generated by FreeTerraForged");
        }
        GeneratorContext generatorContext = ftfRandomState.generatorContext();

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        BufferedImage full = TerrainMapGenerator.generateHeightmapImage(
            generatorContext, minX, minZ, maxX, maxZ, TerrainMapGenerator.DEFAULT_CHUNK_SIZE,
            prog -> context.getSource().sendSystemMessage(Component.literal("Generated heightmap " + prog.chunkX() + "," + prog.chunkZ()))
        );

        long durationMs = System.currentTimeMillis() - startTime;
        double durationSec = durationMs / 1000.0;

        try {
            SimpleDateFormat fmt = new SimpleDateFormat("ddMMyyyyhhmm");
            File finalFile = new File(String.format("heightmap_%s.png", fmt.format(new Date())));
            ImageIO.write(full, "png", finalFile);
            sendGeneratedImageFeedback(context, finalFile, durationSec);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write final heightmap", e);
        }
    }

    private static void startReliefmapProcess(CommandContext<CommandSourceStack> context,
                                              ServerLevel level,
                                              int x1, int z1, int x2, int z2) {
        if (reliefmapTask.get() != null) {
            context.getSource().sendFailure(Component.literal("Task already started"));
            return;
        }

        RandomState randomState = level.getChunkSource().randomState();
        if (!((Object) randomState instanceof FTFRandomState ftfRandomState) || ftfRandomState.generatorContext() == null) {
            context.getSource().sendFailure(Component.literal("Current world is not generated by FreeTerraForged."));
            return;
        }

        context.getSource().sendSystemMessage(Component.literal("Relief map generation started in background..."));

        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            generateReliefmaps(context, level, x1, x2, z1, z2);
            return 0;
        }, execPool).exceptionallyAsync(throwable -> {
            throwable.printStackTrace();
            context.getSource().sendFailure(Component.literal("Relief map generation failed: " + throwable.getMessage()));
            reliefmapTask.set(null);
            return 0;
        }).thenApplyAsync(integer -> {
            reliefmapTask.set(null);
            return 0;
        });
        reliefmapTask.set(future);
    }

    private static void generateReliefmaps(CommandContext<CommandSourceStack> context,
                                           ServerLevel serverLevel,
                                           int x1, int x2,
                                           int z1, int z2) {
        long startTime = System.currentTimeMillis();

        RandomState randomState = serverLevel.getChunkSource().randomState();
        if (!((Object) randomState instanceof FTFRandomState ftfRandomState) || ftfRandomState.generatorContext() == null) {
            throw new IllegalStateException("Current world is not generated by FreeTerraForged");
        }
        GeneratorContext generatorContext = ftfRandomState.generatorContext();

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        BufferedImage full = TerrainMapGenerator.generateReliefImage(
            generatorContext, minX, minZ, maxX, maxZ, TerrainMapGenerator.DEFAULT_CHUNK_SIZE,
            prog -> context.getSource().sendSystemMessage(Component.literal("Generated reliefmap " + prog.chunkX() + "," + prog.chunkZ()))
        );

        long durationMs = System.currentTimeMillis() - startTime;
        double durationSec = durationMs / 1000.0;

        try {
            SimpleDateFormat fmt = new SimpleDateFormat("ddMMyyyyhhmm");
            File finalFile = new File(String.format("reliefmap_%s.png", fmt.format(new Date())));
            ImageIO.write(full, "png", finalFile);
            sendGeneratedImageFeedback(context, finalFile, durationSec);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write final reliefmap", e);
        }
    }

    private static void sendGeneratedImageFeedback(CommandContext<CommandSourceStack> context,
                                                   File imageFile,
                                                   double durationSec) {
        String absPath = imageFile.getAbsolutePath();
        MutableComponent linkComponent = Component.literal(imageFile.getName())
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, absPath))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to open: " + absPath)))
                );

        context.getSource().sendSystemMessage(
                Component.literal("Successfully generated ")
                        .append(linkComponent)
                        .append(Component.literal(String.format(" in %.2f seconds", durationSec)))
        );
    }

    public static int computeReliefRGB(int y, int left, int right, int up, int down, int waterLevel) {
        return TerrainMapGenerator.computeReliefRGB(y, left, right, up, down, waterLevel);
    }

    public static BufferedImage renderVisualReliefImage(int[][] blockElevations, int waterLevel, int width, int height) {
        return TerrainMapGenerator.renderReliefImage(blockElevations, waterLevel, width, height);
    }
}
