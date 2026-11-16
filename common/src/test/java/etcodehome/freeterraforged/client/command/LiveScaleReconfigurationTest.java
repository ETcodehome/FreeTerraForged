package etcodehome.freeterraforged.client.command;

import com.mojang.brigadier.CommandDispatcher;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainMapGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainMapGenerator.SampledArea;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LiveScaleReconfigurationTest {

    private static CommandDispatcher<CommandSourceStack> dispatcher;
    private static List<String> feedbackMessages;
    private static CommandSourceStack testSource;

    private static Path resolveOutputDir() {
        String prop = System.getProperty("freeterraforged.test.heightmap.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        String env = System.getenv("FTF_TEST_OUTPUT_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of("build", "reports", "preset-test-heightmaps").toAbsolutePath().normalize();
    }

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        dispatcher = new CommandDispatcher<>();
        FTFCommands.register(dispatcher);

        feedbackMessages = new ArrayList<>();
        CommandSource source = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                feedbackMessages.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };

        testSource = new CommandSourceStack(
                source,
                Vec3.ZERO,
                Vec2.ZERO,
                null,
                4,
                "TestRunner",
                Component.literal("TestRunner"),
                null,
                null
        );
    }

    @Test
    @DisplayName("Exercise live spawn, initial heightmap generation, live scale revision, and rebuilt heightmap comparison")
    void testLiveScaleReconfigurationAndHeightmaps() throws Exception {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset defaultPreset = Presets.makeFTFDefault();
        PresetManager.PM.ingestFromPreset(defaultPreset);

        HolderGetter<Noise> noiseLookup = defaultPreset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long seed = 133742L;

        try (GeneratorContext ctx = new GeneratorContext(noiseLookup, seed)) {
            // 1. "Spawn in": Locate the continent spawn center and compute surface elevation
            long centerPacked = ctx.heightmap.continent().getNearestCenter(0, 0);
            int spawnX = PosUtil.unpackLeft(centerPacked);
            int spawnZ = PosUtil.unpackRight(centerPacked);

            Cell spawnCell = new Cell();
            ctx.lookup.applyCell(spawnCell, spawnX, spawnZ, false, false);
            int initialSpawnY = ctx.levels.scale(spawnCell.height);
            System.out.printf("[LiveTest] Spawned in at (%d, %d), initial surface elevation: %d%n", spawnX, spawnZ, initialSpawnY);

            // 2. Sample 512x512 area centered on player spawn location
            int radius = 256;
            int minX = spawnX - radius;
            int minZ = spawnZ - radius;
            int maxX = spawnX + radius - 1;
            int maxZ = spawnZ + radius - 1;

            SampledArea beforeArea = TerrainMapGenerator.sampleArea(ctx, minX, minZ, maxX, maxZ);
            BufferedImage beforeRelief = beforeArea.toReliefMap(ctx.levels.waterLevel);
            BufferedImage beforeRaw = beforeArea.toRawHeightmap();

            assertNotNull(beforeRelief, "Initial relief map must not be null");
            assertNotNull(beforeRaw, "Initial raw heightmap must not be null");

            Path outputDir = resolveOutputDir();
            Files.createDirectories(outputDir);

            File beforeReliefFile = outputDir.resolve("live_scale_before_relief.png").toFile();
            File beforeRawFile = outputDir.resolve("live_scale_before_raw.png").toFile();
            ImageIO.write(beforeRelief, "png", beforeReliefFile);
            ImageIO.write(beforeRaw, "png", beforeRawFile);

            System.out.printf("[LiveTest] Saved initial relief map: %s%n", beforeReliefFile.getAbsolutePath());
            System.out.printf("[LiveTest] Initial area stats: min=%d, max=%d, mean=%.1f%n",
                    beforeArea.minBlockElevation(), beforeArea.maxBlockElevation(), beforeArea.meanBlockElevation());

            // 3. "Revise global scales" using live commands
            feedbackMessages.clear();
            int res1 = dispatcher.execute("ftf live terrain.general.globalVerticalScale 2.2", testSource);
            assertEquals(1, res1, "Setting globalVerticalScale via /ftf live should succeed");

            int res2 = dispatcher.execute("ftf live terrain.general.globalHorizontalScale 1.8", testSource);
            assertEquals(1, res2, "Setting globalHorizontalScale via /ftf live should succeed");

            assertEquals(2.2f, PresetManager.PM.terrainSettings.general.globalVerticalScale, 0.001f);
            assertEquals(1.8f, PresetManager.PM.terrainSettings.general.globalHorizontalScale, 0.001f);

            // 4. Sample the rebuilt terrain at the exact same coordinates
            SampledArea afterArea = TerrainMapGenerator.sampleArea(ctx, minX, minZ, maxX, maxZ);
            BufferedImage afterRelief = afterArea.toReliefMap(ctx.levels.waterLevel);
            BufferedImage afterRaw = afterArea.toRawHeightmap();

            assertNotNull(afterRelief, "Rebuilt relief map must not be null");
            assertNotNull(afterRaw, "Rebuilt raw heightmap must not be null");

            File afterReliefFile = outputDir.resolve("live_scale_after_relief.png").toFile();
            File afterRawFile = outputDir.resolve("live_scale_after_raw.png").toFile();
            ImageIO.write(afterRelief, "png", afterReliefFile);
            ImageIO.write(afterRaw, "png", afterRawFile);

            System.out.printf("[LiveTest] Saved rebuilt relief map: %s%n", afterReliefFile.getAbsolutePath());
            System.out.printf("[LiveTest] Rebuilt area stats: min=%d, max=%d, mean=%.1f%n",
                    afterArea.minBlockElevation(), afterArea.maxBlockElevation(), afterArea.meanBlockElevation());

            // 5. Evaluate divergence and responsive elevation delta between before and after
            int totalPixels = beforeArea.width() * beforeArea.height();
            int divergentPixels = 0;
            int maxDelta = 0;
            long sumDelta = 0L;

            for (int z = 0; z < beforeArea.height(); z++) {
                for (int x = 0; x < beforeArea.width(); x++) {
                    int b = beforeArea.blockElevations()[z][x];
                    int a = afterArea.blockElevations()[z][x];
                    int delta = Math.abs(a - b);
                    if (delta >= 3) {
                        divergentPixels++;
                    }
                    if (delta > maxDelta) {
                        maxDelta = delta;
                    }
                    sumDelta += delta;
                }
            }

            double meanDelta = (double) sumDelta / totalPixels;
            double divergentFraction = (double) divergentPixels / totalPixels;
            System.out.printf("[LiveTest] Divergence analysis: divergentPixels=%d / %d (%.1f%%), maxDelta=%d blocks, meanDelta=%.2f blocks%n",
                    divergentPixels, totalPixels, divergentFraction * 100.0, maxDelta, meanDelta);

            assertTrue(divergentFraction > 0.50, "At least 50% of terrain samples must dynamically adjust after scale revision");
            assertTrue(maxDelta > 20, "Peak elevations should exhibit substantial amplification under revised 2.2x vertical scale");
            assertTrue(afterArea.maxBlockElevation() > beforeArea.maxBlockElevation(), "Rebuilt peak elevation should exceed initial peak elevation");

            // Verify the new player surface at spawn was recalculated
            Cell updatedSpawnCell = new Cell();
            ctx.lookup.applyCell(updatedSpawnCell, spawnX, spawnZ, false, false);
            int newSpawnY = ctx.levels.scale(updatedSpawnCell.height);
            System.out.printf("[LiveTest] Recalculated spawn elevation: %d (was %d)%n", newSpawnY, initialSpawnY);
        } finally {
            // Restore clean default state
            PresetManager.PM.ingestFromPreset(Presets.makeFTFDefault());
        }
    }
}
