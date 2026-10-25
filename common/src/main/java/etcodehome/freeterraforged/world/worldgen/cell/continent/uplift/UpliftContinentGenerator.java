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

public class UpliftContinentGenerator extends AbstractContinent implements SimpleContinent {
    protected float frequency;
    protected float variance;
    protected int varianceSeed;
    protected Domain warp;
    protected Domain cleanWarp;
    protected Noise cliffNoise;
    protected Noise bayNoise;
    protected Levels levels;

    protected WorldSettings.Continent continentSettings;

    protected float bridgeChance;
    protected float bridgeReach;
    protected PlateUpliftBridge plateUpliftBridge;
    private final int bridgeSeed;

    // Constant 8-neighbor lookup tables for flat 1D traversal and bitmask indexing
    private static final int[] NBR_DX = { -1, 0, 1, -1, 1, -1, 0, 1 };
    private static final int[] NBR_DY = { -1, -1, -1, 0, 0, 1, 1, 1 };
    private static final int[] NBR_OFFSET = { -6, -5, -4, -1, 1, 4, 5, 6 };

    private static final class BoundaryScratch {
        // 5x5 grid of cell points centered at (xi, yi)
        final float[] px = new float[25];
        final float[] py = new float[25];

        // 8-neighbor bisector geometry for clipping
        final float[] nx = new float[8];
        final float[] ny = new float[8];
        final float[] mx = new float[8];
        final float[] my = new float[8];
    }

    private static final ThreadLocal<BoundaryScratch> SCRATCH = ThreadLocal.withInitial(BoundaryScratch::new);

    public UpliftContinentGenerator(Seed seed, GeneratorContext context) {
        super(seed, context);
        WorldSettings settings = context.preset.world();
        int tectonicScale = settings.continent.continentScale * 4;
        this.continentSettings = settings.continent;
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

        Noise cliffNoise = Noises.simplex2(seed.next(), this.continentScale / 2, 2);
        cliffNoise = Noises.clamp(cliffNoise, 0.1F, 0.25F);
        cliffNoise = Noises.map(cliffNoise, 0.0F, 1.0F);
        cliffNoise = Noises.frequency(cliffNoise, frequency);
        this.cliffNoise = cliffNoise;

        Noise bayNoise = Noises.simplex(seed.next(), 100, 1);
        bayNoise = Noises.mul(bayNoise, 0.1F);
        bayNoise = Noises.add(bayNoise, 0.9F);
        bayNoise = Noises.frequency(bayNoise, frequency);
        this.bayNoise = bayNoise;

        this.levels = context.levels;

        this.bridgeChance = settings.continent.bridgeChance;
        this.bridgeReach = settings.continent.bridgeReach;

        // Must stay the LAST seed.next() so existing noise seeds above are not shifted.
        this.bridgeSeed = seed.next();
        int tectonicSeed = seed.next();
        this.plateUpliftBridge = new PlateUpliftBridge(this, this.seed, this.jitter, tectonicSeed, settings.continent.tectonicPlateUplift);
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
        cell.continentX = Math.round(cellPointX / this.frequency);
        cell.continentZ = Math.round(cellPointY / this.frequency);

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
     * the local cell's bisector boundary. When borders are bridged, this evaluates all true coastal Voronoi
     * polygon segments in the local 3x3 window around (x,y) with continuous boundary interpolation.
     */
    private float getBoundaryDistanceSq(float x, float y, int cellX, int cellY, float ax, float ay) {
        // Fast path 1: if bridging is globally disabled or this cell cannot bridge, all borders are closed.
        if (this.bridgeChance <= 0.0F || !this.canBridge(cellX, cellY)) {
            float bestSq = Float.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                int cx2 = cellX + NBR_DX[i];
                int cy2 = cellY + NBR_DY[i];
                NoiseUtil.Vec2f vec2 = NoiseUtil.cell(this.seed, cx2, cy2);
                float px2 = cx2 + vec2.x() * this.jitter;
                float py2 = cy2 + vec2.y() * this.jitter;
                float dist = getDistance(x, y, ax, ay, px2, py2);
                if (dist < bestSq) {
                    bestSq = dist;
                }
            }
            return bestSq == Float.MAX_VALUE ? 4.0F : bestSq;
        }

        // Fast path 2: single-pass check if all 8 neighbors are unbridged (isolated island).
        // If an open bridged border is found, break early to run the bridged boundary path.
        // Otherwise, bestSq is already computed over all 8 closed borders with zero duplicate passes.
        boolean anyOpen = false;
        float bestSq = Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            int cx2 = cellX + NBR_DX[i];
            int cy2 = cellY + NBR_DY[i];
            if (this.canBridge(cx2, cy2) && this.bridgeRoll(cellX, cellY, cx2, cy2) < this.bridgeChance) {
                anyOpen = true;
                break;
            }
            NoiseUtil.Vec2f vec2 = NoiseUtil.cell(this.seed, cx2, cy2);
            float px2 = cx2 + vec2.x() * this.jitter;
            float py2 = cy2 + vec2.y() * this.jitter;
            float dist = getDistance(x, y, ax, ay, px2, py2);
            if (dist < bestSq) {
                bestSq = dist;
            }
        }
        if (!anyOpen) {
            return bestSq == Float.MAX_VALUE ? 4.0F : bestSq;
        }

        // Bridged landmass path: evaluate the true closed Voronoi polygon segments in the 3x3 window around (xi, yi).
        BoundaryScratch s = SCRATCH.get();
        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);

        // Precompute the 5x5 grid points and canBridge bitmask centered at (xi, yi)
        int idx = 0;
        int canBridgeMask = 0;
        for (int dy = -2; dy <= 2; dy++) {
            int cy = yi + dy;
            for (int dx = -2; dx <= 2; dx++) {
                int cx = xi + dx;
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                s.px[idx] = cx + vec.x() * this.jitter;
                s.py[idx] = cy + vec.y() * this.jitter;
                if (this.canBridge(cx, cy)) {
                    canBridgeMask |= (1 << idx);
                }
                idx++;
            }
        }

        bestSq = Float.MAX_VALUE;

        // Iterate over the 3x3 cells (grid indices cdx=1..3, cdy=1..3 in the 5x5 array)
        for (int cdy = 1; cdy <= 3; cdy++) {
            int cy = yi + (cdy - 2);
            for (int cdx = 1; cdx <= 3; cdx++) {
                int cx = xi + (cdx - 2);
                int cIdx = cdy * 5 + cdx;
                float cpx = s.px[cIdx];
                float cpy = s.py[cIdx];
                boolean cCanBridge = (canBridgeMask & (1 << cIdx)) != 0;

                // Gather 8 neighbors of cell (cx, cy)
                int openMask = 0;
                for (int n = 0; n < 8; n++) {
                    int nIdx = cIdx + NBR_OFFSET[n];
                    float npx = s.px[nIdx];
                    float npy = s.py[nIdx];
                    float ux = npx - cpx;
                    float uy = npy - cpy;
                    float inv = 1.0F / NoiseUtil.sqrt(ux * ux + uy * uy);
                    s.nx[n] = ux * inv;
                    s.ny[n] = uy * inv;
                    s.mx[n] = (cpx + npx) * 0.5F;
                    s.my[n] = (cpy + npy) * 0.5F;
                    boolean nCanBridge = (canBridgeMask & (1 << nIdx)) != 0;
                    if (cCanBridge && nCanBridge) {
                        int ncx = cx + NBR_DX[n];
                        int ncy = cy + NBR_DY[n];
                        if (this.bridgeRoll(cx, cy, ncx, ncy) < this.bridgeChance) {
                            openMask |= (1 << n);
                        }
                    }
                }

                // Clip each closed bisector to find the true coastal Voronoi edge segment
                for (int k = 0; k < 8; k++) {
                    int nIdx = cIdx + NBR_OFFSET[k];
                    boolean nCanBridge = (canBridgeMask & (1 << nIdx)) != 0;
                    boolean bothSkipped = (!cCanBridge) && (!nCanBridge);
                    boolean isOpen = (openMask & (1 << k)) != 0;
                    if (bothSkipped || isOpen) {
                        continue; // Open borders or ocean-ocean borders are not coasts
                    }

                    // Undirected edge deduplication: only process each edge once
                    int nc_dx = cdx + NBR_DX[k];
                    int nc_dy = cdy + NBR_DY[k];
                    boolean nbrIn3x3 = (nc_dx >= 1 && nc_dx <= 3 && nc_dy >= 1 && nc_dy <= 3);
                    if (nbrIn3x3 && (NBR_DX[k] < 0 || (NBR_DX[k] == 0 && NBR_DY[k] < 0))) {
                        continue;
                    }

                    float tx = -s.ny[k], ty = s.nx[k];
                    float lo = -1.0e9F, hi = 1.0e9F;
                    boolean ok = true;

                    for (int j = 0; j < 8; j++) {
                        if (j == k) {
                            continue;
                        }
                        float a = tx * s.nx[j] + ty * s.ny[j];
                        float b = (s.mx[j] - s.mx[k]) * s.nx[j] + (s.my[j] - s.my[k]) * s.ny[j];
                        if (Math.abs(a) < 1.0e-6F) {
                            if (b < 0.0F) {
                                ok = false;
                                break;
                            }
                            continue;
                        }
                        float t = b / a;
                        if (a > 0.0F) {
                            if (t < hi) {
                                hi = t;
                            }
                        } else if (t > lo) {
                            lo = t;
                        }
                    }

                    if (ok && lo < hi && lo > -1.0e8F && hi < 1.0e8F) {
                        float p1x = s.mx[k] + lo * tx;
                        float p1y = s.my[k] + lo * ty;
                        float p2x = s.mx[k] + hi * tx;
                        float p2y = s.my[k] + hi * ty;
                        float dSq = distToSegmentSq(x, y, p1x, p1y, p2x, p2y);
                        if (dSq < bestSq) {
                            bestSq = dSq;
                        }
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
        try (Resource<Cell> resource = Cell.getResource()) {
            Cell cell = resource.get();
            this.apply(cell, x, z);
            return cell.continentEdge;
        }
    }

    @Override
    public long getNearestCenter(float x, float z) {
        try (Resource<Cell> resource = Cell.getResource()) {
            Cell cell = resource.get();
            this.apply(cell, x, z);
            return PosUtil.pack(cell.continentX, cell.continentZ);
        }
    }

    @Override
    public Rivermap getRivermap(int x, int z) {
        return this.riverCache.getRivers(x, z);
    }

    public float getBridgeChance() {
        return this.bridgeChance;
    }

    public float getBridgeReach() {
        return this.bridgeReach;
    }

    public PlateUpliftBridge getPlateUpliftBridge() {
        return this.plateUpliftBridge;
    }

    public float getTectonicPlateUplift() {
        return this.plateUpliftBridge != null ? this.plateUpliftBridge.getTectonicPlateUplift() : 0.0F;
    }

    protected Domain createWarp(int seedX, int seedZ, int tectonicScale, WorldSettings.Continent continent, boolean applyLacunarity) {
        int warpScale = NoiseUtil.round(tectonicScale * 0.225F);
        float strength = NoiseUtil.round(tectonicScale * 0.33F);
        float lacunarity = applyLacunarity ? continent.continentNoiseLacunarity : 0.0F;
        return Domains.domain(
                Noises.perlin2(seedX, warpScale, continent.continentNoiseOctaves, lacunarity, continent.continentNoiseGain),
                Noises.perlin2(seedZ, warpScale, continent.continentNoiseOctaves, lacunarity, continent.continentNoiseGain),
                Noises.constant(strength)
        );
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
        if (distance > this.controlPoints.shallowOcean && distance < this.controlPoints.inland) {
            float alpha = distance / this.controlPoints.inland;
            float cliff = this.cliffNoise.compute(x, y, 0);
            distance = NoiseUtil.lerp(distance * cliff, distance, alpha);
            if (distance < this.controlPoints.shallowOcean) {
                distance = this.controlPoints.shallowOcean * this.bayNoise.compute(x, y, 0);
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