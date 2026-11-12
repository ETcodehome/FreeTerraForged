package etcodehome.freeterraforged.world.worldgen.biome;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import etcodehome.freeterraforged.data.worldgen.preset.settings.IslandSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.SpawnType;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings.Properties;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.CellPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.ClimateParameterSampler;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Populators;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainCategory;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.ArchipelagoPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.PreviewGrid;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.DensityFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContinentalnessRangeMappingTest {

    @BeforeAll
    static void setUpRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private Levels levels;
    private ControlPoints defaultControlPoints;
    private Heightmap defaultHeightmap;

    @BeforeEach
    void setUp() {
        Properties properties = new Properties(SpawnType.CONTINENT_CENTER, 384, 64, 63, -54, 63, 0, 0);
        this.levels = new Levels(properties);
        // Default control points:
        // islandInland = 0.0F, islandCoast = 0.074F, deepOcean = 0.102F, shallowOcean = 0.13144F,
        // beach = 0.2210F, coast = 0.3576F, inland = 0.4717F
        this.defaultControlPoints = new ControlPoints(0.0F, 0.074F, 0.102F, 0.13144F, 0.2210F, 0.3576F, 0.4717F);
        this.defaultHeightmap = new Heightmap(null, null, null, null, this.levels, this.defaultControlPoints, 1.0F, null, null);
    }

    private Heightmap makeHeightmap(ControlPoints cp) {
        return new Heightmap(null, null, null, null, this.levels, cp, 1.0F, null, null);
    }

    @Test
    @DisplayName("Verify all 7 vanilla continentalness parameter ranges are fully covered without gaps")
    void testAllSevenVanillaRangesCovered() {
        boolean hitMushroom = false;
        boolean hitDeepOcean = false;
        boolean hitOcean = false;
        boolean hitCoast = false;
        boolean hitNearInland = false;
        boolean hitMidInland = false;
        boolean hitFarInland = false;

        Cell cell = new Cell();

        // 1. Mushroom Fields / Islands (C1 and C0)
        cell.terrain = TerrainType.MUSHROOM_FIELDS;
        cell.height = this.levels.water + 0.1F;
        cell.controlPoint = this.defaultControlPoints.islandInland;
        float cMushroom = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cMushroom >= Continentalness.MUSHROOM_FIELDS.min() && cMushroom <= Continentalness.MUSHROOM_FIELDS.max()) {
            hitMushroom = true;
        }

        // 2. Deep Ocean (C2) - near ocean floor
        float floorHeight = this.levels.water(-this.levels.oceanDepth);
        cell.terrain = TerrainType.NONE;
        cell.height = floorHeight + 2.0F / this.levels.terrainScaleFactor;
        cell.controlPoint = 0.05F;
        float cDeepOcean = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cDeepOcean >= Continentalness.DEEP_OCEAN.min() && cDeepOcean <= Continentalness.DEEP_OCEAN.max()) {
            hitDeepOcean = true;
        }

        // 3. Shallow Ocean (C3) - near water surface
        cell.height = this.levels.water - 2.0F / this.levels.terrainScaleFactor;
        cell.controlPoint = 0.12F;
        float cOcean = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cOcean >= Continentalness.OCEAN.min() && cOcean <= Continentalness.OCEAN.max()) {
            hitOcean = true;
        }

        // 4. Coast / Beach (C4) - continent above water
        cell.terrain = TerrainType.COAST;
        cell.height = this.levels.water + 0.05F;
        cell.controlPoint = 0.28F;
        float cCoast = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cCoast >= Continentalness.COAST.min() && cCoast <= Continentalness.COAST.max()) {
            hitCoast = true;
        }

        // 5. Near-Inland (C5)
        cell.terrain = TerrainType.NONE;
        cell.height = this.levels.water + 0.15F;
        cell.controlPoint = 0.40F;
        float cNearInland = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cNearInland >= Continentalness.NEAR_INLAND.min() && cNearInland <= Continentalness.NEAR_INLAND.max()) {
            hitNearInland = true;
        }

        // 6. Mid-Inland (C6)
        cell.controlPoint = 0.52F;
        float cMidInland = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cMidInland >= Continentalness.MID_INLAND.min() && cMidInland <= Continentalness.MID_INLAND.max()) {
            hitMidInland = true;
        }

        // 7. Far-Inland (C7)
        cell.controlPoint = 0.85F;
        float cFarInland = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cFarInland >= Continentalness.FAR_INLAND.min() && cFarInland <= Continentalness.FAR_INLAND.max()) {
            hitFarInland = true;
        }

        assertTrue(hitMushroom, "Mushroom Fields range [-1.20, -1.05] must be reached");
        assertTrue(hitDeepOcean, "Deep Ocean range [-1.05, -0.455] must be reached");
        assertTrue(hitOcean, "Ocean range [-0.455, -0.19] must be reached");
        assertTrue(hitCoast, "Coast range [-0.19, -0.11] must be reached");
        assertTrue(hitNearInland, "Near-Inland range [-0.11, 0.03] must be reached");
        assertTrue(hitMidInland, "Mid-Inland range [0.03, 0.30] must be reached");
        assertTrue(hitFarInland, "Far-Inland range [0.30, 1.00] must be reached");
    }

    @Test
    @DisplayName("Verify C0 continuity across boundaries and height transitions")
    void testBoundaryContinuity() {
        float eps = 1e-5F;
        float tol = 2e-3F;
        Cell cell = new Cell();

        // 1. Deep Ocean (C2) -> Shallow Ocean (C3) at ocean depth split height
        float floorHeight = this.levels.water(-this.levels.oceanDepth);
        float shallowBlocks = Math.min(22.0F, this.levels.oceanDepth * 0.40F);
        float hSplit = 1.0F - (shallowBlocks / this.levels.oceanDepth);
        float splitHeight = floorHeight + hSplit * (this.levels.water - floorHeight);

        cell.terrain = TerrainType.NONE;
        cell.height = splitHeight - eps;
        float cDeep = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.height = splitHeight;
        float cSplitExact = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.height = splitHeight + eps;
        float cShallow = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(-0.455F, cSplitExact, 1e-4F, "Exact ocean depth split must meet -0.455F");
        assertEquals(-0.455F, cDeep, tol, "Ocean depth approaching split must meet -0.455F");
        assertEquals(-0.455F, cShallow, tol, "Ocean depth leaving split must meet -0.455F");
        assertEquals(cDeep, cShallow, tol, "Deep -> Shallow ocean boundary must be continuous");

        // 2. Waterline transition: Submerged ocean surface (C3) -> Continent onshore (C4)
        cell.height = this.levels.water;
        float cSubmergedWaterline = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.height = this.levels.water + 0.01F;
        cell.controlPoint = this.defaultControlPoints.beach;
        float cOnshoreBeach = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(-0.191F, cSubmergedWaterline, 1e-3F, "Submerged waterline must meet -0.191F (C3)");
        assertEquals(-0.185F, cOnshoreBeach, 1e-3F, "Onshore beach start must meet -0.185F (C4)");
        assertTrue(cSubmergedWaterline < -0.19F, "Submerged ocean must be strictly < -0.19F to prevent beach misbioming");
        assertTrue(cOnshoreBeach >= -0.19F, "Onshore continent must be >= -0.19F");

        // 3. Coast -> Near-Inland at controlPoints.coast (0.3576F)
        cell.height = this.levels.water + 0.1F;
        cell.controlPoint = this.defaultControlPoints.coast - eps;
        float cCoastEnd = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.controlPoint = this.defaultControlPoints.coast;
        float cCoastExact = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.controlPoint = this.defaultControlPoints.coast + eps;
        float cNearStart = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(-0.11F, cCoastExact, 1e-4F, "Coast boundary must meet -0.11F");
        assertEquals(-0.11F, cCoastEnd, tol, "Coast end approaching coast must meet -0.11F");
        assertEquals(-0.11F, cNearStart, tol, "Near-Inland start leaving coast must meet -0.11F");
        assertEquals(cCoastEnd, cNearStart, tol, "Coast -> Near-Inland boundary must be continuous");

        // 4. Near-Inland -> Mid-Inland at controlPoints.inland (0.4717F)
        cell.controlPoint = this.defaultControlPoints.inland - eps;
        float cNearEnd = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.controlPoint = this.defaultControlPoints.inland;
        float cInlandExact = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.controlPoint = this.defaultControlPoints.inland + eps;
        float cInteriorStart = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(0.03F, cInlandExact, 1e-4F, "Inland boundary must meet 0.03F");
        assertEquals(0.03F, cNearEnd, tol, "Near-Inland end approaching inland must meet 0.03F");
        assertEquals(0.03F, cInteriorStart, tol, "Mid-Inland start leaving inland must meet 0.03F");
        assertEquals(cNearEnd, cInteriorStart, tol, "Near-Inland -> Interior boundary must be continuous");

        // 5. Mid-Inland -> Far-Inland at interior mid-split (inland + 0.35 * (1.0 - inland))
        float midSplitEdge = this.defaultControlPoints.inland + 0.35F * (1.0F - this.defaultControlPoints.inland);
        cell.controlPoint = midSplitEdge - eps;
        float cMidEnd = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.controlPoint = midSplitEdge;
        float cMidSplitExact = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.controlPoint = midSplitEdge + eps;
        float cFarStart = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(0.30F, cMidSplitExact, 1e-4F, "Mid-split boundary must meet 0.30F");
        assertEquals(0.30F, cMidEnd, tol, "Mid-Inland end must meet 0.30F");
        assertEquals(0.30F, cFarStart, tol, "Far-Inland start must meet 0.30F");
        assertEquals(cMidEnd, cFarStart, tol, "Mid -> Far boundary must be continuous");

        // 6. Far-Inland peak at controlPoint = 1.0F
        cell.controlPoint = 1.0F;
        float cPeak = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(1.00F, cPeak, 1e-4F, "Continental interior peak must reach 1.00F");

        // 7. Mushroom Island waterline at islandCoast meets C1 start (-1.05F)
        cell.terrain = TerrainType.MUSHROOM_FIELDS;
        cell.height = this.levels.water + 0.1F;
        cell.controlPoint = this.defaultControlPoints.islandCoast;
        float cIslandShore = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(-1.05F, cIslandShore, 1e-4F, "Mushroom Island shoreline must meet -1.05F (C1)");

        cell.controlPoint = this.defaultControlPoints.islandInland;
        float cIslandPeak = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(-1.20F, cIslandPeak, 1e-4F, "Mushroom Island peak must meet -1.20F (C0)");
    }

    @Test
    @DisplayName("Fix 2: Only Mushroom Fields islands are C1/C0 ranged [-1.20F, -1.05F]; other islands are C4/C5")
    void testFix2IslandBuiltTerrainClassification() {
        Cell cell = new Cell();
        cell.height = this.levels.water + 0.2F;

        // 1. Mushroom fields islands: strictly C1 / C0 [-1.20F, -1.05F]
        cell.terrain = TerrainType.MUSHROOM_FIELDS;
        for (float edge = 0.0F; edge <= this.defaultControlPoints.islandCoast; edge += 0.005F) {
            cell.controlPoint = edge;
            float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
            assertTrue(c <= -1.05F + 1e-5F,
                String.format("Mushroom fields island at edge %.3f had c=%.4f, exceeding C1 max (-1.05)", edge, c));
            assertTrue(c >= -1.20F - 1e-5F,
                String.format("Mushroom fields island at edge %.3f had c=%.4f below C0 min (-1.20)", edge, c));
        }

        // 2. Standard oceanic islands (non-mushroom): must be C4 or C5, NEVER mushroom [-1.20F, -1.05F]
        Terrain[] nonMushroomIslandTypes = {
            TerrainType.ISLAND,
            TerrainType.ISLAND_BEACH,
            TerrainType.ISLAND_MOUNTAINS
        };

        for (Terrain type : nonMushroomIslandTypes) {
            cell.terrain = type;
            assertEquals(TerrainCategory.ISLAND, cell.terrain.getCategory(), "Must be categorized as ISLAND");

            for (float edge = 0.0F; edge <= this.defaultControlPoints.islandCoast; edge += 0.005F) {
                cell.controlPoint = edge;
                float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

                // Never mushroom range
                assertTrue(c > Continentalness.MUSHROOM_FIELDS.max(),
                    String.format("Standard island %s at edge %.3f had c=%.4f in mushroom range!", type.getName(), edge, c));

                // Above water: must be >= C4 start (-0.185F) and <= C5 max (0.03F)
                assertTrue(c >= -0.185F - 1e-5F,
                    String.format("Standard island %s at edge %.3f had c=%.4f below C4 start (-0.185)", type.getName(), edge, c));
                assertTrue(c <= Continentalness.NEAR_INLAND.max() + 1e-5F,
                    String.format("Standard island %s at edge %.3f had c=%.4f exceeding C5 max (0.03)", type.getName(), edge, c));
            }
        }

        // 3. Island mountains: must be at least C5 Near-Inland min (-0.11F)
        cell.terrain = TerrainType.ISLAND_MOUNTAINS;
        cell.controlPoint = 0.02F;
        float cMtn = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(Continentalness.NEAR_INLAND.min(), cMtn, 1e-4F,
            "Island mountains must evaluate to Near-Inland min (-0.11F)");
    }

    @Test
    @DisplayName("Verify mushroom islands map strictly to [-1.20F, -1.05F]")
    void testMushroomIslandParameterRange() {
        Cell cell = new Cell();
        cell.terrain = TerrainType.MUSHROOM_FIELDS;
        cell.height = this.levels.water + 0.15F;

        // At islandCoast: must be -1.05F
        cell.controlPoint = this.defaultControlPoints.islandCoast;
        float cShore = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(-1.05F, cShore, 1e-4F);

        // At islandInland: must be -1.20F
        cell.controlPoint = this.defaultControlPoints.islandInland;
        float cCenter = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(-1.20F, cCenter, 1e-4F);

        // Sweep controlPoint across [islandInland, islandCoast]
        for (float edge = 0.0F; edge <= this.defaultControlPoints.islandCoast; edge += 0.005F) {
            cell.controlPoint = edge;
            float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
            assertTrue(c >= Continentalness.MUSHROOM_FIELDS.min() - 1e-5F,
                "Mushroom island continentalness must not drop below -1.20F");
            assertTrue(c <= Continentalness.MUSHROOM_FIELDS.max() + 1e-5F,
                "Mushroom island continentalness must not exceed -1.05F");
        }
    }

    @Test
    @DisplayName("Verify control points slider adjustments alter terrain band proportions proportionally")
    void testControlPointsSliderProportionality() {
        // Preset A: Narrow Near-Inland (inland = 0.40F)
        ControlPoints cpNarrow = new ControlPoints(0.0F, 0.074F, 0.102F, 0.13144F, 0.2210F, 0.3576F, 0.40F);
        Heightmap hmA = makeHeightmap(cpNarrow);

        // Preset B: Wide Near-Inland (inland = 0.70F)
        ControlPoints cpWide = new ControlPoints(0.0F, 0.074F, 0.102F, 0.13144F, 0.2210F, 0.3576F, 0.70F);
        Heightmap hmB = makeHeightmap(cpWide);

        Cell cell = new Cell();
        cell.height = this.levels.water + 0.15F;
        cell.terrain = TerrainType.NONE;
        cell.controlPoint = 0.55F;

        // In Preset A, 0.55F is continental interior (Mid/Far-Inland, c > 0.03F)
        float cA = CellSampler.Field.CONTINENT.read(cell, hmA);
        assertTrue(cA > Continentalness.MID_INLAND.min(),
            "With inland=0.40, edge 0.55 must be continental interior (c > 0.03), was " + cA);

        // In Preset B, 0.55F is Near-Inland (c < 0.03F)
        float cB = CellSampler.Field.CONTINENT.read(cell, hmB);
        assertTrue(cB < Continentalness.NEAR_INLAND.max(),
            "With inland=0.70, edge 0.55 must be Near-Inland (c < 0.03), was " + cB);

        // Coast slider proportionality test:
        // Preset C: coast = 0.30F vs Preset D: coast = 0.45F
        ControlPoints cpCoastLow = new ControlPoints(0.0F, 0.074F, 0.102F, 0.13144F, 0.2210F, 0.30F, 0.60F);
        Heightmap hmC = makeHeightmap(cpCoastLow);
        ControlPoints cpCoastHigh = new ControlPoints(0.0F, 0.074F, 0.102F, 0.13144F, 0.2210F, 0.45F, 0.60F);
        Heightmap hmD = makeHeightmap(cpCoastHigh);

        cell.controlPoint = 0.38F;
        float cC = CellSampler.Field.CONTINENT.read(cell, hmC);
        float cD = CellSampler.Field.CONTINENT.read(cell, hmD);

        assertTrue(cC > Continentalness.COAST.max(),
            "With coast=0.30, edge 0.38 must be Near-Inland (c > -0.11), was " + cC);
        assertTrue(cD < Continentalness.NEAR_INLAND.min(),
            "With coast=0.45, edge 0.38 must be Coast (c < -0.11), was " + cD);

        // Mainland monotonicity test: as controlPoint increases from beach to 1.0, continentalness strictly increases
        float prevC = -1.0F;
        for (float edge = this.defaultControlPoints.beach; edge <= 1.0F; edge += 0.01F) {
            cell.controlPoint = edge;
            float currentC = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
            assertTrue(currentC >= prevC - 1e-6F,
                String.format("Continentalness must be monotonically non-decreasing: at %.2f got %.4f vs previous %.4f",
                    edge, currentC, prevC));
            prevC = currentC;
        }
    }

    @Test
    @DisplayName("Verify Field.CONTINENT evaluation does not mutate cell height")
    void testHeightmapIndependence() {
        Cell cell = new Cell();
        cell.height = 0.654321F;
        cell.controlPoint = 0.45F;
        cell.terrain = TerrainType.NONE;

        float hBefore = cell.height;
        float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        float hAfter = cell.height;

        assertEquals(hBefore, hAfter, 0.0F, "cell.height must remain unmodified after Field.CONTINENT evaluation");
        assertTrue(Float.isFinite(c), "Continentalness must be finite");
    }

    @Test
    @DisplayName("Fix 1: Continent terrain above water level never has continentalness less than beach (C4 >= -0.19F)")
    void testFix1ContinentTerrainAboveWaterNeverLessThanBeach() {
        Cell cell = new Cell();
        cell.height = this.levels.water + 0.05F; // Above water level
        cell.terrain = TerrainType.NONE;

        // Sweep controlPoint across full [0.0, 1.0] range
        for (float edge = 0.0F; edge <= 1.0F; edge += 0.01F) {
            cell.controlPoint = edge;
            float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
            assertTrue(c >= Continentalness.COAST.min() - 1e-5F,
                String.format("Continent cell above water at edge %.3f had c=%.4f < C4.min (-0.19F)", edge, c));
            if (edge <= this.defaultControlPoints.beach) {
                assertEquals(-0.185F, c, 1e-4F,
                    String.format("Continent cell at edge %.3f <= beach must be C4 (-0.185F), was %.4f", edge, c));
            }
        }
    }

    @Test
    @DisplayName("Coastal mountains never drop into C4 beach or ocean, maintaining at least C5 Near-Inland")
    void testCoastalMountainsPreserveNearInlandContinentalness() {
        Cell cell = new Cell();
        cell.height = this.levels.water + 0.5F; // Mountain peak above water
        cell.terrain = TerrainType.MOUNTAINS_1;

        // Sweep controlPoint from ocean basin up to coast
        for (float edge = 0.0F; edge <= this.defaultControlPoints.coast; edge += 0.01F) {
            cell.controlPoint = edge;
            float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
            assertTrue(c >= Continentalness.NEAR_INLAND.min() - 1e-5F,
                String.format("Mountain at edge %.3f must be >= Near-Inland min (-0.11F) to prevent turning into Ocean, was %.4f", edge, c));
        }
    }

    @Test
    @DisplayName("Hydrology (rivers, lakes, wetlands) below water level do not drop into ocean C2/C3")
    void testHydrologyPreservesContinentalRangesBelowWaterLevel() {
        Cell cell = new Cell();
        cell.height = this.levels.water - 5.0F / this.levels.terrainScaleFactor; // River bed below water level
        cell.terrain = TerrainType.RIVER;
        cell.controlPoint = 0.50F; // Inland river

        float cRiver = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertTrue(cRiver >= Continentalness.NEAR_INLAND.min(),
            String.format("Inland river at controlPoint 0.50 must preserve continent continentalness, not ocean! Was %.4f", cRiver));

        cell.terrain = TerrainType.WETLAND;
        float cWetland = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertTrue(cWetland >= Continentalness.NEAR_INLAND.min(),
            String.format("Inland wetland must preserve continent continentalness, not ocean! Was %.4f", cWetland));
    }

    @Test
    @DisplayName("Fix 3: Ocean areas below water level are strictly C2/C3, height-interpolated, never beach or mushroom")
    void testFix3OceanBelowWaterHeightDriven() {
        Cell cell = new Cell();
        cell.terrain = TerrainType.NONE;
        float floorHeight = this.levels.water(-this.levels.oceanDepth);

        // Sweep height from deepest ocean floor to water level
        float prevC = -2.0F;
        for (float h = floorHeight; h <= this.levels.water; h += (this.levels.water - floorHeight) / 20.0F) {
            cell.height = h;
            float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

            // Never misbiomed as beach: strictly < -0.19F
            assertTrue(c < -0.19F,
                String.format("Underwater cell at h=%.4f had c=%.4f >= -0.19F, misbioming as beach!", h, c));
            assertTrue(c <= -0.191F + 1e-5F,
                String.format("Underwater cell at h=%.4f had c=%.4f > -0.191F", h, c));

            // Never flagged as mushroom fields on ocean floor: strictly > -1.05F
            assertTrue(c > Continentalness.MUSHROOM_FIELDS.max(),
                String.format("Underwater cell at h=%.4f had c=%.4f <= -1.05F, matching Mushroom Fields!", h, c));
            assertTrue(c >= -1.045F - 1e-5F,
                String.format("Underwater cell at h=%.4f had c=%.4f < -1.045F", h, c));

            // Strictly monotonically increasing with height
            assertTrue(c >= prevC - 1e-5F,
                String.format("Continentalness must monotonically increase with height: at h=%.4f got c=%.4f vs prev %.4f", h, c, prevC));
            prevC = c;
        }
    }

    @Test
    @DisplayName("Defect 2: Island inland control point adjustment does not make all islands mushroom islands")
    void testIslandInlandSliderDecoupledFromMushroomSelection() {
        IslandSettings settings = IslandSettings.makeDefault();
        Seed seed = new Seed(987654321L);
        int oceanDepth = 30;

        ControlPoints cpDefault = new ControlPoints(0.0F, 0.074F, 0.102F, 0.13144F, 0.2210F, 0.3576F, 0.4717F);
        ControlPoints cpShifted = new ControlPoints(0.08F, 0.074F, 0.102F, 0.13144F, 0.2210F, 0.3576F, 0.4717F);

        ArchipelagoPopulator popDefault = new ArchipelagoPopulator(settings, this.levels, cpDefault, seed, oceanDepth);
        ArchipelagoPopulator popShifted = new ArchipelagoPopulator(settings, this.levels, cpShifted, seed, oceanDepth);

        int sampleCount = 0;
        int nonMushroomCount = 0;

        // Sample a grid of positions across ocean
        for (int x = -10000; x <= 10000; x += 1000) {
            for (int z = -10000; z <= 10000; z += 1000) {
                Cell cellA = new Cell();
                cellA.height = this.levels.water - 0.3F;
                cellA.controlPoint = 0.03F; // Deep ocean basin
                cellA.terrain = TerrainType.DEEP_OCEAN;

                Cell cellB = new Cell();
                cellB.height = this.levels.water - 0.3F;
                cellB.controlPoint = 0.03F;
                cellB.terrain = TerrainType.DEEP_OCEAN;

                popDefault.apply(cellA, x, z);
                popShifted.apply(cellB, x, z);

                // Both populators must produce the EXACT same terrain classification regardless of islandInland slider
                assertEquals(cellA.terrain, cellB.terrain,
                    String.format("Position (%d, %d): terrain mismatch between islandInland=0.0 (%s) and islandInland=0.08 (%s)",
                        x, z, cellA.terrain.getName(), cellB.terrain.getName()));

                if (cellA.height > this.levels.water) {
                    sampleCount++;
                    if (cellA.terrain != TerrainType.MUSHROOM_FIELDS) {
                        nonMushroomCount++;
                    }
                }
            }
        }

        // If any exposed islands were sampled, verify that they are NOT all mushroom islands
        if (sampleCount > 0) {
            assertTrue(nonMushroomCount > 0,
                String.format("Expected non-mushroom islands to exist, but all %d sampled islands were mushroom fields!", sampleCount));
        }
    }

    private static class TrackingWorldLookup extends WorldLookup {
        int fallbackCount = 0;

        TrackingWorldLookup(Heightmap heightmap, Levels levels) {
            super(heightmap, levels);
        }

        @Override
        public boolean applyCell(Cell cell, int x, int z, boolean load, boolean applyClimate) {
            this.fallbackCount++;
            cell.height = -999.0F;
            return false;
        }
    }

    @Test
    @DisplayName("Verify GridLookup eliminates checkerboard/repeating grid artifact across non-quart zooms")
    void testGridLookupNoCheckerboardArtifactAtOddZooms() {
        int size = 64;
        PreviewGrid grid = new PreviewGrid(size);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                Cell cell = grid.getCell(x, z);
                cell.height = 0.50F + (x + z * size) * 0.0001F;
                cell.controlPoint = 0.30F;
            }
        }

        // Test non-multiples of 4 zooms (e.g. 5, 6 (Zoom 95), 7, 9, 10, 15, 50)
        int[] oddZooms = {3, 5, 6, 7, 9, 10, 11, 15, 23, 50};
        for (int zoom : oddZooms) {
            TrackingWorldLookup tracker = new TrackingWorldLookup(this.defaultHeightmap, this.levels);
            PreviewTileClimateSampler.GridLookup lookup = new PreviewTileClimateSampler.GridLookup(
                grid, tracker, 0.0F, 0.0F, zoom
            );

            // Simulate biome resolution loop sampling quart-aligned block coordinates
            for (int z = 0; z < size; z++) {
                int worldZ = z * zoom;
                int quartZ = QuartPos.fromBlock(worldZ);
                int blockZ = QuartPos.toBlock(quartZ);
                for (int x = 0; x < size; x++) {
                    int worldX = x * zoom;
                    int quartX = QuartPos.fromBlock(worldX);
                    int blockX = QuartPos.toBlock(quartX);

                    Cell result = lookup.lookupBlock(blockX, blockZ);
                    // The lookup must resolve from grid, NOT fall back to worldLookup!
                    assertFalse(result.height <= -900.0F,
                        String.format("Zoom %d at pixel (%d, %d) fell back to worldLookup creating grid artifact!", zoom, x, z));
                }
            }

            assertEquals(0, tracker.fallbackCount,
                String.format("Zoom %d must have 0 fallbacks inside grid bounds, but had %d", zoom, tracker.fallbackCount));
        }
    }

    @Test
    @DisplayName("Verify activeCell overrides lookup for pixel-exact climate sampling without aliasing")
    void testActiveCellOverridesLookupForExactPixelClimateSampling() {
        int size = 16;
        PreviewGrid grid = new PreviewGrid(size);
        TrackingWorldLookup tracker = new TrackingWorldLookup(this.defaultHeightmap, this.levels);
        PreviewTileClimateSampler.GridLookup lookup = new PreviewTileClimateSampler.GridLookup(
            grid, tracker, 0.0F, 0.0F, 6
        );

        Cell customCell = new Cell();
        customCell.height = 0.777F;
        customCell.controlPoint = 0.654F;

        // When activeCell is set, lookup returns activeCell regardless of context coordinates
        lookup.setActiveCell(customCell);
        Cell fromBlock = lookup.lookupBlock(100, 200);
        assertEquals(0.777F, fromBlock.height, 1e-5F);
        assertEquals(0.654F, fromBlock.controlPoint, 1e-5F);

        DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(500, 64, 500);
        Cell fromContext = lookup.lookup(ctx);
        assertEquals(0.777F, fromContext.height, 1e-5F);

        // When activeCell is cleared, lookup returns to grid
        lookup.setActiveCell(null);
        Cell normal = lookup.lookupBlock(0, 0);
        assertFalse(Float.compare(0.777F, normal.height) == 0,
            "After clearing activeCell, lookup must return grid cell rather than activeCell");
    }

    @Test
    @DisplayName("Verify preview continentalness consistency between Zoom 95 (zoom=6) and Zoom 100 (zoom=1)")
    void testPreviewContinentalnessConsistencyZoom95VsZoom100() {
        // Beach cell just above waterline
        Cell beachCell = new Cell();
        beachCell.height = this.levels.water + 0.01F;
        beachCell.controlPoint = this.defaultControlPoints.beach;
        beachCell.terrain = TerrainType.BEACH;

        // Ocean cell below waterline
        Cell oceanCell = new Cell();
        oceanCell.height = this.levels.water - 0.05F;
        oceanCell.controlPoint = this.defaultControlPoints.shallowOcean;
        oceanCell.terrain = TerrainType.SHALLOW_OCEAN;

        // Preview grids at zoom 1 (Zoom 100) and zoom 6 (Zoom 95)
        PreviewGrid grid1 = new PreviewGrid(16);
        PreviewGrid grid6 = new PreviewGrid(16);
        grid1.getCell(4, 4).copyFrom(beachCell);
        grid6.getCell(4, 4).copyFrom(beachCell);
        grid1.getCell(2, 2).copyFrom(oceanCell);
        grid6.getCell(2, 2).copyFrom(oceanCell);

        TrackingWorldLookup tracker = new TrackingWorldLookup(this.defaultHeightmap, this.levels);
        PreviewTileClimateSampler.GridLookup lookup1 = new PreviewTileClimateSampler.GridLookup(grid1, tracker, 0, 0, 1);
        PreviewTileClimateSampler.GridLookup lookup6 = new PreviewTileClimateSampler.GridLookup(grid6, tracker, 0, 0, 6);

        PreviewTileClimateSampler sampler1 = new PreviewTileClimateSampler(lookup1, this.defaultHeightmap, CellSampler.Field.CONTINENT);
        PreviewTileClimateSampler sampler6 = new PreviewTileClimateSampler(lookup6, this.defaultHeightmap, CellSampler.Field.CONTINENT);

        // 1. With activeCell set (as done in resolveSurfaceTile for each pixel)
        lookup1.setActiveCell(beachCell);
        double cBeach1 = sampler1.compute(new DensityFunction.SinglePointContext(4, 64, 4));
        lookup1.setActiveCell(null);

        lookup6.setActiveCell(beachCell);
        double cBeach6 = sampler6.compute(new DensityFunction.SinglePointContext(24, 64, 24));
        lookup6.setActiveCell(null);

        // Continentalness must match and be >= C4.min (-0.19F)
        assertEquals(cBeach6, cBeach1, 1e-5F, "Beach cell must evaluate to the same continentalness at Zoom 95 and Zoom 100");
        assertTrue(cBeach1 >= Continentalness.COAST.min() - 1e-5F,
            String.format("Beach cell continentalness %.4f must be >= -0.19F, not ocean!", cBeach1));

        // 2. Ocean cell
        lookup1.setActiveCell(oceanCell);
        double cOcean1 = sampler1.compute(new DensityFunction.SinglePointContext(2, 60, 2));
        lookup1.setActiveCell(null);

        lookup6.setActiveCell(oceanCell);
        double cOcean6 = sampler6.compute(new DensityFunction.SinglePointContext(12, 60, 12));
        lookup6.setActiveCell(null);

        assertEquals(cOcean6, cOcean1, 1e-5F, "Ocean cell must evaluate to the same continentalness at Zoom 95 and Zoom 100");
        assertTrue(cOcean1 < Continentalness.COAST.min(),
            String.format("Ocean cell continentalness %.4f must be < -0.19F", cOcean1));
    }

    @Test
    @DisplayName("Verify coastal transition threshold weirdness never falls into river valley window")
    void testCoastalTransitionThresholdWeirdnessNotValley() {
        // 1. Dry coastal land cell right at shoreline/transition threshold (y = 63, just above waterline 62)
        Cell beachCell = new Cell();
        beachCell.height = this.levels.water + (1.0F / this.levels.terrainScaleFactor);
        beachCell.controlPoint = this.defaultControlPoints.beach;
        beachCell.terrain = TerrainType.BEACH;
        beachCell.erosionMask = false;
        beachCell.weirdness = 0.0F; // unassigned or zeroed weirdness

        // Continentalness must be in C4 (Coast)
        float cBeach = CellSampler.Field.CONTINENT.read(beachCell, this.defaultHeightmap);
        assertTrue(cBeach >= Continentalness.COAST.min() && cBeach <= Continentalness.COAST.max(),
            String.format("Coastal transition cell must have C4 continentalness, got %.4f", cBeach));

        // Weirdness must evaluate to the vanilla beach slice center (-0.15F)
        float wBeach = CellSampler.Field.WEIRDNESS.read(beachCell, this.defaultHeightmap);
        assertEquals(-0.15F, wBeach, 1e-5F,
            String.format("Coastal transition cell weirdness must evaluate to -0.15F for beach resolution, got %.4f", wBeach));

        // 2. An actual river carver cell must retain 0.0F weirdness for river multi-noise resolution
        Cell riverCell = new Cell();
        riverCell.height = this.levels.water + (1.0F / this.levels.terrainScaleFactor);
        riverCell.controlPoint = this.defaultControlPoints.beach;
        riverCell.terrain = TerrainType.RIVER;
        riverCell.erosionMask = true;
        riverCell.weirdness = 0.0F;

        float wRiver = CellSampler.Field.WEIRDNESS.read(riverCell, this.defaultHeightmap);
        assertEquals(0.0F, wRiver, 1e-5F, "Actual river carver cell must evaluate to 0.0F weirdness for river resolution");

        // 3. Inland cell must retain its natural weirdness without modification
        Cell inlandCell = new Cell();
        inlandCell.height = this.levels.water + 0.2F;
        inlandCell.controlPoint = this.defaultControlPoints.inland + 0.1F;
        inlandCell.terrain = TerrainType.FLATS;
        inlandCell.erosionMask = false;
        inlandCell.weirdness = 0.33F;

        float wInland = CellSampler.Field.WEIRDNESS.read(inlandCell, this.defaultHeightmap);
        assertEquals(0.33F, wInland, 1e-5F, "Inland cell weirdness must remain untouched");
    }

    @Test
    @DisplayName("Verify vanilla OverworldBiomeBuilder resolves beach biomes for C4 Coast and w = -0.15F")
    void testVanillaCoastBiomeResolution() throws Exception {
        net.minecraft.world.level.biome.OverworldBiomeBuilder builder = new net.minecraft.world.level.biome.OverworldBiomeBuilder();
        java.util.List<com.mojang.datafixers.util.Pair<net.minecraft.world.level.biome.Climate.ParameterPoint, net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>>> biomes = new java.util.ArrayList<>();
        java.lang.reflect.Method addBiomes = net.minecraft.world.level.biome.OverworldBiomeBuilder.class.getDeclaredMethod("addBiomes", java.util.function.Consumer.class);
        addBiomes.setAccessible(true);
        addBiomes.invoke(builder, (java.util.function.Consumer<com.mojang.datafixers.util.Pair<net.minecraft.world.level.biome.Climate.ParameterPoint, net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>>>) biomes::add);
        net.minecraft.world.level.biome.Climate.ParameterList<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>> list = new net.minecraft.world.level.biome.Climate.ParameterList<>(biomes);

        // Verify across temperature (-0.6 cold to +0.6 hot) and erosion (-0.6 steep to +0.6 flat)
        float[] temps = {-0.6F, 0.0F, 0.6F};
        float[] erosions = {-0.6F, 0.0F, 0.6F};
        for (float t : temps) {
            for (float e : erosions) {
                net.minecraft.world.level.biome.Climate.TargetPoint target = new net.minecraft.world.level.biome.Climate.TargetPoint(
                    net.minecraft.world.level.biome.Climate.quantizeCoord(t),
                    net.minecraft.world.level.biome.Climate.quantizeCoord(0.0F),
                    net.minecraft.world.level.biome.Climate.quantizeCoord(-0.185F), // Coast C4
                    net.minecraft.world.level.biome.Climate.quantizeCoord(e),
                    net.minecraft.world.level.biome.Climate.quantizeCoord(0.0F),
                    net.minecraft.world.level.biome.Climate.quantizeCoord(-0.15F) // Beach weirdness
                );
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> found = list.findValue(target);
                String name = found.location().getPath();
                assertTrue(
                    name.contains("beach") || name.contains("shore") || name.equals("desert"),
                    String.format("Expected coastal biome for T=%.2f, E=%.2f, got %s", t, e, found.location())
                );
            }
        }
    }
}
