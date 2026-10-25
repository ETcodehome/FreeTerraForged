package etcodehome.freeterraforged.world.worldgen.cell.continent.uplift;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.module.Line;

/**
 * Handles tectonic plate uplift bridging between adjacent bridged continents.
 * <p>
 * When continents are bridged into a single landmass, their uplift peaks would
 * otherwise drop down to 0.0 at the Voronoi cell boundary, creating a deep trough
 * between the two mountain masses. This class creates an elevated bridge / saddle
 * between the two peaks across the bridged Voronoi edge, while ensuring that all
 * sectors facing unbridged borders (where rivers generate and flow to the sea)
 * remain unmodified and slope cleanly to the coast.
 */
public class PlateUpliftBridge {
    public static final float DEFAULT_SADDLE_HEIGHT = 0.75F;
    public static final float DEFAULT_TECTONIC_ROUNDING = 0.50F;
    public static final float MIN_SECTOR_ANGLE_DEGREES = 20.0F;
    public static final float MIN_SECTOR_ANGLE_TAN = (float) Math.tan(Math.toRadians(MIN_SECTOR_ANGLE_DEGREES));

    /**
     * Returns true if the sector of the peak pyramid subtended by the Voronoi edge [lo, hi]
     * from the peak (at Delaunay distance len to neighbor) has an angular width less than 20 degrees.
     */
    public static boolean isSectorTooThin(float lo, float hi, float len) {
        float halfL = 0.5F * len;
        float dot = halfL * halfL + lo * hi;
        float cross = halfL * (hi - lo);
        return dot > 0.0F && cross < dot * MIN_SECTOR_ANGLE_TAN;
    }

    protected final UpliftContinentGenerator generator;
    protected final int seed;
    protected final float jitter;
    protected final float frequency;
    protected final int tectonicSeed;
    protected float tectonicPlateUplift;
    protected float tectonicRounding;
    protected float saddleHeight;

    private static final class BridgeScratch {
        final float[] neighborX = new float[8];
        final float[] neighborY = new float[8];
        final int[] cx = new int[8];
        final int[] cy = new int[8];
        final float[] nx = new float[8];
        final float[] ny = new float[8];
        final float[] mx = new float[8];
        final float[] my = new float[8];
        final float[] len = new float[8];
        final float[] planeValue = new float[8];
    }

    private static final ThreadLocal<BridgeScratch> SCRATCH = ThreadLocal.withInitial(BridgeScratch::new);

    public PlateUpliftBridge(UpliftContinentGenerator generator, int seed, float jitter, int tectonicSeed, float tectonicPlateUplift) {
        this(generator, seed, jitter, tectonicSeed, tectonicPlateUplift, DEFAULT_TECTONIC_ROUNDING, DEFAULT_SADDLE_HEIGHT);
    }

    public PlateUpliftBridge(UpliftContinentGenerator generator, int seed, float jitter, int tectonicSeed, float tectonicPlateUplift, float tectonicRounding) {
        this(generator, seed, jitter, tectonicSeed, tectonicPlateUplift, tectonicRounding, DEFAULT_SADDLE_HEIGHT);
    }

    public PlateUpliftBridge(UpliftContinentGenerator generator, int seed, float jitter, int tectonicSeed, float tectonicPlateUplift, float tectonicRounding, float saddleHeight) {
        this.generator = generator;
        this.seed = seed;
        this.jitter = jitter;
        this.frequency = generator.frequency;
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
                && this.generator.isBridged(ax, ay, bx, by)
                && this.tectonicRoll(ax, ay, bx, by) < this.tectonicPlateUplift;
    }

    /**
     * Computes the uplift gradient for cell at the given unwarped coordinates (rawX, rawY).
     * Connects peaks across bridged borders where tectonic plate uplift is active, while
     * strictly preserving the original slope to 0.0 along borders where rivers flow to ocean.
     */
    public float computeSmoothVoronoiGradient(Cell cell, float rawX, float rawY) {
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

        BridgeScratch s = SCRATCH.get();

        // Collect the 8 immediate neighboring seeds
        int nIndex = 0;
        for (int cy2 = cellY - 1; cy2 <= cellY + 1; ++cy2) {
            for (int cx2 = cellX - 1; cx2 <= cellX + 1; ++cx2) {
                if (cx2 != cellX || cy2 != cellY) {
                    NoiseUtil.Vec2f vec2 = NoiseUtil.cell(this.seed, cx2, cy2);
                    s.cx[nIndex] = cx2;
                    s.cy[nIndex] = cy2;
                    s.neighborX[nIndex] = cx2 + vec2.x() * this.jitter;
                    s.neighborY[nIndex] = cy2 + vec2.y() * this.jitter;
                    nIndex++;
                }
            }
        }

        float s0Sq = cellPointX * cellPointX + cellPointY * cellPointY;
        int minIndex = -1;
        float minGradient = Float.MAX_VALUE;

        for (int i = 0; i < 8; i++) {
            float px2 = s.neighborX[i];
            float py2 = s.neighborY[i];
            float dx = px2 - cellPointX;
            float dy = py2 - cellPointY;
            float lenSq = dx * dx + dy * dy;

            if (lenSq > 0.00001F) {
                float len = NoiseUtil.sqrt(lenSq);
                float inv = 1.0F / len;
                s.len[i] = len;
                s.nx[i] = dx * inv;
                s.ny[i] = dy * inv;
                s.mx[i] = (cellPointX + px2) * 0.5F;
                s.my[i] = (cellPointY + py2) * 0.5F;

                float siSq = px2 * px2 + py2 * py2;
                float baseHalfDiff = 0.5F * (siSq - s0Sq);
                float h_x = baseHalfDiff - (x * dx + y * dy);
                float h_c = baseHalfDiff - (cellPointX * dx + cellPointY * dy);

                if (h_c > 0.00001F) {
                    float planeValue = h_x / h_c;
                    s.planeValue[i] = planeValue;
                    if (planeValue < minGradient) {
                        minGradient = planeValue;
                        minIndex = i;
                    }
                } else {
                    s.planeValue[i] = 1.0F;
                }
            } else {
                s.planeValue[i] = 1.0F;
            }
        }

        if (minIndex < 0) {
            return 1.0F;
        }

        float clampedMin = NoiseUtil.clamp(minGradient, 0.0F, 1.0F);

        // Fast path: if tectonic plate uplift is inactive globally or on this specific border
        if (this.tectonicPlateUplift <= 0.0F) {
            return clampedMin;
        }

        boolean isActive = this.isTectonicUpliftActive(cellX, cellY, s.cx[minIndex], s.cy[minIndex]);
        if (!isActive) {
            return clampedMin;
        }

        // Progress r along the sector towards the bridged edge: 0.0 at cell center peak, 1.0 at Voronoi edge
        float r = 1.0F - clampedMin;
        if (r <= 1.0e-5F) {
            return 1.0F;
        }

        // Clip bisector minIndex symmetrically using the union bounding box of cell and neighbor
        int ncx = s.cx[minIndex];
        int ncy = s.cy[minIndex];
        int minCx = Math.min(cellX, ncx) - 1;
        int maxCx = Math.max(cellX, ncx) + 1;
        int minCy = Math.min(cellY, ncy) - 1;
        int maxCy = Math.max(cellY, ncy) + 1;
        float mxK = s.mx[minIndex];
        float myK = s.my[minIndex];

        float tx = -s.ny[minIndex];
        float ty = s.nx[minIndex];
        float lo = -1.0e9F;
        float hi = 1.0e9F;
        boolean ok = true;

        for (int cy = minCy; cy <= maxCy && ok; ++cy) {
            for (int cx = minCx; cx <= maxCx && ok; ++cx) {
                if ((cx == cellX && cy == cellY) || (cx == ncx && cy == ncy)) {
                    continue;
                }
                NoiseUtil.Vec2f vecC = NoiseUtil.cell(this.seed, cx, cy);
                float pxC = cx + vecC.x() * this.jitter;
                float pyC = cy + vecC.y() * this.jitter;

                float dACx = pxC - cellPointX;
                float dACy = pyC - cellPointY;
                float lenACSq = dACx * dACx + dACy * dACy;
                if (lenACSq < 1.0e-5F) {
                    continue;
                }

                float invAC = 1.0F / NoiseUtil.sqrt(lenACSq);
                float nACx = dACx * invAC;
                float nACy = dACy * invAC;
                float mACx = (cellPointX + pxC) * 0.5F;
                float mACy = (cellPointY + pyC) * 0.5F;

                float a = tx * nACx + ty * nACy;
                float b = (mACx - mxK) * nACx + (mACy - myK) * nACy;
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
                    }
                } else if (t > lo) {
                    lo = t;
                }
            }
        }

        // Hard cutoff threshold: sectors of the peak pyramid less than 20 degrees wide never bridge
        if (!ok || lo >= hi || lo <= -1.0e8F || hi >= 1.0e8F || isSectorTooThin(lo, hi, s.len[minIndex])) {
            return clampedMin;
        }

        // Tangent parameter t_edge along the bisector where the ray from peak (cellPointX, cellPointY) through (x, y) intersects the edge
        float d_tan = (x - cellPointX) * tx + (y - cellPointY) * ty;
        float t_edge = d_tan / r;

        // Measure distance from the true center of the Voronoi edge relative to half-width
        float t_mid = 0.5F * (lo + hi);
        float halfLen = 0.5F * (hi - lo);
        if (halfLen <= 1.0e-6F) {
            return clampedMin;
        }

        float uEdge = (t_edge - t_mid) / halfLen;
        if (uEdge <= -1.0F || uEdge >= 1.0F) {
            return clampedMin;
        }

        float fraction;
        if (this.tectonicRounding <= 0.0F || Math.abs(t_mid) < 1.0e-5F) {
            fraction = Math.abs(uEdge);
        } else {
            // Circumcircle through Peak 1 (-halfL, 0), Chord intersection (0, t_mid), and Peak 2 (+halfL, 0)
            float halfL = 0.5F * s.len[minIndex];
            float h2 = halfL * halfL;
            float cy = 0.5F * (t_mid - h2 / t_mid);
            float M = Math.abs(cy);
            float rFactor = r * (2.0F - r);
            float radicand = M * M + rFactor * h2;
            float denom = NoiseUtil.sqrt(radicand) + M;
            float sign = t_mid >= 0.0F ? 1.0F : -1.0F;
            float tArcDivR = sign * ((2.0F - r) * h2 / denom);

            // Interpolate path between straight chord (0.0 rounding) and circular arc (1.0 rounding)
            float uCrest = this.tectonicRounding * ((tArcDivR - t_mid) / halfLen);
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
}
