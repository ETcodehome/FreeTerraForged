package etcodehome.freeterraforged.world.worldgen.cell.heightmap;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

import static org.junit.jupiter.api.Assertions.*;

public class RiparianErosionTest {

    private static GeneratorContext ctx;
    private static Heightmap heightmap;
    private static Levels levels;
    private static Preset preset;

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        preset = Presets.makeFTFDefault();
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        ctx = new GeneratorContext(preset, noiseLookup, 42L);
        heightmap = ctx.getHeightmap();
        levels = ctx.levels;
    }

    @Test
    @DisplayName("Verify riparian erosion applies a gentle bias without blatting background noise")
    void testRiparianErosionAdditiveGentleBias() {
        Cell controlCell = new Cell();
        controlCell.globalContinentScale = preset.world().continent.continentScale;
        controlCell.continentSizeModifier = 1.0F;
        controlCell.height = levels.water + levels.unit * 5.0F;
        controlCell.riverMask = 1.0F; // outside river influence
        controlCell.terrain = TerrainType.FLATS;

        heightmap.applyClimate(controlCell, 1200, 3400, true);
        float baselineErosion = controlCell.erosion;

        Cell riparianCell = new Cell();
        riparianCell.globalContinentScale = preset.world().continent.continentScale;
        riparianCell.continentSizeModifier = 1.0F;
        riparianCell.height = levels.water + levels.unit * 5.0F;
        riparianCell.riverMask = 0.20F; // inside valley: valleyAlpha = 0.80
        riparianCell.terrain = TerrainType.FLATS;

        heightmap.applyClimate(riparianCell, 1200, 3400, true);

        // Expected boost: (0.80)^2 * 0.20F = 0.64 * 0.20F = 0.128F subtracted
        float expectedBoost = (0.80F * 0.80F) * 0.20F;
        float expectedErosion = Math.max(-1.0F, baselineErosion - expectedBoost);

        assertEquals(expectedErosion, riparianCell.erosion, 1e-4F,
            "Riparian erosion must gently bias the baseline value downwards, preserving underlying noise variation");
        assertEquals(riparianCell.erosion, riparianCell.terrainErosion, "terrainErosion must match updated erosion");
    }

    @Test
    @DisplayName("Verify mountain terrain retains mountain/slope classification and does not become swamp")
    void testMountainsDoNotBecomeSwamps() {
        Cell mountainCell = new Cell();
        mountainCell.globalContinentScale = preset.world().continent.continentScale;
        mountainCell.continentSizeModifier = 1.0F;
        mountainCell.height = levels.water + levels.unit * 20.0F;
        mountainCell.riverMask = 0.05F; // right next to river
        mountainCell.terrain = TerrainType.MOUNTAINS_1;

        // Find a coordinate with mountainous baseline macro erosion (e.g. <= -0.50F)
        int testX = 0;
        for (int x = 0; x < 20000; x += 300) {
            mountainCell.riverMask = 1.0F;
            heightmap.applyClimate(mountainCell, x, 0, true);
            if (mountainCell.erosion <= -0.50F) {
                testX = x;
                break;
            }
        }
        float mountainBaseline = mountainCell.erosion;
        assertTrue(mountainBaseline <= -0.50F, "Should find a coordinate with mountainous baseline erosion");

        // Now evaluate inside river valley
        mountainCell.riverMask = 0.05F;
        heightmap.applyClimate(mountainCell, testX, 0, true);

        // The gentle boost (at most +0.20F) leaves erosion <= -0.30F, far below the swamp threshold (+0.55F)
        assertTrue(mountainCell.erosion < 0.0F,
            String.format("Mountain river gorge must remain in mountain/slope band (< 0.0F), but got %.3f", mountainCell.erosion));
        assertTrue(mountainCell.erosion < 0.55F, "Mountain river gorge must never reach the swamp threshold (0.55F)");
    }

    @Test
    @DisplayName("Verify smooth continuous transition across riverMask with zero step discontinuities")
    void testSmoothContinuityAcrossValley() {
        Cell cell = new Cell();
        cell.globalContinentScale = preset.world().continent.continentScale;
        cell.continentSizeModifier = 1.0F;
        cell.height = levels.water + levels.unit * 4.0F;
        cell.terrain = TerrainType.FLATS;

        float prevErosion = -999.0F;
        // Sweep riverMask from 0.0 (channel center) to 1.0 (outside valley) in small increments
        for (float mask = 0.0F; mask <= 1.0F; mask += 0.02F) {
            cell.riverMask = mask;
            heightmap.applyClimate(cell, 5000, 5000, true);

            if (prevErosion != -999.0F) {
                // Maximum delta between adjacent 0.02 steps:
                // d/d(mask) of (1 - mask)^2 * 0.20 is at most 2 * 0.20 = 0.40.
                // Over 0.02 step, delta must be <= 0.40 * 0.02 = 0.008 + epsilon
                float stepDelta = Math.abs(cell.erosion - prevErosion);
                assertTrue(stepDelta < 0.015F,
                    String.format("Erosion transition at riverMask=%.2f must be smooth and continuous, but jumped by %.4f", mask, stepDelta));
            }
            prevErosion = cell.erosion;
        }
    }

    @Test
    @DisplayName("Verify cells outside river influence (riverMask >= 1.0) receive zero boost")
    void testOutsideRiverInfluenceUnaffected() {
        Cell cell = new Cell();
        cell.globalContinentScale = preset.world().continent.continentScale;
        cell.continentSizeModifier = 1.0F;
        cell.height = levels.water + levels.unit * 2.0F;
        cell.riverMask = 1.0F; // Far outside river influence
        cell.terrain = TerrainType.FLATS;

        heightmap.applyClimate(cell, 8000, 8000, true);
        float baselineErosion = cell.erosion;

        cell.riverMask = 1.20F;
        heightmap.applyClimate(cell, 8000, 8000, true);
        assertEquals(baselineErosion, cell.erosion, 1e-4F, "Cells with riverMask >= 1.0 must not be modified");
    }

    @Test
    @DisplayName("Verify clamping at -1.0F prevents underflow on pre-existing low erosion")
    void testClampingAtLowerBound() {
        Cell cell = new Cell();
        cell.globalContinentScale = preset.world().continent.continentScale;
        cell.continentSizeModifier = 1.0F;
        cell.height = levels.water + levels.unit * 2.0F;
        cell.riverMask = 0.0F; // maximum reduction (-0.20F)
        cell.terrain = TerrainType.FLATS;

        // Find low erosion coordinate
        for (int x = 0; x < 20000; x += 300) {
            cell.riverMask = 1.0F;
            heightmap.applyClimate(cell, x, 0, true);
            if (cell.erosion <= -0.85F) {
                cell.riverMask = 0.0F;
                heightmap.applyClimate(cell, x, 0, true);
                assertTrue(cell.erosion >= -1.0F, "Erosion must be clamped to >= -1.0F");
                assertTrue(cell.erosion <= -0.85F, "Erosion must not increase");
                break;
            }
        }
    }

    @Test
    @DisplayName("Inspect real river networks: main rivers vs child branches riverMask")
    void testMainRiversVsForksRiverMask() throws Exception {
        Cell centerCell = new Cell();
        heightmap.applyTerrain(centerCell, 0, 0);
        Rivermap rivermap = heightmap.continent().getRivermap(centerCell);
        assertNotNull(rivermap, "Rivermap must exist");

        java.lang.reflect.Field networksField = Rivermap.class.getDeclaredField("networks");
        networksField.setAccessible(true);
        etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.Network[] networks = 
            (etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.Network[]) networksField.get(rivermap);

        boolean verifiedRoot = false;
        boolean verifiedChild = false;

        for (int i = 0; i < networks.length; i++) {
            var root = networks[i];
            var carver = root.riverCarver();
            var river = carver.getRiver();

            // Check midpoint of ROOT river
            float rMidX = river.x1 + 0.5F * river.dx;
            float rMidZ = river.z1 + 0.5F * river.dz;
            Cell rootCell = new Cell();
            heightmap.applyTerrain(rootCell, rMidX, rMidZ);
            heightmap.applyRivers(rootCell, rMidX, rMidZ, rivermap);
            if (rootCell.riverMask < 0.10F) {
                verifiedRoot = true;
            }

            for (int j = 0; j < root.children().length; j++) {
                var child = root.children()[j];
                var childCarver = child.riverCarver();
                var childRiver = childCarver.getRiver();

                // Check midpoint of CHILD river
                float cMidX = childRiver.x1 + 0.5F * childRiver.dx;
                float cMidZ = childRiver.z1 + 0.5F * childRiver.dz;
                Cell childCell = new Cell();
                heightmap.applyTerrain(childCell, cMidX, cMidZ);
                heightmap.applyRivers(childCell, cMidX, cMidZ, rivermap);
                if (childCell.riverMask < 0.10F) {
                    verifiedChild = true;
                }
            }
        }

        assertTrue(verifiedRoot, "Main root rivers must update riverMask (< 0.10 near channel center)");
        assertTrue(verifiedChild, "Child fork branches must also update riverMask (< 0.10 near channel center)");
    }
}
