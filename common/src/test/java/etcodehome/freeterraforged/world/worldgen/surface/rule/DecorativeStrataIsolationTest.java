package etcodehome.freeterraforged.world.worldgen.surface.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DecorativeStrataIsolationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void decorativeLayersLeaveTerrainErosionAndWaterUnchanged() {
        Preset baseline = Presets.makeFTFDefault();
        Preset decorated = baseline.copy();
        decorated.miscellaneous().strataDecorator = true;
        decorated.miscellaneous().structuredStrata = true;
        decorated.miscellaneous().oreCompatibleStoneOnly = false;
        var settings = decorated.miscellaneous().geology;
        decorated.miscellaneous().geology = settings.withFaultThrow(96)
            .withLayers(settings.layers().withBedThicknessScale(1.5F));

        try (var normal = context(baseline); var strata = context(decorated)) {
            int eroded = 0;
            var before = new Cell();
            var after = new Cell();
            for (int[] site : new int[][] {{-5317, -14503}, {-5381, -14503}, {5000, 5000}}) {
                for (int z = site[1] - 32; z < site[1] + 32; z++) {
                    for (int x = site[0] - 32; x < site[0] + 32; x++) {
                        normal.lookup.applyCell(before.reset(), x, z, true, false);
                        strata.lookup.applyCell(after.reset(), x, z, true, false);
                        assertEquals(before.height, after.height);
                        assertEquals(before.preErosionHeight, after.preErosionHeight);
                        assertEquals(before.heightErosion, after.heightErosion);
                        assertEquals(before.sediment, after.sediment);
                        assertEquals(before.riverWaterLevel, after.riverWaterLevel);
                        assertEquals(before.riverZone, after.riverZone);
                        assertEquals(before.terrain, after.terrain);
                        if (before.heightErosion != 0) eroded++;
                    }
                }
            }
            assertTrue(eroded > 100, "The sampled terrain should include active baseline erosion");
        }
    }

    private static GeneratorContext context(Preset preset) {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        var noise = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        return GeneratorContext.make(preset, noise, 42L);
    }
}
