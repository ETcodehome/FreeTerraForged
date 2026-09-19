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
    // --- continent bridging tuning (hard-coded starting point) ---
    /** Chance that a border between two eligible continents is left dry. ~0.12-0.25 gives a few merged continents; ~0.45 percolates into giants. */
    private static final float BRIDGE_CHANCE = 0.18F;
    /** Distance (cell units) at which the coastline ramp saturates; matches the upper bound of the map(...) in getDistanceValue. */
    private static final float BRIDGE_REACH = 0.25F;

    protected float frequency;
    protected float variance;
    protected int varianceSeed;
    protected Domain warp;
    protected Domain cleanWarp;
    protected Noise cliffNoise;
    protected Noise bayNoise;
    protected Levels levels;

    protected WorldSettings.Continent continentSettings;

    private final int bridgeSeed;

    private static final class BoundaryScratch {
        final float[] nx = new float[8], ny = new float[8], mx = new float[8], my = new float[8], d = new float[8];
        final float[] lo = new float[8], hi = new float[8];
        final int[] cx = new int[8], cy = new int[8], loP = new int[8], hiP = new int[8];
        final boolean[] eligible = new boolean[8], open = new boolean[8], real = new boolean[8];
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

        // Must stay the LAST seed.next() so existing noise seeds above are not shifted.
        this.bridgeSeed = seed.next();
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
    private boolean canBridge(int cx, int cy) {
        return !this.shouldSkip(cx, cy) && this.getContinentSizeModifier(cx, cy) >= 1.0F;
    }

    /** Symmetric per-border roll in [0,1]; identical when evaluated from either side of the border. */
    private float bridgeRoll(int ax, int ay, int bx, int by) {
        if (ax > bx || (ax == bx && ay > by)) {
            int t = ax; ax = bx; bx = t;
            t = ay; ay = by; by = t;
        }
        int s = this.bridgeSeed ^ (bx * 73856093) ^ (by * 19349663);
        return 0.5F + NoiseUtil.valCoord2D(s, ax, ay) * 0.5F;
    }

    private static float distToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float l2 = dx * dx + dy * dy;
        float t = l2 < 1.0e-12F ? 0.0F : NoiseUtil.clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0.0F, 1.0F);
        float ex = px - (ax + t * dx), ey = py - (ay + t * dy);
        return NoiseUtil.sqrt(ex * ex + ey * ey);
    }

    /**
     * Squared distance (cell units) from (x,y) to the nearest closed border of the merged landmass.
     * With no open borders nearby this is identical to the original min-over-neighbour-bisectors distance.
     */
    private float getBoundaryDistanceSq(float x, float y, int cellX, int cellY, float ax, float ay) {
        BoundaryScratch s = SCRATCH.get();
        boolean aEligible = this.canBridge(cellX, cellY);
        boolean anyOpenNear = false;
        float closedMin = Float.MAX_VALUE;
        int n = 0;

        for (int cy2 = cellY - 1; cy2 <= cellY + 1; ++cy2) {
            for (int cx2 = cellX - 1; cx2 <= cellX + 1; ++cx2) {
                if (cx2 == cellX && cy2 == cellY) {
                    continue;
                }
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx2, cy2);
                float px = cx2 + vec.x() * this.jitter;
                float py = cy2 + vec.y() * this.jitter;
                float ux = px - ax, uy = py - ay;
                float inv = 1.0F / NoiseUtil.sqrt(ux * ux + uy * uy);
                s.nx[n] = ux * inv;
                s.ny[n] = uy * inv;
                s.mx[n] = (ax + px) * 0.5F;
                s.my[n] = (ay + py) * 0.5F;
                // perpendicular distance to the bisector line (same value the old getDistance() produced, unsquared)
                s.d[n] = Math.max(0.0F, (s.mx[n] - x) * s.nx[n] + (s.my[n] - y) * s.ny[n]);
                s.cx[n] = cx2;
                s.cy[n] = cy2;
                s.eligible[n] = aEligible && this.canBridge(cx2, cy2);
                s.open[n] = s.eligible[n] && this.bridgeRoll(cellX, cellY, cx2, cy2) < BRIDGE_CHANCE;
                if (s.open[n]) {
                    if (s.d[n] < BRIDGE_REACH) {
                        anyOpenNear = true;
                    }
                } else if (s.d[n] < closedMin) {
                    closedMin = s.d[n];
                }
                n++;
            }
        }

        // fast path: no open border can influence this point
        if (!anyOpenNear) {
            return closedMin == Float.MAX_VALUE ? 4.0F : closedMin * closedMin;
        }

        // slow path: clip every bisector by the other 7 to get this cell's true polygon edges
        float best = Float.MAX_VALUE;
        for (int k = 0; k < 8; k++) {
            float tx = -s.ny[k], ty = s.nx[k];
            float lo = -1.0e9F, hi = 1.0e9F;
            int loP = -1, hiP = -1;
            boolean ok = true;
            for (int j = 0; j < 8 && ok; j++) {
                if (j == k) {
                    continue;
                }
                float a = tx * s.nx[j] + ty * s.ny[j];
                float b = (s.mx[j] - s.mx[k]) * s.nx[j] + (s.my[j] - s.my[k]) * s.ny[j];
                if (Math.abs(a) < 1.0e-6F) {
                    if (b < 0.0F) {
                        ok = false;
                    }
                    continue;
                }
                float t = b / a;
                if (a > 0.0F) {
                    if (t < hi) {
                        hi = t;
                        hiP = j;
                    }
                } else if (t > lo) {
                    lo = t;
                    loP = j;
                }
            }
            s.real[k] = ok && lo < hi && lo > -1.0e8F && hi < 1.0e8F;
            s.lo[k] = lo;
            s.hi[k] = hi;
            s.loP[k] = loP;
            s.hiP[k] = hiP;
            if (s.real[k] && !s.open[k]) {
                best = Math.min(best, distToSegment(x, y,
                        s.mx[k] + lo * tx, s.my[k] + lo * ty,
                        s.mx[k] + hi * tx, s.my[k] + hi * ty));
            }
        }

        // slit ends: a vertex shared by two OPEN edges whose far side (neighbour|neighbour) is closed
        for (int k = 0; k < 8; k++) {
            if (!s.real[k] || !s.open[k]) {
                continue;
            }
            float tx = -s.ny[k], ty = s.nx[k];
            for (int end = 0; end < 2; end++) {
                int l = end == 0 ? s.loP[k] : s.hiP[k];
                if (l < 0 || !s.real[l] || !s.open[l]) {
                    continue;
                }
                // third border also open -> fully merged corner, no boundary here
                if (s.eligible[k] && s.eligible[l]
                        && this.bridgeRoll(s.cx[k], s.cy[k], s.cx[l], s.cy[l]) < BRIDGE_CHANCE) {
                    continue;
                }
                float t = end == 0 ? s.lo[k] : s.hi[k];
                float vx = x - (s.mx[k] + t * tx), vy = y - (s.my[k] + t * ty);
                best = Math.min(best, NoiseUtil.sqrt(vx * vx + vy * vy));
            }
        }
        return best == Float.MAX_VALUE ? 4.0F : best * best;
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
        if (cell.terrain != null && (cell.terrain.isShallowOcean() || cell.terrain.isDeepOcean())) {
            return 0.0F;
        }

        float x = rawX * this.frequency;
        float y = rawY * this.frequency;

        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);

        int cellX = xi;
        int cellY = yi;
        float cellPointX = x;
        float cellPointY = y;
        float nearestSq = Float.MAX_VALUE;

        // Find the closest Voronoi cell seed
        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx = xi - 1; cx <= xi + 1; ++cx) {
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                float px = cx + vec.x() * this.jitter;
                float py = cy + vec.y() * this.jitter;
                float dist2 = Line.distSq(x, y, px, py);

                if (dist2 < nearestSq) {
                    nearestSq = dist2;
                    cellPointX = px;
                    cellPointY = py;
                    cellX = cx;
                    cellY = cy;
                }
            }
        }

        // Collect the 8 immediate neighboring seeds
        float[] neighborX = new float[8];
        float[] neighborY = new float[8];
        int nIndex = 0;
        for (int cy2 = cellY - 1; cy2 <= cellY + 1; ++cy2) {
            for (int cx2 = cellX - 1; cx2 <= cellX + 1; ++cx2) {
                if (cx2 != cellX || cy2 != cellY) {
                    NoiseUtil.Vec2f vec2 = NoiseUtil.cell(this.seed, cx2, cy2);
                    neighborX[nIndex] = cx2 + vec2.x() * this.jitter;
                    neighborY[nIndex] = cy2 + vec2.y() * this.jitter;
                    nIndex++;
                }
            }
        }

        float s0Sq = cellPointX * cellPointX + cellPointY * cellPointY;

        float minGradient = 1.0F;
        for (int i = 0; i < 8; i++) {
            float px2 = neighborX[i];
            float py2 = neighborY[i];
            float dx = px2 - cellPointX;
            float dy = py2 - cellPointY;
            float lenSq = dx * dx + dy * dy;

            if (lenSq > 0.00001F) {
                float siSq = px2 * px2 + py2 * py2;
                float baseHalfDiff = 0.5F * (siSq - s0Sq);
                float h_x = baseHalfDiff - (x * dx + y * dy);

                // Using the true centroid ensures the intersecting planes
                // always evaluate to exactly 0.0 at the Voronoi border edge.
                float h_c = baseHalfDiff - (cellPointX * dx + cellPointY * dy);

                if (h_c > 0.00001F) {
                    float planeValue = h_x / h_c;
                    if (planeValue < minGradient) minGradient = planeValue;
                }
            }
        }
        return NoiseUtil.clamp(minGradient, 0.0F, 1.0F);
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