package etcodehome.freeterraforged.world.worldgen.feature;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import etcodehome.freeterraforged.data.worldgen.preset.settings.MiscellaneousSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.tags.FTFBlockTags;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SnowCoverVegetationTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.BLOCK.bindTags(Map.of(
            FTFBlockTags.SNOW_COVERABLE, List.of(
                BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.SHORT_GRASS),
                BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.FERN),
                BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.PINK_PETALS),
                BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.POPPY),
                BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.DANDELION)
            )
        ));
    }

    @Test
    @DisplayName("Verify MiscellaneousSettings snowCoversSmallPlants defaults to true, round-trips, and decodes legacy configs")
    void testMiscellaneousSettingsCodecAndCopy() {
        MiscellaneousSettings defaults = MiscellaneousSettings.makeDefault();
        assertTrue(defaults.snowCoversSmallPlants, "Default miscellaneous settings must enable snow cover");

        defaults.snowCoversSmallPlants = false;
        JsonObject encoded = MiscellaneousSettings.CODEC.encodeStart(JsonOps.INSTANCE, defaults)
            .getOrThrow(msg -> new IllegalStateException("Failed to encode miscellaneous settings: " + msg))
            .getAsJsonObject();

        MiscellaneousSettings decoded = MiscellaneousSettings.CODEC.parse(JsonOps.INSTANCE, encoded)
            .getOrThrow(msg -> new IllegalStateException("Failed to decode miscellaneous settings: " + msg));
        assertFalse(decoded.snowCoversSmallPlants, "snowCoversSmallPlants=false must survive a codec round trip");

        MiscellaneousSettings copied = defaults.copy();
        assertFalse(copied.snowCoversSmallPlants, "MiscellaneousSettings.copy() must retain snowCoversSmallPlants=false");

        encoded.remove("snowCoversSmallPlants");
        MiscellaneousSettings legacy = MiscellaneousSettings.CODEC.parse(JsonOps.INSTANCE, encoded)
            .getOrThrow(msg -> new IllegalStateException("Legacy miscellaneous settings failed to decode: " + msg));
        assertTrue(legacy.snowCoversSmallPlants, "Legacy settings without snowCoversSmallPlants must default to true");
    }

    @Test
    @DisplayName("Verify DecorateSnowFeature.Config cover_plants defaults to true, round-trips, and decodes legacy configs")
    void testDecorateSnowConfigCodec() {
        DecorateSnowFeature.Config config = new DecorateSnowFeature.Config(
            true, true, new ScreeFeature.Config(0, 0, 0, 0, 0F, 0F, 0F, 0F, 0F), false
        );
        assertFalse(config.coverPlants());

        JsonObject encoded = DecorateSnowFeature.Config.CODEC.encodeStart(JsonOps.INSTANCE, config)
            .getOrThrow(msg -> new IllegalStateException("Failed to encode decorate_snow config: " + msg))
            .getAsJsonObject();

        DecorateSnowFeature.Config decoded = DecorateSnowFeature.Config.CODEC.parse(JsonOps.INSTANCE, encoded)
            .getOrThrow(msg -> new IllegalStateException("Failed to decode decorate_snow config: " + msg));
        assertFalse(decoded.coverPlants(), "cover_plants=false must survive a codec round trip");

        encoded.remove("cover_plants");
        DecorateSnowFeature.Config legacy = DecorateSnowFeature.Config.CODEC.parse(JsonOps.INSTANCE, encoded)
            .getOrThrow(msg -> new IllegalStateException("Legacy decorate_snow config failed to decode: " + msg));
        assertTrue(legacy.coverPlants(), "Legacy decorate_snow config without cover_plants must default to true");

        DecorateSnowFeature.Config compat = new DecorateSnowFeature.Config(
            true, true, new ScreeFeature.Config(0, 0, 0, 0, 0F, 0F, 0F, 0F, 0F)
        );
        assertTrue(compat.coverPlants(), "3-argument Config constructor must default coverPlants to true");
    }

    @Test
    @DisplayName("Verify isCoverable accepts only tagged small single-block plants and rejects double plants, fluids, and non-plants")
    void testSnowCoverableBlockClassification() {
        // Tagged small single-block plants must be coverable
        assertTrue(DecorateSnowFeature.isCoverable(Blocks.SHORT_GRASS.defaultBlockState()), "Short grass must be coverable");
        assertTrue(DecorateSnowFeature.isCoverable(Blocks.FERN.defaultBlockState()), "Fern must be coverable");
        assertTrue(DecorateSnowFeature.isCoverable(Blocks.PINK_PETALS.defaultBlockState()), "Pink petals must be coverable");
        assertTrue(DecorateSnowFeature.isCoverable(Blocks.POPPY.defaultBlockState()), "Poppy must be coverable");
        assertTrue(DecorateSnowFeature.isCoverable(Blocks.DANDELION.defaultBlockState()), "Dandelion must be coverable");

        // Double plants and multi-block vegetation must never be covered
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.TALL_GRASS.defaultBlockState()), "Tall grass must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.LARGE_FERN.defaultBlockState()), "Large fern must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.SUNFLOWER.defaultBlockState()), "Sunflower must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.PEONY.defaultBlockState()), "Peony must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.ROSE_BUSH.defaultBlockState()), "Rose bush must not be coverable");

        // Non-vegetation, trees, fluids, and snow itself must never be covered
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.SNOW.defaultBlockState()), "Snow layer must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.SNOW_BLOCK.defaultBlockState()), "Snow block must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.OAK_SAPLING.defaultBlockState()), "Sapling must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.WATER.defaultBlockState()), "Water must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.STONE.defaultBlockState()), "Stone must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.DIRT.defaultBlockState()), "Dirt must not be coverable");
        assertFalse(DecorateSnowFeature.isCoverable(Blocks.GRASS_BLOCK.defaultBlockState()), "Grass block must not be coverable");
    }

    @Test
    @DisplayName("Verify getSnowLevel depth mapping accurately scales snow layers relative to continuous terrain height")
    void testSnowLevelDepthDerivation() {
        Levels levels = new Levels(Presets.makeFTFDefault().world().properties);
        Cell cell = new Cell();

        int groundY = 64;

        // Ground level exactly: continuousHeight == 64.0 (depth 0.0) -> level 0
        cell.height = 64.0F * levels.unit;
        assertEquals(0, DecorateSnowFeature.getSnowLevel(cell, levels, groundY));

        // Slightly below ground: depth < 0.0 -> level 0
        cell.height = 63.8F * levels.unit;
        assertEquals(0, DecorateSnowFeature.getSnowLevel(cell, levels, groundY));

        // Slightly above ground: depth 0.125 (1/8) -> level 1
        cell.height = 64.125F * levels.unit;
        assertEquals(1, DecorateSnowFeature.getSnowLevel(cell, levels, groundY));

        // Halfway: depth 0.5 (4/8) -> level 4
        cell.height = 64.5F * levels.unit;
        assertEquals(4, DecorateSnowFeature.getSnowLevel(cell, levels, groundY));

        // Deeper: depth 0.75 (6/8) -> level 6
        cell.height = 64.75F * levels.unit;
        assertEquals(6, DecorateSnowFeature.getSnowLevel(cell, levels, groundY));

        // Full block: depth 1.0 (8/8) -> level 8
        cell.height = 65.0F * levels.unit;
        assertEquals(8, DecorateSnowFeature.getSnowLevel(cell, levels, groundY));

        // Overshoot: depth 2.0 -> clamped to 8 (MAX)
        cell.height = 66.0F * levels.unit;
        assertEquals(8, DecorateSnowFeature.getSnowLevel(cell, levels, groundY));
    }
}
