package etcodehome.freeterraforged.world.worldgen.cell.continent.uplift;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles tectonic plate uplift bridging between adjacent bridged continents,
 * centering uplift peaks and river spokes on true Voronoi centroids.
 * <p>
 * Continent peaks are placed at the true geometric centroid of each Voronoi polygon,
 * ensuring all facets of the uplift pyramid slope evenly from the central peak down to
 * the Voronoi boundary edges. When continents are bridged into a single landmass, an elevated
 * bridge / saddle connects the centroid peaks across the bridged Voronoi edge.
 */
public class PlateUpliftBridge {
    public static final float DEFAULT_SADDLE_HEIGHT = 0.75F;
    public static final float DEFAULT_TECTONIC_ROUNDING = 0.50F;
    public static final float MIN_SECTOR_ANGLE_DEGREES = 20.0F;
    public static final float MIN_SECTOR_ANGLE_TAN = (float) Math.tan(Math.toRadians(MIN_SECTOR_ANGLE_DEGREES));

    /**
     * Returns true if the sector of the peak pyramid subtended by the Voronoi edge [lo, hi]
     * from the centroid peak has an angular width less than 20 degrees.
     */
    public static boolean isSectorTooThin(float lo, float hi, float dPerp, float mTan) {
        float t1 = lo - mTan;
        float t2 = hi - mTan;
        float dot = dPerp * dPerp + t1 * t2;
        float cross = dPerp * (hi - lo);
        return dot > 0.0F && cross < dot * MIN_SECTOR_ANGLE_TAN;
    }

    public static boolean isSectorTooThin(float lo, float hi, float len) {
        return isSectorTooThin(lo, hi, 0.5F * len, 0.0F);
    }

    public static final class CellVoronoi {
        public int instanceId = -1;
        public int cellX = Integer.MIN_VALUE;
        public int cellY = Integer.MIN_VALUE;
        public float seedX;
        public float seedY;
        public float centroidX;
        public float centroidY;
        public int edgeCount;

        public final int[] ncx = new int[24];
        public final int[] ncy = new int[24];
        public final float[] neighborX = new float[24];
        public final float[] neighborY = new float[24];
        public final float[] dx = new float[24];
        public final float[] dy = new float[24];
        public final float[] mx = new float[24];
        public final float[] my = new float[24];
        public final float[] nx = new float[24];
        public final float[] ny = new float[24];
        public final float[] tx = new float[24];
        public final float[] ty = new float[24];
        public final float[] len = new float[24];
        public final float[] lo = new float[24];
        public final float[] hi = new float[24];
        public final float[] tMid = new float[24];
        public final float[] halfLen = new float[24];
        public final float[] hc = new float[24];
        public final float[] mTan = new float[24];
        public final boolean[] isBridged = new boolean[24];
        public final boolean[] isTectonicActive = new boolean[24];
    }

    private static final class BridgeScratch {
        final CellVoronoi[] cache = new CellVoronoi[64];

        final int[] candCx = new int[64];
        final int[] candCy = new int[64];
        final float[] candX = new float[64];
        final float[] candY = new float[64];

        final float[] edgeLo = new float[24];
        final float[] edgeHi = new float[24];
        final float[] edgeLen = new float[24];
        final float[] edgeNx = new float[24];
        final float[] edgeNy = new float[24];
        final float[] edgeTx = new float[24];
        final float[] edgeTy = new float[24];
        final float[] edgeMx = new float[24];
        final float[] edgeMy = new float[24];
        final int[] edgeCandIdx = new int[24];

        BridgeScratch() {
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new CellVoronoi();
            }
        }
    }

    private static final ThreadLocal<BridgeScratch> SCRATCH = ThreadLocal.withInitial(BridgeScratch::new);

    public interface BridgeProvider {
        boolean canBridge(int cx, int cy);
        boolean isBridged(int ax, int ay, int bx, int by);
        float getBridgeChance();
    }

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    private final int instanceId = INSTANCE_COUNTER.incrementAndGet();

    protected final BridgeProvider generator;
    protected final int seed;
    protected final float jitter;
    protected final float frequency;
    protected final int tectonicSeed;
    protected volatile float tectonicPlateUplift;
    protected volatile float tectonicRounding;
    protected volatile float saddleHeight;

    public PlateUpliftBridge(BridgeProvider generator, int seed, float jitter, int tectonicSeed, float tectonicPlateUplift) {
        this(generator, seed, jitter, tectonicSeed, tectonicPlateUplift, DEFAULT_TECTONIC_ROUNDING, DEFAULT_SADDLE_HEIGHT);
    }

    public PlateUpliftBridge(BridgeProvider generator, int seed, float jitter, int tectonicSeed, float tectonicPlateUplift, float tectonicRounding) {
        this(generator, seed, jitter, tectonicSeed, tectonicPlateUplift, tectonicRounding, DEFAULT_SADDLE_HEIGHT);
    }

    public PlateUpliftBridge(BridgeProvider generator, int seed, float jitter, int tectonicSeed, float tectonicPlateUplift, float tectonicRounding, float saddleHeight) {
        this(generator, seed, jitter, generator instanceof UpliftContinentGenerator ? ((UpliftContinentGenerator) generator).frequency : 1.0F / 4000.0F, tectonicSeed, tectonicPlateUplift, tectonicRounding, saddleHeight);
    }

    public PlateUpliftBridge(BridgeProvider generator, int seed, float jitter, float frequency, int tectonicSeed, float tectonicPlateUplift, float tectonicRounding, float saddleHeight) {
        this.generator = generator;
        this.seed = seed;
        this.jitter = jitter;
        this.frequency = frequency;
        this.tectonicSeed = tectonicSeed;
        this.tectonicPlateUplift = tectonicPlateUplift;
        this.tectonicRounding = tectonicRounding;
        this.saddleHeight = saddleHeight;
    }

    /**
     * Symmetric per-border roll in [0,1]; identical when evaluated from either side of the border.
     */
    public float tectonicRoll(int ax, int ay, int bx, int by) {
        if (ax > bx || (ax == bx && ay > by)) {
            int t = ax; ax = bx; bx = t;
            t = ay; ay = by; by = t;
        }
        int s = this.tectonicSeed ^ (bx * 73856093) ^ (by * 19349663);
        return 0.5F + NoiseUtil.valCoord2D(s, ax, ay) * 0.5F;
    }

    /**
     * True if the border between cell (ax, ay) and cell (bx, by) is bridged and has tectonic plate uplift active.
     */
    public boolean isTectonicUpliftActive(int ax, int ay, int bx, int by) {
        return this.tectonicPlateUplift > 0.0F
                && this.generator != null
                && this.generator.isBridged(ax, ay, bx, by)
                && this.tectonicRoll(ax, ay, bx, by) < this.tectonicPlateUplift;
    }

    /**
     * Retrieves the cached Voronoi polygon and centroid geometry for the given grid cell.
     */
    public CellVoronoi getVoronoi(int cellX, int cellY) {
        BridgeScratch s = SCRATCH.get();
        int hash = ((cellX & 7) << 3) | (cellY & 7);
        CellVoronoi v = s.cache[hash];
        if (v.instanceId == this.instanceId && v.cellX == cellX && v.cellY == cellY) {
            return v;
        }

        this.populateVoronoi(cellX, cellY, v, s);
        v.instanceId = this.instanceId;
        return v;
    }

    /**
     * Populates the Voronoi polygon, true geometric centroid, and face geometry for cell (cellX, cellY).
     */
    public void populateVoronoi(int cellX, int cellY, CellVoronoi v, BridgeScratch s) {
        v.cellX = cellX;
        v.cellY = cellY;

        NoiseUtil.Vec2f vec0 = NoiseUtil.cell(this.seed, cellX, cellY);
        v.seedX = cellX + vec0.x() * this.jitter;
        v.seedY = cellY + vec0.y() * this.jitter;

        int candCount = 0;
        for (int dy = -3; dy <= 3; dy++) {
            int cy = cellY + dy;
            for (int dx = -3; dx <= 3; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int cx = cellX + dx;
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                s.candCx[candCount] = cx;
                s.candCy[candCount] = cy;
                s.candX[candCount] = cx + vec.x() * this.jitter;
                s.candY[candCount] = cy + vec.y() * this.jitter;
                candCount++;
            }
        }

        int e = 0;
        for (int i = 0; i < candCount; i++) {
            float px = s.candX[i];
            float py = s.candY[i];
            float diffX = px - v.seedX;
            float diffY = py - v.seedY;
            float lenSq = diffX * diffX + diffY * diffY;
            if (lenSq < 1.0e-5F) {
                continue;
            }

            float len = NoiseUtil.sqrt(lenSq);
            float inv = 1.0F / len;
            float nx = diffX * inv;
            float ny = diffY * inv;
            float mx = (v.seedX + px) * 0.5F;
            float my = (v.seedY + py) * 0.5F;
            float tx = -ny;
            float ty = nx;

            float lo = -1.0e9F;
            float hi = 1.0e9F;
            boolean ok = true;

            for (int j = 0; j < candCount; j++) {
                if (j == i) {
                    continue;
                }
                float pxJ = s.candX[j];
                float pyJ = s.candY[j];
                float diffJx = pxJ - v.seedX;
                float diffJy = pyJ - v.seedY;
                float lenJSq = diffJx * diffJx + diffJy * diffJy;
                if (lenJSq < 1.0e-5F) {
                    continue;
                }

                float invJ = 1.0F / NoiseUtil.sqrt(lenJSq);
                float nxJ = diffJx * invJ;
                float nyJ = diffJy * invJ;
                float mxJ = (v.seedX + pxJ) * 0.5F;
                float myJ = (v.seedY + pyJ) * 0.5F;

                float a = tx * nxJ + ty * nyJ;
                float b = (mxJ - mx) * nxJ + (myJ - my) * nyJ;
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

            if (ok && lo < hi && e < s.edgeLo.length) {
                s.edgeLo[e] = lo;
                s.edgeHi[e] = hi;
                s.edgeLen[e] = len;
                s.edgeNx[e] = nx;
                s.edgeNy[e] = ny;
                s.edgeTx[e] = tx;
                s.edgeTy[e] = ty;
                s.edgeMx[e] = mx;
                s.edgeMy[e] = my;
                s.edgeCandIdx[e] = i;
                e++;
            }
        }

        // Exact polygon centroid via star decomposition into triangles from seed point v.seed
        float totalWeight = 0.0F;
        float numX = 0.0F;
        float numY = 0.0F;
        for (int k = 0; k < e; k++) {
            float l = s.edgeLen[k];
            float span = s.edgeHi[k] - s.edgeLo[k];
            float w = l * span;
            totalWeight += w;
            float tMid = (s.edgeLo[k] + s.edgeHi[k]) * 0.5F;
            float dcX = (l * s.edgeNx[k] + 2.0F * tMid * s.edgeTx[k]) * (1.0F / 3.0F);
            float dcY = (l * s.edgeNy[k] + 2.0F * tMid * s.edgeTy[k]) * (1.0F / 3.0F);
            numX += w * dcX;
            numY += w * dcY;
        }

        float invW = totalWeight > 1.0e-6F ? (1.0F / totalWeight) : 0.0F;
        v.centroidX = totalWeight > 1.0e-6F ? (v.seedX + numX * invW) : v.seedX;
        v.centroidY = totalWeight > 1.0e-6F ? (v.seedY + numY * invW) : v.seedY;
        v.edgeCount = e;

        boolean cellCanBridge = this.generator != null && this.generator.canBridge(cellX, cellY);

        for (int k = 0; k < e; k++) {
            int cIdx = s.edgeCandIdx[k];
            int ncx = s.candCx[cIdx];
            int ncy = s.candCy[cIdx];
            v.ncx[k] = ncx;
            v.ncy[k] = ncy;
            v.neighborX[k] = s.candX[cIdx];
            v.neighborY[k] = s.candY[cIdx];
            v.dx[k] = s.candX[cIdx] - v.seedX;
            v.dy[k] = s.candY[cIdx] - v.seedY;
            v.mx[k] = s.edgeMx[k];
            v.my[k] = s.edgeMy[k];
            v.nx[k] = s.edgeNx[k];
            v.ny[k] = s.edgeNy[k];
            v.tx[k] = s.edgeTx[k];
            v.ty[k] = s.edgeTy[k];
            v.len[k] = s.edgeLen[k];
            v.lo[k] = s.edgeLo[k];
            v.hi[k] = s.edgeHi[k];
            v.tMid[k] = (s.edgeLo[k] + s.edgeHi[k]) * 0.5F;
            v.halfLen[k] = (s.edgeHi[k] - s.edgeLo[k]) * 0.5F;

            // Height normalization factor: distance from centroid peak to bisector edge along edge normal
            float hcVal = (v.mx[k] - v.centroidX) * v.dx[k] + (v.my[k] - v.centroidY) * v.dy[k];
            v.hc[k] = Math.max(hcVal, 1.0e-5F);
            v.mTan[k] = (v.mx[k] - v.centroidX) * v.tx[k] + (v.my[k] - v.centroidY) * v.ty[k];

            // Symmetric sector width check based on Delaunay span (strictly identical between Cell A and Cell B)
            boolean notTooThin = !isSectorTooThin(v.lo[k], v.hi[k], v.len[k]);

            v.isBridged[k] = cellCanBridge
                    && this.generator.isBridged(cellX, cellY, ncx, ncy)
                    && notTooThin;

            v.isTectonicActive[k] = v.isBridged[k]
                    && this.tectonicPlateUplift > 0.0F
                    && this.tectonicRoll(cellX, cellY, ncx, ncy) < this.tectonicPlateUplift;
        }
    }

    /**
     * Computes the uplift gradient for cell at the given unwarped coordinates (rawX, rawY).
     * The peak of the uplift pyramid is centered on the true Voronoi centroid of the continent.
     * Connects peaks across bridged borders where tectonic plate uplift is active, while
     * strictly preserving the original slope to 0.0 along borders where rivers flow to ocean.
     */
    public float computeSmoothVoronoiGradient(Cell cell, float rawX, float rawY) {
        float x = rawX * this.frequency;
        float y = rawY * this.frequency;

        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);

        int cellX = xi;
        int cellY = yi;
        float nearestSq = Float.MAX_VALUE;

        // Find the closest Voronoi cell seed to determine cell ownership
        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx = xi - 1; cx <= xi + 1; ++cx) {
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                float px = cx + vec.x() * this.jitter;
                float py = cy + vec.y() * this.jitter;
                float dX = px - x;
                float dY = py - y;
                float dist2 = dX * dX + dY * dY;

                if (dist2 < nearestSq) {
                    nearestSq = dist2;
                    cellX = cx;
                    cellY = cy;
                }
            }
        }

        CellVoronoi v = this.getVoronoi(cellX, cellY);

        int minIndex = -1;
        float minGradient = Float.MAX_VALUE;

        // Evaluate all valid Voronoi faces of the pyramid centered on the centroid peak
        for (int i = 0; i < v.edgeCount; i++) {
            float h_x = (v.mx[i] - x) * v.dx[i] + (v.my[i] - y) * v.dy[i];
            float planeValue = h_x / v.hc[i];
            if (planeValue < minGradient) {
                minGradient = planeValue;
                minIndex = i;
            }
        }

        if (minIndex < 0) {
            return 1.0F;
        }

        float clampedMin = NoiseUtil.clamp(minGradient, 0.0F, 1.0F);

        // Fast path: if tectonic plate uplift is inactive globally or on this specific border
        if (!v.isTectonicActive[minIndex]) {
            return clampedMin;
        }

        // Progress r along the sector towards the bridged edge: 0.0 at centroid peak, 1.0 at Voronoi edge
        float r = 1.0F - clampedMin;
        if (r <= 0.01F) {
            return clampedMin;
        }

        // Ray from centroid through (x, y) intersecting bisector line
        float d_tan = (x - v.centroidX) * v.tx[minIndex] + (y - v.centroidY) * v.ty[minIndex];
        float t_edge = d_tan / r - v.mTan[minIndex];

        float halfLen = v.halfLen[minIndex];
        if (halfLen <= 1.0e-6F) {
            return clampedMin;
        }

        float uEdge = (t_edge - v.tMid[minIndex]) / halfLen;
        if (uEdge <= -1.0F || uEdge >= 1.0F) {
            return clampedMin;
        }

        float fraction;
        if (this.tectonicRounding <= 0.0F || Math.abs(v.tMid[minIndex]) < 1.0e-4F) {
            fraction = Math.abs(uEdge);
        } else {
            // Use symmetric half-distance between seeds so arc geometry is identical from both sides of the border
            float halfDelaunay = 0.5F * v.len[minIndex];
            float h2 = halfDelaunay * halfDelaunay;
            float tMid = v.tMid[minIndex];
            float cy = 0.5F * (tMid - h2 / tMid);
            float M = Math.abs(cy);
            float rFactor = r * (2.0F - r);
            float radicand = M * M + rFactor * h2;
            float denom = NoiseUtil.sqrt(radicand) + M;
            float sign = tMid >= 0.0F ? 1.0F : -1.0F;
            float tArcDivR = sign * ((2.0F - r) * h2 / denom);

            // Interpolate path between straight chord (0.0 rounding) and circular arc (1.0 rounding)
            float uCrest = this.tectonicRounding * ((tArcDivR - tMid) / halfLen);
            uCrest = NoiseUtil.clamp(uCrest, -0.80F, 0.80F);

            // Lateral distance from the curved ridge centerline to (x, y)
            if (uEdge < uCrest) {
                fraction = (uCrest - uEdge) / (uCrest + 1.0F);
            } else {
                fraction = (uEdge - uCrest) / (1.0F - uCrest);
            }
        }

        if (fraction >= 1.0F) {
            return clampedMin;
        }

        // Smoothstep lateral fade: 1.0 along the centerline bridge, fading to 0.0 at the sector boundary vertices
        float f = fraction;
        float lateralFade = 1.0F - (3.0F * f * f - 2.0F * f * f * f);
        float edgeHeight = this.saddleHeight * lateralFade;

        // Smooth crest profile along r ensuring d(bridged)/dr = 0 at the saddle center crest (r = 1.0)
        float sh = NoiseUtil.clamp(this.saddleHeight, 0.01F, 1.0F);
        float kSlope = (1.0F - sh) / sh;
        float gR = r * (1.0F + kSlope * (r - 1.0F));
        float bridged = clampedMin + gR * edgeHeight;

        return NoiseUtil.clamp(bridged, 0.0F, 1.0F);
    }

    public float getTectonicPlateUplift() {
        return this.tectonicPlateUplift;
    }

    public void setTectonicPlateUplift(float tectonicPlateUplift) {
        this.tectonicPlateUplift = tectonicPlateUplift;
    }

    public float getTectonicRounding() {
        return this.tectonicRounding;
    }

    public void setTectonicRounding(float tectonicRounding) {
        this.tectonicRounding = tectonicRounding;
    }

    public float getSaddleHeight() {
        return this.saddleHeight;
    }

    public void setSaddleHeight(float saddleHeight) {
        this.saddleHeight = saddleHeight;
    }

    /**
     * Returns true if a ray from the continent centroid peak at unwarped coordinates (cx, cz)
     * in direction (dx, dz) points into a bridged sector connecting to an adjacent bridged continent.
     */
    public boolean isInBridgedSector(int cx, int cz, float dx, float dz) {
        if (this.generator == null || this.generator.getBridgeChance() <= 0.0F) {
            return false;
        }

        float x = cx * this.frequency;
        float y = cz * this.frequency;

        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);

        int cellX = xi;
        int cellY = yi;
        float nearestSq = Float.MAX_VALUE;

        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx2 = xi - 1; cx2 <= xi + 1; ++cx2) {
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx2, cy);
                float px = cx2 + vec.x() * this.jitter;
                float py = cy + vec.y() * this.jitter;
                float dX = px - x;
                float dY = py - y;
                float dist2 = dX * dX + dY * dY;

                if (dist2 < nearestSq) {
                    nearestSq = dist2;
                    cellX = cx2;
                    cellY = cy;
                }
            }
        }

        if (!this.generator.canBridge(cellX, cellY)) {
            return false;
        }

        CellVoronoi v = this.getVoronoi(cellX, cellY);

        int minIndex = -1;
        float minS = Float.MAX_VALUE;

        for (int i = 0; i < v.edgeCount; i++) {
            float dotN = dx * v.nx[i] + dz * v.ny[i];
            if (dotN > 0.0001F) {
                float distToEdge = v.hc[i] / v.len[i];
                float sDist = distToEdge / dotN;
                if (sDist < minS) {
                    minS = sDist;
                    minIndex = i;
                }
            }
        }

        if (minIndex < 0 || !v.isBridged[minIndex]) {
            return false;
        }

        float dotT = dx * v.tx[minIndex] + dz * v.ty[minIndex];
        float tEdge = minS * dotT - v.mTan[minIndex];

        float halfLen = v.halfLen[minIndex];
        if (halfLen <= 1.0e-6F) {
            return false;
        }

        float uEdge = (tEdge - v.tMid[minIndex]) / halfLen;
        return uEdge >= -1.0F && uEdge <= 1.0F;
    }

    /**
     * Returns true if the unwarped coordinates (rawX, rawY) lie inside a bridged sector connecting
     * two adjacent bridged continents.
     */
    public boolean isInBridgedSector(float rawX, float rawY) {
        if (this.generator == null || this.generator.getBridgeChance() <= 0.0F) {
            return false;
        }

        float x = rawX * this.frequency;
        float y = rawY * this.frequency;

        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);

        int cellX = xi;
        int cellY = yi;
        float nearestSq = Float.MAX_VALUE;

        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx = xi - 1; cx <= xi + 1; ++cx) {
                NoiseUtil.Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                float px = cx + vec.x() * this.jitter;
                float py = cy + vec.y() * this.jitter;
                float dX = px - x;
                float dY = py - y;
                float dist2 = dX * dX + dY * dY;

                if (dist2 < nearestSq) {
                    nearestSq = dist2;
                    cellX = cx;
                    cellY = cy;
                }
            }
        }

        if (!this.generator.canBridge(cellX, cellY)) {
            return false;
        }

        CellVoronoi v = this.getVoronoi(cellX, cellY);

        int minIndex = -1;
        float minGradient = Float.MAX_VALUE;

        for (int i = 0; i < v.edgeCount; i++) {
            float h_x = (v.mx[i] - x) * v.dx[i] + (v.my[i] - y) * v.dy[i];
            float planeValue = h_x / v.hc[i];
            if (planeValue < minGradient) {
                minGradient = planeValue;
                minIndex = i;
            }
        }

        if (minIndex < 0 || !v.isBridged[minIndex]) {
            return false;
        }

        float clampedMin = NoiseUtil.clamp(minGradient, 0.0F, 1.0F);
        float r = 1.0F - clampedMin;
        if (r <= 0.0001F) {
            return false;
        }

        float d_tan = (x - v.centroidX) * v.tx[minIndex] + (y - v.centroidY) * v.ty[minIndex];
        float t_edge = d_tan / r - v.mTan[minIndex];

        float halfLen = v.halfLen[minIndex];
        if (halfLen <= 1.0e-6F) {
            return false;
        }

        float uEdge = (t_edge - v.tMid[minIndex]) / halfLen;
        return uEdge >= -1.0F && uEdge <= 1.0F;
    }
}
