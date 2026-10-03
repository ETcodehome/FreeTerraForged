package etcodehome.freeterraforged.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.RandomState;

import javax.imageio.ImageIO;
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

public class FtfCommands {

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
        );
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

        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            generateHeightmaps(context, level, x1, x2, z1, z2);
            return 0;
        }, execPool).exceptionallyAsync(throwable -> {
            throwable.printStackTrace();
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
                                           int x1,
                                           int x2,
                                           int z1,
                                           int z2) {
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


        for (int cx = 0; cx < chunksX; cx++) {
            for (int cz = 0; cz < chunksZ; cz++) {
                int startX = minX + cx * CHUNK;
                int startZ = minZ + cz * CHUNK;
                int w = Math.min(CHUNK, totalW - cx * CHUNK);
                int h = Math.min(CHUNK, totalH - cz * CHUNK);

                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
                WritableRaster raster = img.getRaster();
                for (int x = 0; x < w; x++) {
                    for (int z = 0; z < h; z++) {
                        int rx = generatorContext.cache.chunkToTile((startX + x) >> 4);
                        int rz = generatorContext.cache.chunkToTile((startZ + z) >> 4);
                        Tile tile = generatorContext.cache.provide(rx, rz);
                        Cell cell = tile.lookup(startX + x, startZ + z);
                        float height = cell.height;
                        int gray = (int) (Math.clamp(height, 0, 1) * 65356 / serverLevel.getLogicalHeight());
                        raster.setSample(x,z,0,gray);
                    }
                }

                try {
                    File tempFile = outputDir.resolve(String.format("chunk_%d_%d.png", cx, cz)).toFile();
                    tempFile.deleteOnExit();
                    ImageIO.write(img, "png", tempFile);
                    context.getSource().sendSystemMessage(Component.literal("Generated " + tempFile.getName()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        BufferedImage full = new BufferedImage(totalW, totalH, BufferedImage.TYPE_USHORT_GRAY);

        for (int cx = 0; cx < chunksX; cx++) {
            for (int cz = 0; cz < chunksZ; cz++) {
                BufferedImage chunk = null;
                try {
                    chunk = ImageIO.read(outputDir.resolve(String.format("chunk_%d_%d.png", cx, cz)).toFile());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                full.getGraphics().drawImage(chunk, cx * CHUNK, cz * CHUNK, null);
            }
        }
        full.getGraphics().dispose();
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("ddMMyyyyhhmm");
            File file = new File("heightmap_%s.png".formatted(fmt.format(new Date())));
            ImageIO.write(full, "png", file);
            context.getSource().sendSystemMessage(Component.literal("Generated " + file.getName()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
