package etcodehome.freeterraforged.world.worldgen.biome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.data.worldgen.preset.settings.IslandSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.SpawnType;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings.Properties;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainCategory;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.ArchipelagoPopulator;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.util.Seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContinentalnessRangeMappingTest {

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

        // 1. Mushroom Fields
        cell.terrain = TerrainType.MUSHROOM_FIELDS;
        cell.height = this.levels.water + 0.1F;
        cell.continentEdge = 0.03F;
        float cMushroom = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cMushroom >= Continentalness.MUSHROOM_FIELDS.min() && cMushroom <= Continentalness.MUSHROOM_FIELDS.max()) {
            hitMushroom = true;
        }

        // 2. Deep Ocean
        cell.terrain = TerrainType.DEEP_OCEAN;
        cell.height = this.levels.water - 0.2F;
        cell.continentEdge = 0.088F;
        float cDeepOcean = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cDeepOcean >= Continentalness.DEEP_OCEAN.min() && cDeepOcean <= Continentalness.DEEP_OCEAN.max()) {
            hitDeepOcean = true;
        }

        // 3. Shallow Ocean
        cell.terrain = TerrainType.SHALLOW_OCEAN;
        cell.height = this.levels.water - 0.1F;
        cell.continentEdge = 0.12F;
        float cOcean = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cOcean >= Continentalness.OCEAN.min() && cOcean <= Continentalness.OCEAN.max()) {
            hitOcean = true;
        }

        // 4. Coast
        cell.terrain = TerrainType.COAST;
        cell.height = this.levels.water + 0.05F;
        cell.continentEdge = 0.28F;
        float cCoast = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cCoast >= Continentalness.COAST.min() && cCoast <= Continentalness.COAST.max()) {
            hitCoast = true;
        }

        // 5. Near-Inland
        cell.terrain = TerrainType.NONE;
        cell.height = this.levels.water + 0.15F;
        cell.continentEdge = 0.40F;
        float cNearInland = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cNearInland >= Continentalness.NEAR_INLAND.min() && cNearInland <= Continentalness.NEAR_INLAND.max()) {
            hitNearInland = true;
        }

        // 6. Mid-Inland
        cell.continentEdge = 0.52F;
        float cMidInland = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        if (cMidInland >= Continentalness.MID_INLAND.min() && cMidInland <= Continentalness.MID_INLAND.max()) {
            hitMidInland = true;
        }

        // 7. Far-Inland
        cell.continentEdge = 0.85F;
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
    @DisplayName("Verify C0 continuity across all control point boundaries without step jumps")
    void testBoundaryContinuity() {
        float eps = 1e-5F;
        float tol = 2e-3F;
        Cell cell = new Cell();

        // 1. Deep Ocean -> Shallow Ocean at controlPoints.deepOcean (0.102F)
        cell.height = this.levels.water - 0.2F;
        cell.terrain = TerrainType.NONE;

        cell.continentEdge = this.defaultControlPoints.deepOcean - eps;
        float cDeep = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.continentEdge = this.defaultControlPoints.deepOcean;
        float cDeepExact = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.continentEdge = this.defaultControlPoints.deepOcean + eps;
        float cShallow = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(-0.455F, cDeepExact, 1e-4F, "Exact deep ocean boundary must meet -0.455F");
        assertEquals(-0.455F, cDeep, tol, "Deep ocean boundary approaching deepOcean must meet -0.455F");
        assertEquals(-0.455F, cShallow, tol, "Shallow ocean boundary leaving deepOcean must meet -0.455F");
        assertEquals(cDeep, cShallow, tol, "Deep -> Shallow boundary must be continuous");

        // 2. Shallow Ocean (submerged) -> Coast (onshore) at controlPoints.beach (0.2210F)
        cell.height = this.levels.water - 0.01F;
        cell.continentEdge = this.defaultControlPoints.beach - eps;
        float cSubmergedBeach = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.continentEdge = this.defaultControlPoints.beach;
        float cSubmergedBeachExact = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.height = this.levels.water + 0.01F;
        cell.continentEdge = this.defaultControlPoints.beach + eps;
        float cOnshoreBeach = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(-0.195F, cSubmergedBeachExact, 1e-4F, "Submerged beach boundary must meet -0.195F");
        assertEquals(-0.195F, cSubmergedBeach, tol, "Submerged beach approaching beach must meet -0.195F");
        assertEquals(-0.190F, cOnshoreBeach, tol, "Onshore coast start leaving beach must meet -0.190F");
        assertTrue(cSubmergedBeachExact < -0.19F, "Submerged beach must be strictly < -0.19F to prevent beach misbioming");
        assertTrue(cOnshoreBeach >= -0.19F, "Onshore coast must be >= -0.19F");

        // 3. Coast -> Near-Inland at controlPoints.coast (0.3576F)
        cell.height = this.levels.water + 0.1F;
        cell.continentEdge = this.defaultControlPoints.coast - eps;
        float cCoastEnd = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.continentEdge = this.defaultControlPoints.coast;
        float cCoastExact = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.continentEdge = this.defaultControlPoints.coast + eps;
        float cNearStart = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(-0.11F, cCoastExact, 1e-4F, "Coast boundary must meet -0.11F");
        assertEquals(-0.11F, cCoastEnd, tol, "Coast end approaching coast must meet -0.11F");
        assertEquals(-0.11F, cNearStart, tol, "Near-Inland start leaving coast must meet -0.11F");
        assertEquals(cCoastEnd, cNearStart, tol, "Coast -> Near-Inland boundary must be continuous");

        // 4. Near-Inland -> Mid-Inland at controlPoints.inland (0.4717F)
        cell.continentEdge = this.defaultControlPoints.inland - eps;
        float cNearEnd = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.continentEdge = this.defaultControlPoints.inland;
        float cInlandExact = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.continentEdge = this.defaultControlPoints.inland + eps;
        float cInteriorStart = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(0.03F, cInlandExact, 1e-4F, "Inland boundary must meet 0.03F");
        assertEquals(0.03F, cNearEnd, tol, "Near-Inland end approaching inland must meet 0.03F");
        assertEquals(0.03F, cInteriorStart, tol, "Mid-Inland start leaving inland must meet 0.03F");
        assertEquals(cNearEnd, cInteriorStart, tol, "Near-Inland -> Interior boundary must be continuous");

        // 5. Mid-Inland -> Far-Inland at interior mid-split (inland + 0.35 * (1.0 - inland))
        float midSplitEdge = this.defaultControlPoints.inland + 0.35F * (1.0F - this.defaultControlPoints.inland);
        cell.continentEdge = midSplitEdge - eps;
        float cMidEnd = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.continentEdge = midSplitEdge;
        float cMidSplitExact = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.continentEdge = midSplitEdge + eps;
        float cFarStart = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(0.30F, cMidSplitExact, 1e-4F, "Mid-split boundary must meet 0.30F");
        assertEquals(0.30F, cMidEnd, tol, "Mid-Inland end must meet 0.30F");
        assertEquals(0.30F, cFarStart, tol, "Far-Inland start must meet 0.30F");
        assertEquals(cMidEnd, cFarStart, tol, "Mid -> Far boundary must be continuous");

        // 6. Far-Inland peak at continentEdge = 1.0F
        cell.continentEdge = 1.0F;
        float cPeak = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(1.00F, cPeak, 1e-4F, "Continental interior peak must reach 1.00F");

        // 7. Mushroom island shore meeting deep ocean at islandCoast (0.074F)
        cell.terrain = TerrainType.MUSHROOM_FIELDS;
        cell.height = this.levels.water + 0.1F;
        cell.continentEdge = this.defaultControlPoints.islandCoast;
        float cMushroomShore = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        cell.terrain = TerrainType.DEEP_OCEAN;
        cell.height = this.levels.water - 0.2F;
        cell.continentEdge = this.defaultControlPoints.islandCoast;
        float cDeepOceanAtCoast = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

        assertEquals(-1.05F, cMushroomShore, 1e-4F, "Mushroom shore must meet -1.05F");
        assertTrue(cDeepOceanAtCoast > -1.05F, "Deep ocean water must strictly be > -1.05F to prevent mushroom fields");
        float expectedDeepOceanAtCoast = -1.00F + ((-0.455F - (-1.00F)) * (this.defaultControlPoints.islandCoast / this.defaultControlPoints.deepOcean));
        assertEquals(expectedDeepOceanAtCoast, cDeepOceanAtCoast, 1e-4F, "Deep ocean continentalness at islandCoast matches deep ocean curve");
    }

    @Test
    @DisplayName("Verify oceanic islands (TerrainCategory.ISLAND) never exceed Near-Inland max (0.03F)")
    void testOceanicIslandsNeverExceedNearInlandMax() {
        Cell cell = new Cell();
        cell.height = this.levels.water + 0.2F;

        Terrain[] islandTypes = {
            TerrainType.ISLAND,
            TerrainType.ISLAND_BEACH,
            TerrainType.ISLAND_MOUNTAINS
        };

        for (Terrain[] arr = islandTypes; ; ) {
            for (Terrain type : islandTypes) {
                cell.terrain = type;
                assertEquals(TerrainCategory.ISLAND, cell.terrain.getCategory(), "Must be categorized as ISLAND");

                // Sweep continentEdge across full [0.0, 1.0] range
                for (float edge = 0.0F; edge <= 1.0F; edge += 0.02F) {
                    cell.continentEdge = edge;
                    float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
                    assertTrue(c <= Continentalness.NEAR_INLAND.max() + 1e-5F,
                        String.format("Island type %s at edge %.3f had continentalness %.4f, exceeding NEAR_INLAND.max (0.03)",
                            type.getName(), edge, c));
                    assertTrue(c >= Continentalness.COAST.min() - 1e-5F,
                        String.format("Exposed island type %s at edge %.3f had continentalness %.4f below COAST.min (-0.19)",
                            type.getName(), edge, c));
                }
            }
            break;
        }
    }

    @Test
    @DisplayName("Verify mushroom islands map strictly to [-1.20F, -1.05F]")
    void testMushroomIslandParameterRange() {
        Cell cell = new Cell();
        cell.terrain = TerrainType.MUSHROOM_FIELDS;
        cell.height = this.levels.water + 0.15F;

        // At islandCoast: must be -1.05F
        cell.continentEdge = this.defaultControlPoints.islandCoast;
        float cShore = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(-1.05F, cShore, 1e-4F);

        // At islandInland: must be -1.20F
        cell.continentEdge = this.defaultControlPoints.islandInland;
        float cCenter = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(-1.20F, cCenter, 1e-4F);

        // Sweep continentEdge across [islandInland, islandCoast]
        for (float edge = 0.0F; edge <= this.defaultControlPoints.islandCoast; edge += 0.005F) {
            cell.continentEdge = edge;
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
        cell.continentEdge = 0.55F;

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

        cell.continentEdge = 0.38F;
        float cC = CellSampler.Field.CONTINENT.read(cell, hmC);
        float cD = CellSampler.Field.CONTINENT.read(cell, hmD);

        assertTrue(cC > Continentalness.COAST.max(),
            "With coast=0.30, edge 0.38 must be Near-Inland (c > -0.11), was " + cC);
        assertTrue(cD < Continentalness.NEAR_INLAND.min(),
            "With coast=0.45, edge 0.38 must be Coast (c < -0.11), was " + cD);

        // Mainland monotonicity test: as continentEdge increases from beach to 1.0, continentalness strictly increases
        float prevC = -1.0F;
        for (float edge = this.defaultControlPoints.beach; edge <= 1.0F; edge += 0.01F) {
            cell.continentEdge = edge;
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
        cell.continentEdge = 0.45F;
        cell.terrain = TerrainType.NONE;

        float hBefore = cell.height;
        float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        float hAfter = cell.height;

        assertEquals(hBefore, hAfter, 0.0F, "cell.height must remain unmodified after Field.CONTINENT evaluation");
        assertTrue(Float.isFinite(c), "Continentalness must be finite");
    }

    @Test
    @DisplayName("Defect 1: Underwater areas never misbiomed as beach (continentalness strictly < -0.19F)")
    void testSubmergedCellsNeverMisbiomedAsBeach() {
        Cell cell = new Cell();
        cell.height = this.levels.water - 0.05F; // Submerged cell

        // Test across shelf, beach control point, and inland transition zone while submerged
        float[] edges = {
            this.defaultControlPoints.deepOcean,
            this.defaultControlPoints.shallowOcean,
            this.defaultControlPoints.beach - 0.02F,
            this.defaultControlPoints.beach,
            this.defaultControlPoints.beach + 0.02F,
            this.defaultControlPoints.coast,
            this.defaultControlPoints.inland
        };

        for (float edge : edges) {
            cell.continentEdge = edge;
            cell.terrain = TerrainType.NONE;
            float cNone = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
            assertTrue(cNone < -0.19F,
                String.format("Submerged cell (NONE) at edge %.4f had c=%.4f; must be < -0.19F to prevent beach biome", edge, cNone));
            assertTrue(cNone <= -0.195F + 1e-5F,
                String.format("Submerged cell (NONE) at edge %.4f had c=%.4f; must be <= -0.195F", edge, cNone));

            cell.terrain = TerrainType.SHALLOW_OCEAN;
            float cOcean = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
            assertTrue(cOcean < -0.19F,
                String.format("Submerged cell (SHALLOW_OCEAN) at edge %.4f had c=%.4f; must be < -0.19F to prevent beach biome", edge, cOcean));
            assertTrue(cOcean <= -0.195F + 1e-5F,
                String.format("Submerged cell (SHALLOW_OCEAN) at edge %.4f had c=%.4f; must be <= -0.195F", edge, cOcean));
        }
    }

    @Test
    @DisplayName("Defect 3: Deep ocean is never flagged as mushroom fields (continentalness strictly > -1.05F)")
    void testDeepOceanNeverFlaggedAsMushroomFields() {
        Cell cell = new Cell();
        cell.height = this.levels.water - 0.20F; // Deep ocean floor
        cell.terrain = TerrainType.DEEP_OCEAN;

        // Sweep entire deep ocean range from abyssal floor (0.0F) up to deepOcean boundary (0.102F)
        for (float edge = 0.0F; edge <= this.defaultControlPoints.deepOcean; edge += 0.005F) {
            cell.continentEdge = edge;
            float c = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);

            assertTrue(c > Continentalness.MUSHROOM_FIELDS.max(),
                String.format("Deep ocean cell at edge %.4f had continentalness %.4f <= -1.05F, matching Mushroom Fields!", edge, c));
            assertTrue(c >= -1.00F - 1e-5F,
                String.format("Deep ocean cell at edge %.4f had continentalness %.4f < -1.00F", edge, c));
            assertTrue(c <= Continentalness.DEEP_OCEAN.max() + 1e-5F,
                String.format("Deep ocean cell at edge %.4f had continentalness %.4f > DEEP_OCEAN.max", edge, c));
        }

        // Verify that only exposed mushroom fields terrain (height > levels.water) can produce continentalness <= -1.05F
        cell.terrain = TerrainType.MUSHROOM_FIELDS;
        cell.height = this.levels.water - 0.10F; // Submerged near mushroom island
        cell.continentEdge = this.defaultControlPoints.islandCoast;
        float cSubmergedMushroom = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertTrue(cSubmergedMushroom > Continentalness.MUSHROOM_FIELDS.max(),
            "Submerged water surrounding mushroom island must be ocean (> -1.05F), was " + cSubmergedMushroom);

        cell.height = this.levels.water + 0.10F; // Dry exposed land
        float cExposedMushroom = CellSampler.Field.CONTINENT.read(cell, this.defaultHeightmap);
        assertEquals(-1.05F, cExposedMushroom, 1e-4F,
            "Exposed mushroom land at islandCoast must meet -1.05F");
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
                cellA.continentEdge = 0.03F; // Deep ocean basin
                cellA.terrain = TerrainType.DEEP_OCEAN;

                Cell cellB = new Cell();
                cellB.height = this.levels.water - 0.3F;
                cellB.continentEdge = 0.03F;
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
}
