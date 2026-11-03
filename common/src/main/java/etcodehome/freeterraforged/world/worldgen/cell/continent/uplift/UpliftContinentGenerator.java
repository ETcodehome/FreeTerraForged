package etcodehome.freeterraforged.world.worldgen.cell.continent.uplift;

import etcodehome.freeterraforged.world.worldgen.cell.continent.advanced.AbstractContinent;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.domain.Domain;
import etcodehome.freeterraforged.world.worldgen.noise.domain.Domains;
import etcodehome.freeterraforged.world.worldgen.noise.module.Line;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import etcodehome.freeterraforged.concurrent.Resource;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.continent.SimpleContinent;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public class UpliftContinentGenerator extends AbstractContinent implements SimpleContinent, PlateUpliftBridge.BridgeProvider {
    protected float frequency;
    protected float variance;
    protected int varianceSeed;
    protected Domain warp;
    protected Domain cleanWarp;
    protected Noise cliffNoise;
    protected Noise bayNoise;
    protected Levels levels;

    protected WorldSettings.Continent continentSettings;

    protected final float macroWarpStrength;
    protected final float coastalComplexity;
    protected volatile float bridgeChance;
    protected PlateUpliftBridge plateUpliftBridge;
    private final int bridgeSeed;

    // Constant 8-neighbor lookup tables for flat 1D traversal and bitmask indexing
    private static final int[] NBR_DX = { -1, 0, 1, -1, 1, -1, 0, 1 };
    private static final int[] NBR_DY = { -1, -1, -1, 0, 0, 1, 1, 1 };

    protected UpliftContinentGenerator(int seed, float jitter, float frequency, float bridgeChance) {
        this(seed, jitter, frequency, bridgeChance, WorldSettings.Continent.macroWarpStrengthSetting.defaultValue, WorldSettings.Continent.coastalComplexitySetting.defaultValue);
    }

    protected UpliftContinentGenerator(int seed, float jitter, float frequency, float bridgeChance, float macroWarpStrength, float coastalComplexity) {
        super(seed, jitter);
        this.frequency = frequency;
        this.bridgeChance = bridgeChance;
        this.macroWarpStrength = macroWarpStrength;
        this.coastalComplexity = coastalComplexity;
        this.controlPoints = WorldSettings.makeDefault().controlPoints;
        this.bridgeSeed = seed ^ 0x5a5a5a5a;
        this.plateUpliftBridge = new PlateUpliftBridge(this, seed, jitter, frequency, 9999, 0.8F, 0.5F, 0.75F);
    }

    public void setControlPoints(WorldSettings.ControlPoints controlPoints) {
        this.controlPoints = controlPoints;
    }

    public UpliftContinentGenerator(Seed seed, GeneratorContext context) {
        super(seed, context);
        WorldSettings settings = context.preset.world();
        int tectonicScale = settings.continent.continentScale * 4;
        this.continentSettings = settings.continent;
        this.macroWarpStrength = settings.continent.macroWarpStrength;
        this.coastalComplexity = settings.continent.coastalComplexity;
        this.frequency = 1.0F / tectonicScale;
        this.varianceSeed = seed.next();
        this.variance = settings.continent.continentSizeVariance;

        // We extract identical seeds so both domains share the exact same macro-shape.
        // If we pass 'seed' directly, seed.next() desyncs the two warps entirely.
        int warpSeedX = seed.next();
        int warpSeedZ = seed.next();

        this.warp = this.createWarp(warpSeedX, warpSeedZ, tectonicScale, settings.continent, true);
        this.cleanWarp = this.createWarp(warpSeedX, warpSeedZ, tectonicScale, settings.continent, false);

        float frequency = 1.0F / this.frequency;

        Noise cliffNoise = Noises.simplex2(seed.next(), this.continentScale / 2, 3, 2.2F, 0.35F);
        cliffNoise = Noises.clamp(cliffNoise, 0.1F, 0.25F);
        cliffNoise = Noises.map(cliffNoise, 0.0F, 1.0F);
        cliffNoise = Noises.frequency(cliffNoise, frequency);
        this.cliffNoise = cliffNoise;

        Noise bayNoise = Noises.simplex(seed.next(), 100, 2, 2.5F, 0.4F);
        bayNoise = Noises.mul(bayNoise, 0.1F);
        bayNoise = Noises.add(bayNoise, 0.9F);
        bayNoise = Noises.frequency(bayNoise, frequency);
        this.bayNoise = bayNoise;

        this.levels = context.levels;

        this.bridgeChance = settings.continent.bridgeChance;

        // Must stay the LAST seed.next() so existing noise seeds above are not shifted.
        this.bridgeSeed = seed.next();
        int tectonicSeed = seed.next();
        this.plateUpliftBridge = new PlateUpliftBridge(this, this.seed, this.jitter, tectonicSeed, settings.continent.tectonicPlateUplift, settings.continent.tectonicRounding);
    }

    @Override
    public void apply(Cell cell, float rawX, float rawY) {
        float wx = this.warp.getX(rawX, rawY, 0);
        float wy = this.warp.getZ(rawX, rawY, 0);
        float x = wx * this.frequency;
        float y = wy * this.frequency;
        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);
        int cellX = xi;
        int cellY = yi;
        float cellPointX = x;
        float cellPointY = y;
        float nearest = Float.MAX_VALUE;

        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx = xi - 1; cx <= xi + 1; ++cx) {
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                float px = cx + vec.x() * this.jitter;
                float py = cy + vec.y() * this.jitter;
                float dist2 = Line.distSq(x, y, px, py);
                if (dist2 < nearest) {
                    cellPointX = px;
                    cellPointY = py;
                    cellX = cx;
                    cellY = cy;
                    nearest = dist2;
                }
            }
        }

        // We always resolve a stable continent centre even for continents that get skipped.
        // RiverCache / getRivermap() / getNearestCenter() all rely on cell.continentX/continentZ so if we return early
        // without setting them they keep whatever value this pooled Cell last held (often a leftover
        // (0,0) from a previous tile), causing an unrelated continents rivers to be carved here instead.
        PlateUpliftBridge.CellVoronoi voronoi = this.plateUpliftBridge.getVoronoi(cellX, cellY);
        cell.continentX = Math.round(voronoi.centroidX / this.frequency);
        cell.continentZ = Math.round(voronoi.centroidY / this.frequency);

        // early exit guard to avoid processing skipped continents
        if (this.shouldSkip(cellX, cellY)) {
            return;
        }

        // squared distance to the nearest CLOSED border (borders bridged to a neighbouring continent are ignored)
        nearest = this.getBoundaryDistanceSq(x, y, cellX, cellY, cellPointX, cellPointY);

        // process regular continent masks
        cell.continentDistance = NoiseUtil.sqrt(nearest);
        cell.continentEdge = this.getDistanceValue(x, y, cellX, cellY, nearest);
        cell.continentId = AbstractContinent.getCellValue(this.seed, cellX, cellY);

        // this updates continent centers to the voronoi centroid.
        // we remap by the water level so the continent uplift is usefully placed above the water line
        // then an additional 15ish percent insets from coastal boundaries
        float upliftGradient = getSmoothVoronoiGradient(cell, rawX, rawY);
        upliftGradient = shiftAndRemap(upliftGradient, levels.water);
        upliftGradient = shiftAndRemap(upliftGradient, 0.15F);

        // rescale the uplift to handle continental variance
        cell.continentSizeModifier = getContinentSizeModifier(cellX, cellY);
        if (cell.continentSizeModifier != 1.0F) {
            upliftGradient = shiftAndRemap(upliftGradient, (1.0F - cell.continentSizeModifier));
        }

        // use the continent edge values to guarantee we fall to the ocean near the ocean.
        // sometimes this can look a little rough but havent found a more reliable way yet.
        float customPeak = shiftAndRemap(cell.continentEdge, levels.water);
        if (customPeak < 0.05F && customPeak < upliftGradient) {
            upliftGradient = customPeak;
        }
        cell.waterTable = upliftGradient;
    }

    // ------------------------------------------------------------------------------------------
    // Continent bridging
    // ------------------------------------------------------------------------------------------

    /**
     * Biases continent skipping so that continents with bridges to other continents get skipped
     * significantly less near the start (low skipping thresholds), causing lonely isolated continents
     * to be the ones skipped early.
     */
    @Override
    protected boolean shouldSkip(int cellX, int cellY) {
        if (!this.hasSkipping || this.isDefaultContinent(cellX, cellY)) {
            return false;
        }
        if (this.bridgeChance <= 0.0F) {
            return super.shouldSkip(cellX, cellY);
        }

        float t = this.skipThreshold;
        float lonelyT = NoiseUtil.sqrt(t * NoiseUtil.sqrt(t));
        float strength = NoiseUtil.clamp(this.bridgeChance / 0.35F, 0.0F, 1.0F);
        float maxEffectiveT = NoiseUtil.lerp(t, lonelyT, strength);

        float rawSkip = getCellValue(this.skippingSeed, cellX, cellY);
        // Fast path 1: if raw roll is above the maximum possible threshold (lonely), it never skips
        if (rawSkip >= maxEffectiveT) {
            return false;
        }

        // Fast path 2: size-reduced continents (< 1.0) can never bridge, so they are always lonely
        if (this.getContinentSizeModifier(cellX, cellY) < 1.0F) {
            return true;
        }

        int bridges = 0;
        for (int i = 0; i < 8; i++) {
            int nx = cellX + NBR_DX[i];
            int ny = cellY + NBR_DY[i];
            if (this.getContinentSizeModifier(nx, ny) >= 1.0F
                    && this.bridgeRoll(cellX, cellY, nx, ny) < this.bridgeChance) {
                bridges++;
            }
        }

        if (bridges == 0) {
            return true;
        }

        float biasedT = (bridges == 1) ? (t * t) : (t * t * t);
        float effectiveT = NoiseUtil.lerp(t, biasedT, strength);
        return rawSkip < effectiveT;
    }

    /**
     * Only continents that spawned (not skipped) and are full size may bridge. The full-size requirement matters:
     * getVariedDistanceValue scales distance per cell, which would create a seam across an open border if the two
     * cells had different modifiers.
     */
    public boolean canBridge(int cx, int cy) {
        return !this.shouldSkip(cx, cy) && this.getContinentSizeModifier(cx, cy) >= 1.0F;
    }

    /** Symmetric per-border roll in [0,1]; identical when evaluated from either side of the border. */
    public float bridgeRoll(int ax, int ay, int bx, int by) {
        if (ax > bx || (ax == bx && ay > by)) {
            int t = ax; ax = bx; bx = t;
            t = ay; ay = by; by = t;
        }
        int s = this.bridgeSeed ^ (bx * 73856093) ^ (by * 19349663);
        return 0.5F + NoiseUtil.valCoord2D(s, ax, ay) * 0.5F;
    }

    public boolean isBridged(int ax, int ay, int bx, int by) {
        return this.bridgeChance > 0.0F
                && this.canBridge(ax, ay)
                && this.canBridge(bx, by)
                && this.bridgeRoll(ax, ay, bx, by) < this.bridgeChance;
    }

    private static float distToSegmentSq(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float l2 = dx * dx + dy * dy;
        float t = l2 < 1.0e-12F ? 0.0F : NoiseUtil.clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0.0F, 1.0F);
        float ex = px - (ax + t * dx), ey = py - (ay + t * dy);
        return ex * ex + ey * ey;
    }

    /**
     * Squared distance (cell units) from (x,y) to the nearest closed border of the merged landmass.
     * When all borders are unbridged (or for isolated continents), this computes the exact distance to
     * the local cell's Voronoi boundary. When borders are bridged, this evaluates all true coastal Voronoi
     * polygon segments in the 5x5 window around (cellX, cellY) with continuous boundary interpolation.
     */
    float getBoundaryDistanceSq(float x, float y, int cellX, int cellY, float ax, float ay) {
        PlateUpliftBridge.CellVoronoi v = this.plateUpliftBridge.getVoronoi(cellX, cellY);

        boolean hasAnyBridge = false;
        if (this.bridgeChance > 0.0F && this.canBridge(cellX, cellY)) {
            for (int k = 0; k < v.edgeCount; k++) {
                if (v.isBridged[k]) {
                    hasAnyBridge = true;
                    break;
                }
            }
        }

        // If this cell has no bridges to any neighbor, all of its Voronoi edges are closed coasts/borders.
        // Since (x, y) is inside cell (cellX, cellY), its nearest closed boundary is simply its own nearest edge.
        if (!hasAnyBridge) {
            float bestSq = Float.MAX_VALUE;
            for (int k = 0; k < v.edgeCount; k++) {
                float p1x = v.mx[k] + v.lo[k] * v.tx[k];
                float p1y = v.my[k] + v.lo[k] * v.ty[k];
                float p2x = v.mx[k] + v.hi[k] * v.tx[k];
                float p2y = v.my[k] + v.hi[k] * v.ty[k];
                float dSq = distToSegmentSq(x, y, p1x, p1y, p2x, p2y);
                if (dSq < bestSq) {
                    bestSq = dSq;
                }
            }
            return bestSq == Float.MAX_VALUE ? 4.0F : bestSq;
        }

        // Bridged landmass path: evaluate the closed Voronoi edges in the 5x5 window around (cellX, cellY).
        // Using a 5x5 cell window guarantees that all Delaunay Voronoi edges (including distance-2 neighbors
        // at high jitter >= 0.85) are continuously evaluated without 3x3 truncation or boundary jumps.
        float bestSq = Float.MAX_VALUE;

        for (int dy = -2; dy <= 2; dy++) {
            int cy = cellY + dy;
            for (int dx = -2; dx <= 2; dx++) {
                int cx = cellX + dx;
                if (this.shouldSkip(cx, cy)) {
                    continue; // Skip ocean cells; coastal edges are evaluated from adjacent land cells
                }

                PlateUpliftBridge.CellVoronoi cv = this.plateUpliftBridge.getVoronoi(cx, cy);
                for (int k = 0; k < cv.edgeCount; k++) {
                    if (cv.isBridged[k]) {
                        continue; // Open bridged borders are internal ridges, not coasts
                    }

                    int ncx = cv.ncx[k];
                    int ncy = cv.ncy[k];
                    // Undirected edge deduplication: only deduplicate internal land-land borders
                    if (!this.shouldSkip(ncx, ncy) && (ncx < cx || (ncx == cx && ncy < cy)) && Math.abs(ncx - cellX) <= 2 && Math.abs(ncy - cellY) <= 2) {
                        continue;
                    }

                    float p1x = cv.mx[k] + cv.lo[k] * cv.tx[k];
                    float p1y = cv.my[k] + cv.lo[k] * cv.ty[k];
                    float p2x = cv.mx[k] + cv.hi[k] * cv.tx[k];
                    float p2y = cv.my[k] + cv.hi[k] * cv.ty[k];
                    float dSq = distToSegmentSq(x, y, p1x, p1y, p2x, p2y);
                    if (dSq < bestSq) {
                        bestSq = dSq;
                    }
                }
            }
        }

        return bestSq == Float.MAX_VALUE ? 4.0F : bestSq;
    }

    // ------------------------------------------------------------------------------------------

    public float shiftAndRemap(float value, float threshold) {
        if (value <= threshold) {
            return 0.0F;
        }
        float remapped = (value - threshold) / (1.0F - threshold);
        return NoiseUtil.clamp(remapped, 0.0F, 1.0F);
    }

    /**
     * Generates a linear polygonal pyramid strictly in unwarped block space.
     * Peak relies completely on true cellPoint to ensure zero boundary discontinuities.
     */
    public float getSmoothVoronoiGradient(Cell cell, float rawX, float rawY) {
        return this.plateUpliftBridge.computeSmoothVoronoiGradient(cell, rawX, rawY);
    }

    @Override
    public float getEdgeValue(float x, float z) {
        float wx = this.warp.getX(x, z, 0);
        float wy = this.warp.getZ(x, z, 0);
        float px = wx * this.frequency;
        float py = wy * this.frequency;
        int xi = NoiseUtil.floor(px);
        int yi = NoiseUtil.floor(py);
        int cellX = xi;
        int cellY = yi;
        float cellPointX = px;
        float cellPointY = py;
        float nearest = Float.MAX_VALUE;

        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx = xi - 1; cx <= xi + 1; ++cx) {
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                float cpx = cx + vec.x() * this.jitter;
                float cpy = cy + vec.y() * this.jitter;
                float dist2 = Line.distSq(px, py, cpx, cpy);
                if (dist2 < nearest) {
                    cellPointX = cpx;
                    cellPointY = cpy;
                    cellX = cx;
                    cellY = cy;
                    nearest = dist2;
                }
            }
        }

        if (this.shouldSkip(cellX, cellY)) {
            return 0.0F;
        }

        nearest = this.getBoundaryDistanceSq(px, py, cellX, cellY, cellPointX, cellPointY);
        return this.getDistanceValue(px, py, cellX, cellY, nearest);
    }

    @Override
    public long getNearestCenter(float x, float z) {
        float wx = this.warp.getX(x, z, 0);
        float wy = this.warp.getZ(x, z, 0);
        float px = wx * this.frequency;
        float py = wy * this.frequency;
        int xi = NoiseUtil.floor(px);
        int yi = NoiseUtil.floor(py);
        float nearest = Float.MAX_VALUE;
        int bestCellX = xi;
        int bestCellY = yi;

        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx = xi - 1; cx <= xi + 1; ++cx) {
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                float cpx = cx + vec.x() * this.jitter;
                float cpy = cy + vec.y() * this.jitter;
                float dist2 = Line.distSq(px, py, cpx, cpy);
                if (dist2 < nearest) {
                    nearest = dist2;
                    bestCellX = cx;
                    bestCellY = cy;
                }
            }
        }
        PlateUpliftBridge.CellVoronoi voronoi = this.plateUpliftBridge.getVoronoi(bestCellX, bestCellY);
        int cx = Math.round(voronoi.centroidX / this.frequency);
        int cz = Math.round(voronoi.centroidY / this.frequency);
        return PosUtil.pack(cx, cz);
    }

    @Override
    public Rivermap getRivermap(int x, int z) {
        return this.riverCache.getRivers(x, z);
    }

    public float getBridgeChance() {
        return this.bridgeChance;
    }

    public PlateUpliftBridge getPlateUpliftBridge() {
        return this.plateUpliftBridge;
    }

    @Override
    public boolean isInBridgedSector(int cx, int cz, float dx, float dz) {
        if (this.bridgeChance <= 0.0F || this.plateUpliftBridge == null) {
            return false;
        }
        return this.plateUpliftBridge.isInBridgedSector(cx, cz, dx, dz);
    }

    @Override
    public boolean isInBridgedSector(float x, float z) {
        if (this.bridgeChance <= 0.0F || this.plateUpliftBridge == null) {
            return false;
        }
        return this.plateUpliftBridge.isInBridgedSector(x, z);
    }

    public float getTectonicPlateUplift() {
        return this.plateUpliftBridge != null ? this.plateUpliftBridge.getTectonicPlateUplift() : 0.0F;
    }

    public float getTectonicRounding() {
        return this.plateUpliftBridge != null ? this.plateUpliftBridge.getTectonicRounding() : 0.0F;
    }

    public float getMacroWarpStrength() {
        return this.macroWarpStrength;
    }

    public float getCoastalComplexity() {
        return this.coastalComplexity;
    }

    protected Domain createWarp(int seedX, int seedZ, int tectonicScale, WorldSettings.Continent continent, boolean applyLacunarity) {
        int mesoWarpScale = NoiseUtil.round(tectonicScale * 0.225F);
        float mesoStrength = NoiseUtil.round(tectonicScale * 0.33F);
        float mesoLacunarity = applyLacunarity ? continent.continentNoiseLacunarity : 0.0F;
        Domain mesoWarp = Domains.domain(
                Noises.perlin2(seedX, mesoWarpScale, continent.continentNoiseOctaves, mesoLacunarity, continent.continentNoiseGain),
                Noises.perlin2(seedZ, mesoWarpScale, continent.continentNoiseOctaves, mesoLacunarity, continent.continentNoiseGain),
                Noises.constant(mesoStrength)
        );

        if (continent.macroWarpStrength <= 0.0F) {
            return mesoWarp;
        }

        int macroWarpScale = NoiseUtil.round(tectonicScale * 0.75F);
        float macroStrength = tectonicScale * 0.40F * continent.macroWarpStrength;
        float macroLacunarity = applyLacunarity ? 2.2F : 0.0F;
        int macroSeedX = seedX + 7919;
        int macroSeedZ = seedZ + 104729;
        Domain macroWarp = Domains.domain(
                Noises.perlin2(macroSeedX, macroWarpScale, 3, macroLacunarity, 0.35F),
                Noises.perlin2(macroSeedZ, macroWarpScale, 3, macroLacunarity, 0.35F),
                Noises.constant(macroStrength)
        );

        return Domains.add(macroWarp, mesoWarp);
    }

    public float getContinentSizeModifier(int cellX, int cellY) {
        if (this.variance > 0.0f && !this.isDefaultContinent(cellX, cellY)) {
            float sizeValue = AbstractContinent.getCellValue(this.varianceSeed, cellX, cellY);
            return NoiseUtil.map(sizeValue, 0.0f, this.variance, this.variance);
        }
        return 1.0F;
    }

    protected float getDistanceValue(float x, float y, int cellX, int cellY, float distance) {
        distance = this.getVariedDistanceValue(cellX, cellY, distance);
        distance = NoiseUtil.sqrt(distance);
        distance = NoiseUtil.map(distance, 0.05F, 0.25F, 0.2F);
        distance = this.getCoastalDistanceValue(x, y, distance);
        if (distance < this.controlPoints.inland && distance >= this.controlPoints.shallowOcean) {
            distance = this.getCoastalDistanceValue(x, y, distance);
        }
        return distance;
    }

    protected float getVariedDistanceValue(int cellX, int cellY, float distance) {
        if (this.variance > 0.0f && !this.isDefaultContinent(cellX, cellY)) {
            float sizeValue = AbstractContinent.getCellValue(this.varianceSeed, cellX, cellY);
            float sizeModifier = NoiseUtil.map(sizeValue, 0.0f, this.variance, this.variance);
            distance *= sizeModifier;
        }
        return distance;
    }

    protected float getCoastalDistanceValue(float x, float y, float distance) {
        if (this.coastalComplexity <= 0.0F) {
            return distance;
        }
        if (distance > this.controlPoints.shallowOcean && distance < this.controlPoints.inland) {
            float alpha = distance / this.controlPoints.inland;
            float rawCliff = this.cliffNoise.compute(x, y, 0);
            float cliff = NoiseUtil.lerp(1.0F, rawCliff, this.coastalComplexity);
            distance = NoiseUtil.lerp(distance * cliff, distance, alpha);
            if (distance < this.controlPoints.shallowOcean) {
                float rawBay = this.bayNoise.compute(x, y, 0);
                float bay = NoiseUtil.lerp(1.0F, rawBay, this.coastalComplexity);
                distance = this.controlPoints.shallowOcean * bay;
            }
        }
        return distance;
    }

    protected static float midPoint(float a, float b) {
        return (a + b) * 0.5F;
    }

    protected static float getDistance(float x, float y, float ax, float ay, float bx, float by) {
        float mx = midPoint(ax, bx);
        float my = midPoint(ay, by);
        float dx = bx - ax;
        float dy = by - ay;
        float nx = -dy;
        float ny = dx;
        return getDistance2Line(x, y, mx, my, mx + nx, my + ny);
    }

    protected static float getDistance2Line(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float v = (x - ax) * dx + (y - ay) * dy;
        v /= dx * dx + dy * dy;
        float ox = ax + dx * v;
        float oy = ay + dy * v;
        return Line.distSq(x, y, ox, oy);
    }
}