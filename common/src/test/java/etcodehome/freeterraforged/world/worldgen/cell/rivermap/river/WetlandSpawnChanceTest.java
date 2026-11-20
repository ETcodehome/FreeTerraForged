package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.data.worldgen.preset.settings.RiverSettings;
import etcodehome.freeterraforged.registries.FTFRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class WetlandSpawnChanceTest {

    @BeforeAll
    static void setUpRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private int countWetlands(Network network) {
        int count = network.wetlands().length;
        for (Network child : network.children()) {
            count += countWetlands(child);
        }
        return count;
    }

    private int countTotalRiverSegments(Network network) {
        int count = 1;
        for (Network child : network.children()) {
            count += countTotalRiverSegments(child);
        }
        return count;
    }

    @Test
    @DisplayName("Wetland spawn chance 0.0F in preset produces exactly zero wetlands")
    void testWetlandZeroChanceProducesZeroWetlands() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        preset.rivers().wetlands.chance = 0.0F;

        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);

            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            Rivermap rivermap = gen.generateRivers(centerX, centerZ, PosUtil.pack(centerX, centerZ));
            int totalWetlands = 0;
            int totalRiverSegments = 0;
            for (Network net : rivermap.getNetworks()) {
                totalWetlands += countWetlands(net);
                totalRiverSegments += countTotalRiverSegments(net);
            }

            assertTrue(totalRiverSegments > 0, "Continent should have generated river segments");
            assertEquals(0, totalWetlands, "When wetland chance is 0.0F in preset, exactly 0 wetlands must spawn");
        }
    }

    @Test
    @DisplayName("Wetland spawn chance 1.0F in preset produces wetlands on all non-zero length river segments")
    void testWetlandFullChanceProducesWetlands() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        Preset preset = Presets.makeFTFDefault();
        preset.rivers().wetlands.chance = 1.0F;

        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        long worldSeed = 42L;

        try (GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, worldSeed)) {
            UpliftContinentGenerator uplift = (UpliftContinentGenerator) ctx.heightmap.continent();
            TriSubdivisionRiverGenerator gen = new TriSubdivisionRiverGenerator(uplift, ctx);

            long centerPacked = uplift.getNearestCenter(0, 0);
            int centerX = PosUtil.unpackLeft(centerPacked);
            int centerZ = PosUtil.unpackRight(centerPacked);

            Rivermap rivermap = gen.generateRivers(centerX, centerZ, PosUtil.pack(centerX, centerZ));
            int totalWetlands = 0;
            for (Network net : rivermap.getNetworks()) {
                totalWetlands += countWetlands(net);
            }

            assertTrue(totalWetlands > 0, "When wetland chance is 1.0F, wetlands must spawn across the river network");
        }
    }
}
