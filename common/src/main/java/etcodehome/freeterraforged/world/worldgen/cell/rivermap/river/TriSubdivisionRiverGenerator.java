package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.continent.SimpleContinent;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.PlateUpliftBridge;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.module.Simplex2;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

/**
 * Generates dendritic river networks by subdividing the triangular faces of a Voronoi centroid
 * into a multi-tiered drainage tree heading down the gradient away from the centroid peak towards
 * the ocean base.
 * <p>
 * Nodes are placed at the exact centers of flat uplift plateau areas in the ContinentalHydrology
 * step function to align naturally with terraced water profiles.
 */
public class TriSubdivisionRiverGenerator extends BaseRiverGenerator<SimpleContinent> {

    /**
     * Represents a single node on the triangle drainage graph.
     */
    public static class DrainageNode {
        public final float x;
        public final float z;
        public final float gradient;
        public final int row;
        public final int col;
        public int feederCount;
        public boolean isPopulated;
        public boolean isCulled;
        public boolean isMountain;
        public boolean isLake;

        public DrainageNode(float x, float z, float gradient, int row, int col) {
            this.x = x;
            this.z = z;
            this.gradient = gradient;
            this.row = row;
            this.col = col;
            this.feederCount = 0;
            this.isPopulated = false;
            this.isCulled = false;
            this.isMountain = false;
            this.isLake = false;
        }
    }

    /**
     * Represents a connection (river segment) between two drainage nodes.
     */
    public static class RiverConnection {
        public final DrainageNode from;
        public final DrainageNode to;
        public final boolean isHeadwater;
        public final boolean isMain;
        public final boolean isLateral;
        public final boolean isCoastal;

        public RiverConnection(DrainageNode from, DrainageNode to, boolean isHeadwater, boolean isMain, boolean isLateral, boolean isCoastal) {
            this.from = from;
            this.to = to;
            this.isHeadwater = isHeadwater;
            this.isMain = isMain;
            this.isLateral = isLateral;
            this.isCoastal = isCoastal;
        }

        public RiverConnection(DrainageNode from, DrainageNode to, boolean isHeadwater, boolean isMain, boolean isLateral) {
            this(from, to, isHeadwater, isMain, isLateral, false);
        }

        public RiverConnection(DrainageNode from, DrainageNode to, boolean isHeadwater, boolean isMain) {
            this(from, to, isHeadwater, isMain, false, false);
        }
    }

    /**
     * Holds the full graph topology for a face or continent for inspection, debugging, and visualization.
     */
    public static class FaceDrainageGraph {
        public final int faceIndex;
        public final float apexX;
        public final float apexZ;
        public final float v1X;
        public final float v1Z;
        public final float v2X;
        public final float v2Z;
        public final boolean isBridged;
        public final List<List<DrainageNode>> rows = new ArrayList<>();
        public final List<DrainageNode> coastalNodes = new ArrayList<>();
        public final List<RiverConnection> connections = new ArrayList<>();
        public final Map<DrainageNode, RiverConnection> outgoing = new IdentityHashMap<>();
        public final Map<DrainageNode, List<RiverConnection>> incoming = new IdentityHashMap<>();

        public FaceDrainageGraph(int faceIndex, float apexX, float apexZ, float v1X, float v1Z, float v2X, float v2Z, boolean isBridged) {
            this.faceIndex = faceIndex;
            this.apexX = apexX;
            this.apexZ = apexZ;
            this.v1X = v1X;
            this.v1Z = v1Z;
            this.v2X = v2X;
            this.v2Z = v2Z;
            this.isBridged = isBridged;
        }

        public FaceDrainageGraph(int faceIndex, float apexX, float apexZ, float v1X, float v1Z, float v2X, float v2Z) {
            this(faceIndex, apexX, apexZ, v1X, v1Z, v2X, v2Z, false);
        }

        public void addConnection(RiverConnection conn) {
            this.connections.add(conn);
            this.outgoing.put(conn.from, conn);
            this.incoming.computeIfAbsent(conn.to, k -> new ArrayList<>()).add(conn);
        }
    }

    private final ThreadLocal<Cell> scratchCell = ThreadLocal.withInitial(Cell::new);

    /**
     * Checks whether a candidate node falls off the edge of the continental uplift plateau into lowlands.
     */
    private boolean isOffUpliftPlateau(DrainageNode node) {
        Cell cell = this.scratchCell.get();
        this.continent.apply(cell.reset(), node.x, node.z);
        return cell.waterTable < 0.01F;
    }

    /**
     * Checks whether a river connection crosses an uphill elevation rise in water table along its actual spline.
     */
    private boolean hasUphillRise(
            RiverConnection conn,
            Map<DrainageNode, RiverConnection> outgoing,
            Map<DrainageNode, List<RiverConnection>> incoming,
            long faceSeed
    ) {
        Cell cell = this.scratchCell.get();
        float prevWaterY = Float.MAX_VALUE;
        for (int s = 0; s <= 20; s++) {
            float t = s / 20.0F;
            long pt = sampleSplinePacked(conn, outgoing, incoming, t, faceSeed);
            float x = PosUtil.unpackLeftf(pt);
            float z = PosUtil.unpackRightf(pt);
            this.continent.apply(cell.reset(), (int) x, (int) z);
            float waterLevel = ContinentalHydrology.getComplexWaterHeight(
                    cell.waterTable,
                    this.continentScale,
                    cell.continentSizeModifier
            ) + this.levels.water;
            float waterY = this.levels.scale(waterLevel);
            if (waterY > prevWaterY + 0.1F) {
                return true;
            }
            prevWaterY = waterY;
        }
        return false;
    }

    /**
     * Checks whether a world position (x, z) is below sea level (in the ocean).
     */
    public boolean isBelowSeaLevel(float x, float z) {
        return this.continent.getEdgeValue(x, z) <= this.shallowOcean;
    }

    /**
     * Checks whether a world position (x, z) is mountain terrain.
     */
    public boolean isMountainTerrain(float x, float z) {
        if (this.context != null && this.context.heightmap != null) {
            Cell cell = this.scratchCell.get();
            this.context.heightmap.applyTerrain(cell.reset(), x, z);
            return cell.terrain.isMountain() || cell.terrain == TerrainType.ISLAND_MOUNTAINS;
        }
        return false;
    }

    public float getLakeChance() {
        return (this.lake != null) ? this.lake.chance : 0.25F;
    }

    /**
     * Looks ahead to row r + 2 to count mountain candidates downstream of the given candidate node.
     * Returns 0 if r + 2 is past the bottom of the graph (e.g. ocean / boundary) or has no mountains.
     * Returns 2 if both downstream options on row r + 2 are mountains (high risk of mountain termination).
     */
    private static int countLookaheadMountains(DrainageNode cand, List<List<DrainageNode>> rows, int rNext, int rowCount) {
        int rLook = rNext + 1; // row r + 2
        if (rLook >= rowCount || rLook >= rows.size()) {
            return 0;
        }
        List<DrainageNode> rowLook = rows.get(rLook);
        if (rowLook.isEmpty()) {
            return 0;
        }
        List<DrainageNode> rowNext = rows.get(rNext);
        int nextCount = rowNext.size();
        int lookCount = rowLook.size();

        float sCand = (nextCount > 1) ? (float) cand.col / (float) (nextCount - 1) : 0.5F;
        int baseCol = Math.round(sCand * (lookCount - 1));

        int c1 = Math.max(0, Math.min(lookCount - 1, baseCol));
        int c2 = (c1 + 1 < lookCount) ? c1 + 1 : c1;
        if (c1 == c2 && c1 > 0) {
            c2 = c1 - 1;
        }

        DrainageNode look1 = rowLook.get(c1);
        DrainageNode look2 = rowLook.get(c2);

        int mtnCount = 0;
        if (!look1.isCulled && look1.isMountain) {
            mtnCount++;
        }
        if (look1 != look2 && !look2.isCulled && look2.isMountain) {
            mtnCount++;
        }
        return mtnCount;
    }

    /**
     * Samples a smooth, rounded, organic spline position between conn.from and conn.to at parameter t in [0, 1].
     * Uses Catmull-Rom cubic Hermite spline interpolation aligned with upstream and downstream flow directions,
     * combined with multi-frequency simplex domain warping and harmonic wiggles to match the organic meandering
     * style of FreeTerraForged rivers.
     */
    public static long sampleSplinePacked(
            RiverConnection conn,
            Map<DrainageNode, RiverConnection> outgoingMap,
            Map<DrainageNode, List<RiverConnection>> incomingMap,
            float t,
            long seed
    ) {
        float x1 = conn.from.x;
        float z1 = conn.from.z;
        float x2 = conn.to.x;
        float z2 = conn.to.z;

        float segDx = x2 - x1;
        float segDz = z2 - z1;
        float segLen = (float) Math.sqrt(segDx * segDx + segDz * segDz);
        if (segLen < 1.0e-4F) {
            return PosUtil.packf(x1, z1);
        }

        float unitSegX = segDx / segLen;
        float unitSegZ = segDz / segLen;

        // 1. Determine incoming tangent at (x1, z1) (averaging all incoming feeders at confluences)
        float inDx = unitSegX;
        float inDz = unitSegZ;
        List<RiverConnection> inList = incomingMap != null ? incomingMap.get(conn.from) : null;
        if (inList != null && !inList.isEmpty()) {
            float sumDx = 0.0F;
            float sumDz = 0.0F;
            for (RiverConnection inConn : inList) {
                float dx = x1 - inConn.from.x;
                float dz = z1 - inConn.from.z;
                float len = (float) Math.sqrt(dx * dx + dz * dz);
                if (len > 1.0e-4F) {
                    sumDx += dx / len;
                    sumDz += dz / len;
                }
            }
            float sumLen = (float) Math.sqrt(sumDx * sumDx + sumDz * sumDz);
            if (sumLen > 1.0e-4F) {
                inDx = sumDx / sumLen;
                inDz = sumDz / sumLen;
            }
        }

        // 2. Determine outgoing tangent at (x2, z2)
        float outDx = unitSegX;
        float outDz = unitSegZ;
        RiverConnection outConn = outgoingMap != null ? outgoingMap.get(conn.to) : null;
        if (outConn != null) {
            float dx = outConn.to.x - x2;
            float dz = outConn.to.z - z2;
            float len = (float) Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0e-4F) {
                outDx = dx / len;
                outDz = dz / len;
            }
        }

        // 3. Smooth normalized tangents at ends with strict forward angle bounding (tamed meander tangent)
        float tan1X = inDx + unitSegX;
        float tan1Z = inDz + unitSegZ;
        float len1 = (float) Math.sqrt(tan1X * tan1X + tan1Z * tan1Z);
        if (len1 > 1.0e-4F) {
            tan1X /= len1;
            tan1Z /= len1;
        } else {
            tan1X = unitSegX;
            tan1Z = unitSegZ;
        }

        // Enforce tan1 forward projection dot(tan1, unitSeg) >= 0.80 (angle <= 36.8 deg)
        float dot1 = tan1X * unitSegX + tan1Z * unitSegZ;
        if (dot1 < 0.80F) {
            float perpX = tan1X - dot1 * unitSegX;
            float perpZ = tan1Z - dot1 * unitSegZ;
            float perpLen = (float) Math.sqrt(perpX * perpX + perpZ * perpZ);
            if (perpLen > 1.0e-4F) {
                tan1X = 0.80F * unitSegX + 0.60F * (perpX / perpLen);
                tan1Z = 0.80F * unitSegZ + 0.60F * (perpZ / perpLen);
            } else {
                tan1X = unitSegX;
                tan1Z = unitSegZ;
            }
        }

        float tan2X = unitSegX + outDx;
        float tan2Z = unitSegZ + outDz;
        float len2 = (float) Math.sqrt(tan2X * tan2X + tan2Z * tan2Z);
        if (len2 > 1.0e-4F) {
            tan2X /= len2;
            tan2Z /= len2;
        } else {
            tan2X = unitSegX;
            tan2Z = unitSegZ;
        }

        // Enforce tan2 forward projection dot(tan2, unitSeg) >= 0.80 (angle <= 36.8 deg)
        float dot2 = tan2X * unitSegX + tan2Z * unitSegZ;
        if (dot2 < 0.80F) {
            float perpX = tan2X - dot2 * unitSegX;
            float perpZ = tan2Z - dot2 * unitSegZ;
            float perpLen = (float) Math.sqrt(perpX * perpX + perpZ * perpZ);
            if (perpLen > 1.0e-4F) {
                tan2X = 0.80F * unitSegX + 0.60F * (perpX / perpLen);
                tan2Z = 0.80F * unitSegZ + 0.60F * (perpZ / perpLen);
            } else {
                tan2X = unitSegX;
                tan2Z = unitSegZ;
            }
        }

        // 4. Cubic Hermite spline interpolation with scale factor 0.35 to guarantee S'(t) . unitSeg > 0
        float h00 = (2.0F * t - 3.0F) * t * t + 1.0F;
        float h10 = ((t - 2.0F) * t + 1.0F) * t;
        float h01 = (-2.0F * t + 3.0F) * t * t;
        float h11 = (t - 1.0F) * t * t;

        float m1X = tan1X * segLen * 0.35F;
        float m1Z = tan1Z * segLen * 0.35F;
        float m2X = tan2X * segLen * 0.35F;
        float m2Z = tan2Z * segLen * 0.35F;

        float sx = h00 * x1 + h10 * m1X + h01 * x2 + h11 * m2X;
        float sz = h00 * z1 + h10 * m1Z + h01 * z2 + h11 * m2Z;

        // Strictly orthogonal transverse vector to segment vector (unitSegZ, -unitSegX).
        // Since norm . unitSeg == 0 identically, transverse meander displacement has zero component
        // along the segment direction, guaranteeing P'(t) . unitSeg == S'(t) . unitSeg > 0 unconditionally.
        float normX = unitSegZ;
        float normZ = -unitSegX;

        // 5. Multi-frequency simplex meandering + harmonic wiggle + organic bank micro-modulation
        // Envelope ensures zero offset at exact node coordinates so junctions connect seamlessly
        float baseSin = (float) Math.sin(t * Math.PI);
        float envelope = (float) Math.pow(Math.max(0.0F, baseSin), 0.70);

        // Low-frequency sweeping meander (~110 block wavelength for ~2 natural curves per 225m terrace step)
        float meanderFreq = 0.0090F;
        float meanderNoise = Simplex2.sample(sx * meanderFreq, sz * meanderFreq, (int) seed);
        float meanderAmp = 9.0F * envelope;

        // Secondary higher-frequency organic wiggle (~30 block wavelength)
        float wiggleFreq = 0.0350F;
        float rads = meanderNoise * (float) Math.PI + (sx + sz) * wiggleFreq;
        float wiggleAmp = 3.5F * envelope;

        // Tertiary high-frequency organic chatter (~12 block wavelength) for natural riverbank variation
        float chatterFreq = 0.0800F;
        float chatterNoise = Simplex2.sample(sx * chatterFreq, sz * chatterFreq, (int) (seed ^ 0x9E3779B9L));
        float chatterAmp = 1.5F * envelope;

        float totalOffset = meanderNoise * meanderAmp + NoiseUtil.cos(rads) * wiggleAmp + chatterNoise * chatterAmp;

        float finalX = sx + normX * totalOffset;
        float finalZ = sz + normZ * totalOffset;

        return PosUtil.packf(finalX, finalZ);
    }

    /**
     * Compatibility helper returning double[] coordinates. For zero-allocation hot paths,
     * use {@link #sampleSplinePacked(RiverConnection, Map, Map, float, long)}.
     */
    public static double[] sampleRoundedSpline(
            RiverConnection conn,
            Map<DrainageNode, RiverConnection> outgoingMap,
            Map<DrainageNode, List<RiverConnection>> incomingMap,
            float t,
            long seed
    ) {
        long packed = sampleSplinePacked(conn, outgoingMap, incomingMap, t, seed);
        return new double[]{PosUtil.unpackLeftf(packed), PosUtil.unpackRightf(packed)};
    }

    /**
     * RiverWarp adapter that computes domain warping along the Catmull-Rom cubic Hermite spline
     * with multi-frequency simplex meandering and harmonic wiggles matching FreeTerraForged river styling.
     */
    public static class SplineRiverWarp extends RiverWarp {
        private final RiverConnection conn;
        private final Map<DrainageNode, RiverConnection> outgoingMap;
        private final Map<DrainageNode, List<RiverConnection>> incomingMap;
        private final long seed;
        private final float kc1;
        private final float kc2;

        public SplineRiverWarp(
                RiverConnection conn,
                Map<DrainageNode, RiverConnection> outgoingMap,
                Map<DrainageNode, List<RiverConnection>> incomingMap,
                long seed
        ) {
            super((int) seed, 0.0F, 1.0F, 0.0016F, 38.0F);
            this.conn = conn;
            this.outgoingMap = outgoingMap;
            this.incomingMap = incomingMap;
            this.seed = seed;

            float x1 = conn.from.x;
            float z1 = conn.from.z;
            float x2 = conn.to.x;
            float z2 = conn.to.z;
            float segDx = x2 - x1;
            float segDz = z2 - z1;
            float segLen = (float) Math.sqrt(segDx * segDx + segDz * segDz);
            float unitSegX = (segLen > 1.0e-4F) ? segDx / segLen : 0.0F;
            float unitSegZ = (segLen > 1.0e-4F) ? segDz / segLen : 0.0F;

            float inDx = unitSegX;
            float inDz = unitSegZ;
            List<RiverConnection> inList = incomingMap != null ? incomingMap.get(conn.from) : null;
            if (inList != null && !inList.isEmpty()) {
                RiverConnection mainIn = inList.get(0);
                for (RiverConnection c : inList) {
                    if (c.isMain) {
                        mainIn = c;
                        break;
                    }
                }
                float dx = x1 - mainIn.from.x;
                float dz = z1 - mainIn.from.z;
                float len = (float) Math.sqrt(dx * dx + dz * dz);
                if (len > 1.0e-4F) {
                    inDx = dx / len;
                    inDz = dz / len;
                }
            }

            float outDx = unitSegX;
            float outDz = unitSegZ;
            RiverConnection outConn = outgoingMap != null ? outgoingMap.get(conn.to) : null;
            if (outConn != null) {
                float dx = outConn.to.x - x2;
                float dz = outConn.to.z - z2;
                float len = (float) Math.sqrt(dx * dx + dz * dz);
                if (len > 1.0e-4F) {
                    outDx = dx / len;
                    outDz = dz / len;
                }
            }

            float tan1X = inDx + unitSegX;
            float tan1Z = inDz + unitSegZ;
            float len1 = (float) Math.sqrt(tan1X * tan1X + tan1Z * tan1Z);
            if (len1 > 1.0e-4F) {
                tan1X /= len1;
                tan1Z /= len1;
            } else {
                tan1X = unitSegX;
                tan1Z = unitSegZ;
            }
            float dot1 = tan1X * unitSegX + tan1Z * unitSegZ;
            if (dot1 < 0.80F) {
                float perpX = tan1X - dot1 * unitSegX;
                float perpZ = tan1Z - dot1 * unitSegZ;
                float perpLen = (float) Math.sqrt(perpX * perpX + perpZ * perpZ);
                if (perpLen > 1.0e-4F) {
                    tan1X = 0.80F * unitSegX + 0.60F * (perpX / perpLen);
                    tan1Z = 0.80F * unitSegZ + 0.60F * (perpZ / perpLen);
                } else {
                    tan1X = unitSegX;
                    tan1Z = unitSegZ;
                }
            }

            float tan2X = unitSegX + outDx;
            float tan2Z = unitSegZ + outDz;
            float len2 = (float) Math.sqrt(tan2X * tan2X + tan2Z * tan2Z);
            if (len2 > 1.0e-4F) {
                tan2X /= len2;
                tan2Z /= len2;
            } else {
                tan2X = unitSegX;
                tan2Z = unitSegZ;
            }
            float dot2 = tan2X * unitSegX + tan2Z * unitSegZ;
            if (dot2 < 0.80F) {
                float perpX = tan2X - dot2 * unitSegX;
                float perpZ = tan2Z - dot2 * unitSegZ;
                float perpLen = (float) Math.sqrt(perpX * perpX + perpZ * perpZ);
                if (perpLen > 1.0e-4F) {
                    tan2X = 0.80F * unitSegX + 0.60F * (perpX / perpLen);
                    tan2Z = 0.80F * unitSegZ + 0.60F * (perpZ / perpLen);
                } else {
                    tan2X = unitSegX;
                    tan2Z = unitSegZ;
                }
            }

            this.kc1 = (tan1X * unitSegX + tan1Z * unitSegZ) * 0.35F;
            this.kc2 = (tan2X * unitSegX + tan2Z * unitSegZ) * 0.35F;
        }

        @Override
        public boolean test(float t) {
            return t >= -0.25F && t <= 1.25F;
        }

        @Override
        public long getOffset(float x, float z, float t, River river) {
            if (t < -0.25F || t > 1.25F) {
                return 0L;
            }
            float clampedT = NoiseUtil.clamp(t, 0.0F, 1.0F);
            float tau = solveSplineTau(clampedT, this.kc1, this.kc2);
            long splinePt = sampleSplinePacked(this.conn, this.outgoingMap, this.incomingMap, tau, this.seed);
            float lx = river.x1 + clampedT * river.dx;
            float lz = river.z1 + clampedT * river.dz;
            float dx = lx - PosUtil.unpackLeftf(splinePt);
            float dz = lz - PosUtil.unpackRightf(splinePt);
            return PosUtil.packf(dx, dz);
        }
    }

    /**
     * Solves the true spline parameter tau in [0, 1] such that the longitudinal projection u(tau)
     * matches the line-projection targetU. Uses 4 iterations of Newton-Raphson with guaranteed
     * quadratic convergence since u'(tau) >= 0.325 > 0 is strictly monotonic.
     */
    public static float solveSplineTau(float targetU, float kc1, float kc2) {
        if (targetU <= 0.0F) return targetU;
        if (targetU >= 1.0F) return targetU;
        float tau = targetU;
        for (int i = 0; i < 4; i++) {
            float tau2 = tau * tau;
            float tau3 = tau2 * tau;
            float h01 = 3.0F * tau2 - 2.0F * tau3;
            float h10 = tau - 2.0F * tau2 + tau3;
            float h11 = -tau2 + tau3;
            float u = h01 + kc1 * h10 + kc2 * h11;
            float err = u - targetU;
            if (Math.abs(err) < 1.0e-5F) break;

            float dh01 = 6.0F * (tau - tau2);
            float dh10 = 1.0F - 4.0F * tau + 3.0F * tau2;
            float dh11 = -2.0F * tau + 3.0F * tau2;
            float du = dh01 + kc1 * dh10 + kc2 * dh11;
            if (du < 1.0e-4F) du = 1.0e-4F;
            tau = NoiseUtil.clamp(tau - err / du, 0.0F, 1.0F);
        }
        return tau;
    }

    protected final GeneratorContext context;

    public TriSubdivisionRiverGenerator(SimpleContinent continent, GeneratorContext context) {
        super(continent, context);
        this.context = context;
    }

    @Override
    public Rivermap generateRivers(int x, int z, long id) {
        PlateUpliftBridge bridge = null;
        if (this.continent instanceof UpliftContinentGenerator uplift) {
            bridge = uplift.getPlateUpliftBridge();
        }
        GenWarp warp = (bridge != null) ? GenWarp.EMPTY : GenWarp.make((int) id, this.continentScale);

        if (this.continent.getEdgeValue(x, z) < this.minEdgeValue) {
            return new Rivermap(x, z, new Network[0], warp);
        }

        Random random = new Random(id + this.seed);
        List<Network.Builder> rivers = this.generateTriSubdivisionRivers(x, z, random, warp);

        for (Network.Builder river : rivers) {
            this.generateWetlands(river, random);
        }

        Network[] networks = rivers.stream().map(Network.Builder::build).toArray(Network[]::new);
        return new Rivermap(x, z, networks, warp);
    }

    /**
     * Builds the drainage graphs for all unbridged faces of the Voronoi centroid and returns
     * the resulting river networks.
     */
    public List<Network.Builder> generateTriSubdivisionRivers(int x, int z, Random random, GenWarp warp) {
        if (this.count <= 0) {
            return new ArrayList<>();
        }

        PlateUpliftBridge bridge = null;
        float frequency = 1.0F / (this.continentScale * 4.0F);

        if (this.continent instanceof UpliftContinentGenerator uplift) {
            bridge = uplift.getPlateUpliftBridge();
            frequency = uplift.getFrequency();
        }

        if (bridge == null) {
            // Fallback for non-uplift continent generators
            return this.generateLegacyRoots(x, z, random, warp);
        }

        PlateUpliftBridge.CellVoronoi voronoi = findVoronoiCell(bridge, frequency, x, z);
        if (voronoi == null) {
            return this.generateLegacyRoots(x, z, random, warp);
        }

        List<FaceDrainageGraph> graphs = buildDrainageGraphs(voronoi, frequency, random);
        List<Network.Builder> networks = new ArrayList<>();
        long worldSeed = (this.context != null && this.context.seed != null) ? this.context.seed.root() : this.seed;

        for (FaceDrainageGraph graph : graphs) {
            long faceWarpSeed = ((long) graph.faceIndex * 123456789L) ^ worldSeed;
            java.util.Map<RiverConnection, Network.Builder> builderMap = new java.util.IdentityHashMap<>();

            for (RiverConnection conn : graph.connections) {
                RiverConfig config = conn.isMain ? this.main : this.fork;
                float boundingRadius = config.calculateBoundingRadius(this.levels);
                River river = new River(conn.from.x, conn.from.z, conn.to.x, conn.to.z, boundingRadius);

                RiverCarverSettings settings = new RiverCarverSettings(random);
                settings.fadeIn = conn.isHeadwater ? config.fade : 0.0F;
                settings.connecting = !conn.isHeadwater;
                settings.valleySize = 275.0F * (conn.isMain ? River.MAIN_VALLEY.next(random) : River.FORK_VALLEY.next(random));

                RiverWarp riverWarp = new SplineRiverWarp(conn, graph.outgoing, graph.incoming, faceWarpSeed);
                FTFRiverCarver carver = new UpliftRiverCarver(river, riverWarp, config, settings, this.levels, this.lake, true);

                builderMap.put(conn, Network.builder(carver));
            }

            // Wire upstream tributary feeders as children of their downstream parent builder
            for (RiverConnection conn : graph.connections) {
                Network.Builder curBuilder = builderMap.get(conn);
                RiverConnection downstream = graph.outgoing.get(conn.to);

                if (downstream != null) {
                    Network.Builder downBuilder = builderMap.get(downstream);
                    downBuilder.children.add(curBuilder);
                } else {
                    // River mouth segment emptying into the ocean: top-level root
                    networks.add(curBuilder);
                }
            }

            // Carve real, flat, organic lake basins for all explicit lake nodes on this face
            for (List<DrainageNode> row : graph.rows) {
                for (DrainageNode lakeNode : row) {
                    if (lakeNode.isLake && !lakeNode.isCulled && !isBelowSeaLevel(lakeNode.x, lakeNode.z)) {
                        RiverConfig config = this.fork;
                        FTFRiverCarver lakeCarver = new UpliftLakeCarver(lakeNode, config, this.levels, this.lake, this.continentScale, worldSeed);
                        networks.add(Network.builder(lakeCarver));
                    }
                }
            }
            for (DrainageNode lakeNode : graph.coastalNodes) {
                if (lakeNode.isLake && !lakeNode.isCulled && !isBelowSeaLevel(lakeNode.x, lakeNode.z)) {
                    RiverConfig config = this.fork;
                    FTFRiverCarver lakeCarver = new UpliftLakeCarver(lakeNode, config, this.levels, this.lake, this.continentScale, worldSeed);
                    networks.add(Network.builder(lakeCarver));
                }
            }
        }

        return networks;
    }

    /**
     * Builds drainage graphs across all valid faces of the given Voronoi centroid polygon
     * using the default riverCount from preset.
     */
    public List<FaceDrainageGraph> buildDrainageGraphs(PlateUpliftBridge.CellVoronoi voronoi, float frequency, Random random) {
        return buildDrainageGraphs(voronoi, frequency, random, this.count);
    }

    /**
     * Builds drainage graphs across all valid faces of the given Voronoi centroid polygon
     * with an explicit riverCount parameter to control node population.
     */
    public List<FaceDrainageGraph> buildDrainageGraphs(PlateUpliftBridge.CellVoronoi voronoi, float frequency, Random random, int riverCount) {
        List<FaceDrainageGraph> graphs = new ArrayList<>();
        float apexX = voronoi.centroidX / frequency;
        float apexZ = voronoi.centroidY / frequency;

        float[] allPlateaus = ContinentalHydrology.getPlateauCenters();

        for (int i = 0; i < voronoi.edgeCount; i++) {
            // Skip thin sliver sectors (< 20 degrees)
            if (PlateUpliftBridge.isSectorTooThin(voronoi.lo[i], voronoi.hi[i], voronoi.len[i])) {
                continue;
            }

            // Tectonic active borders connect to an adjacent peak via a high mountain saddle (~0.75 uplift);
            // tectonic active sectors (peak to peak saddle) should never have nodes or rivers.
            if (voronoi.isTectonicActive[i]) {
                continue;
            }

            // Bridged borders connect towards adjacent landmasses; rivers on these sectors flow
            // downhill from the centroid peak and terminate in lakes before the border.
            boolean isBridged = voronoi.isBridged[i];

            float vAx = (voronoi.mx[i] + voronoi.lo[i] * voronoi.tx[i]) / frequency;
            float vAz = (voronoi.my[i] + voronoi.lo[i] * voronoi.ty[i]) / frequency;
            float vBx = (voronoi.mx[i] + voronoi.hi[i] * voronoi.tx[i]) / frequency;
            float vBz = (voronoi.my[i] + voronoi.hi[i] * voronoi.ty[i]) / frequency;

            // Orient V1 and V2 counter-clockwise from the apex perspective
            float dxA = vAx - apexX;
            float dzA = vAz - apexZ;
            float dxB = vBx - apexX;
            float dzB = vBz - apexZ;
            float cross = dxA * dzB - dzA * dxB;

            float v1X = (cross > 0.0F) ? vAx : vBx;
            float v1Z = (cross > 0.0F) ? vAz : vBz;
            float v2X = (cross > 0.0F) ? vBx : vAx;
            float v2Z = (cross > 0.0F) ? vBz : vAz;

            float midBaseX = (v1X + v2X) * 0.5F;
            float midBaseZ = (v1Z + v2Z) * 0.5F;
            float faceAltitude = NoiseUtil.sqrt((midBaseX - apexX) * (midBaseX - apexX) + (midBaseZ - apexZ) * (midBaseZ - apexZ));
            float baseWidth = NoiseUtil.sqrt((v2X - v1X) * (v2X - v1X) + (v2Z - v1Z) * (v2Z - v1Z));

            if (faceAltitude < 400.0F || baseWidth < 300.0F) {
                continue;
            }

            // Deterministic per-face random sequence
            long faceSeed = random.nextLong() ^ ((long) i * 3141592653589793L);
            Random faceRandom = new Random(faceSeed);

            FaceDrainageGraph graph = buildFaceGraph(i, apexX, apexZ, v1X, v1Z, v2X, v2Z, faceAltitude, baseWidth, allPlateaus, faceRandom, faceSeed, riverCount, isBridged);
            if (!graph.connections.isEmpty() || !graph.rows.isEmpty()) {
                graphs.add(graph);
            }
        }

        return graphs;
    }

    /**
     * Calculates the probability for an empty candidate node on row r to spawn as a headwater.
     * Controlled by riverCount (0 = 0.0%, 30 = 100%).
     */
    private float getNodeSpawnChance(int r, int rowCount, int riverCount) {
        if (riverCount <= 0) {
            return 0.0F;
        }
        if (riverCount >= 30) {
            return 1.0F;
        }

        // Lowland plains outside the continental uplift receive water from upstream networks
        if (r >= ContinentalHydrology.NUM_STEPS) {
            return 0.0F;
        }

        float frac = riverCount / 30.0F;
        if (r == 0) {
            // Centroid peak tier: moderate spawn chance scaled with river count (not every face starts at the peak)
            return frac * 0.9F;
        }

        if (r <= 3) {
            // Upper catchment tiers (r = 1, 2, 3): primary headwater source across mountain face
            float elevWeight = (float) (4 - r) / 4.0F;
            float baseRate = 0.30F + 0.35F * elevWeight;
            return baseRate * frac * 1.5F;
        } else {
            // Highland flank & plateau tiers (r = 4 to 14): tributary and headwater spawning across all uplift plateaus
            float elevWeight = (float) (ContinentalHydrology.NUM_STEPS - r) / (float) (ContinentalHydrology.NUM_STEPS - 4);
            float baseRate = 0.20F + 0.25F * elevWeight;
            return baseRate * frac;
        }
    }

    /**
     * Finds the closest two candidate nodes on the row below BEFORE any ocean culling.
     * Ensures downstream routing only ever considers immediately adjacent downhill slots
     * based on sector fraction s and never jumps laterally across the continent.
     */
    private DrainageNode[] findClosestTwoDownstreamNodes(DrainageNode node, List<DrainageNode> curRow, List<DrainageNode> nextRow) {
        if (nextRow == null || nextRow.isEmpty()) {
            return new DrainageNode[0];
        }
        if (nextRow.size() == 1) {
            return new DrainageNode[]{nextRow.get(0)};
        }
        int curCount = (curRow != null && !curRow.isEmpty()) ? curRow.size() : nextRow.size();
        int nextCount = nextRow.size();

        // Downstream routing: find the immediately adjacent downhill slots in nextRow based on sector fraction s.
        // This strictly prevents lateral river travel by considering ONLY the two slots directly in front of the node.
        float s = (2.0F * (float) node.col + 1.0F) / (2.0F * (float) curCount);
        float targetColF = s * (float) nextCount - 0.5F;
        int c1 = Math.max(0, Math.min(nextCount - 1, Math.round(targetColF)));
        int c2;
        if (targetColF > (float) c1) {
            c2 = Math.min(nextCount - 1, c1 + 1);
        } else {
            c2 = Math.max(0, c1 - 1);
        }
        if (c1 == c2) {
            c2 = (c1 == 0) ? 1 : c1 - 1;
        }

        DrainageNode cand1 = nextRow.get(c1);
        DrainageNode cand2 = nextRow.get(c2);
        return new DrainageNode[]{cand1, cand2};
    }
    /**
     * Finds the distance fraction t in [0.0, 1.0] along a ray from apex towards rayBase where cell.waterTable matches targetU.
     * Where uplift exists, uses bisection to locate the exact center of the ContinentalHydrology
     * flatness factor plateau band (where flatnessFactor == 1.0).
     * If targetU is below the lowest uplift value along this ray (e.g. high tectonic saddle or bridged border),
     * smoothly scales between the lowest valid plateau position and the Voronoi boundary (1.0F).
     */
    private float findPlateauT(float apexX, float apexZ, float rayBaseX, float rayBaseZ, float targetU, Cell scratch) {
        // Measure waterTable at apex (t = 0.0) and at rayBase (t = 1.0)
        this.continent.apply(scratch.reset(), apexX, apexZ);
        float uApex = scratch.waterTable;

        this.continent.apply(scratch.reset(), rayBaseX, rayBaseZ);
        float uBase = scratch.waterTable;

        // If targetU is above apex elevation (e.g. local apex dip), clamp to apex
        if (targetU >= uApex) {
            return 0.0F;
        }

        // Find where uplift ends along this ray: tUpliftBase where waterTable drops to <= 0.01F
        float tUpliftBase;
        if (uBase <= 0.01F) {
            // Uplift ends before or at rayBase (t in [0.0, 1.0])
            float low = 0.0F;
            float high = 1.0F;
            for (int iter = 0; iter < 10; iter++) {
                float mid = (low + high) * 0.5F;
                this.continent.apply(scratch.reset(), (1.0F - mid) * apexX + mid * rayBaseX, (1.0F - mid) * apexZ + mid * rayBaseZ);
                if (scratch.waterTable > 0.01F) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
            tUpliftBase = high;
        } else {
            // Uplift extends all the way to the Voronoi border (e.g. high saddle / bridged border)
            tUpliftBase = 1.0F;
        }

        // Check if targetU exists along the uplift portion of this ray
        float effectiveMinU = Math.max(0.01F, uBase);
        if (targetU >= effectiveMinU) {
            // targetU is guaranteed to exist in [0.0, tUpliftBase]
            float low = 0.0F;
            float high = tUpliftBase;
            for (int iter = 0; iter < 14; iter++) {
                float mid = (low + high) * 0.5F;
                float px = (1.0F - mid) * apexX + mid * rayBaseX;
                float pz = (1.0F - mid) * apexZ + mid * rayBaseZ;
                this.continent.apply(scratch.reset(), px, pz);
                if (scratch.waterTable > targetU) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
            return (low + high) * 0.5F;
        }

        // targetU is below the uplift base on this ray (saddle case where uBase > targetU)
        // Smoothly interpolate between tUpliftBase and 1.0F
        float frac = (effectiveMinU - targetU) / effectiveMinU;
        return tUpliftBase + frac * (1.0F - tUpliftBase);
    }


    /**
     * Subdivides a single face triangle into rows of plateau nodes and connects them into a non-crossing drainage tree.
     * Starts from top of the centroid and works down:
     * - If empty: spawns if spawn chance > position based roll, connecting to one of nearest two nodes in row below.
     * - If already connected to row above: connects to one of nearest two nodes in row below.
     */
    private FaceDrainageGraph buildFaceGraph(
            int faceIndex, float apexX, float apexZ, float v1X, float v1Z, float v2X, float v2Z,
            float faceAltitude, float baseWidth, float[] allPlateaus, Random faceRandom, long faceSeed, int riverCount,
            boolean isBridged
    ) {
        FaceDrainageGraph graph = new FaceDrainageGraph(faceIndex, apexX, apexZ, v1X, v1Z, v2X, v2Z, isBridged);

        // 15 ContinentalHydrology terraces span the uplift bands (rows 0 to 14)
        int upliftRowCount = ContinentalHydrology.NUM_STEPS; // 15
        float targetSpacing = 225.0F;
        Cell scratch = this.scratchCell.get();

        // Calculate lowland span outside the uplift to achieve uniform ~225m row spacing in lowlands
        float midBaseX = (v1X + v2X) * 0.5F;
        float midBaseZ = (v1Z + v2Z) * 0.5F;
        float tCenterUplift = this.findPlateauT(apexX, apexZ, midBaseX, midBaseZ, allPlateaus[0], scratch);
        float lowlandDist = Math.max(0.0F, faceAltitude * (1.0F - tCenterUplift));
        int lowlandRowCount = (lowlandDist > targetSpacing * 0.5F) ? Math.round(lowlandDist / targetSpacing) : 0;
        int totalRowCount = upliftRowCount + lowlandRowCount;

        // 1. Generate candidate rows and nodes for all 15 ContinentalHydrology terraces (rows 0 to 14)
        for (int r = 0; r < upliftRowCount; r++) {
            // Map row 0 to top plateau (index 14, ~0.942) down to row 14 at coastal base (index 0, ~0.030)
            int plateauIndex = (allPlateaus.length - 2) - r;
            float u = allPlateaus[plateauIndex];

            // Determine physical row endpoints along sector boundaries at target elevation u
            float tLeft = this.findPlateauT(apexX, apexZ, v1X, v1Z, u, scratch);
            float leftX = (1.0F - tLeft) * apexX + tLeft * v1X;
            float leftZ = (1.0F - tLeft) * apexZ + tLeft * v1Z;

            float tRight = this.findPlateauT(apexX, apexZ, v2X, v2Z, u, scratch);
            float rightX = (1.0F - tRight) * apexX + tRight * v2X;
            float rightZ = (1.0F - tRight) * apexZ + tRight * v2Z;

            float rowWidth = NoiseUtil.sqrt((rightX - leftX) * (rightX - leftX) + (rightZ - leftZ) * (rightZ - leftZ));
            int nodeCount = Math.max(1, Math.round(rowWidth / targetSpacing));
            if (r > 0) {
                nodeCount = Math.max(graph.rows.get(r - 1).size(), nodeCount);
            }
            nodeCount = Math.min(128, nodeCount);

            List<DrainageNode> rowNodes = new ArrayList<>(nodeCount);
            for (int col = 0; col < nodeCount; col++) {
                float s = (2.0F * col + 1.0F) / (2.0F * (float) nodeCount);
                float jitter = (faceRandom.nextFloat() - 0.5F) * (0.24F / (float) nodeCount);
                float clampedS = NoiseUtil.clamp(s + jitter, 0.02F, 0.98F);

                float rayBaseX = (1.0F - clampedS) * v1X + clampedS * v2X;
                float rayBaseZ = (1.0F - clampedS) * v1Z + clampedS * v2Z;

                float tNode = this.findPlateauT(apexX, apexZ, rayBaseX, rayBaseZ, u, scratch);
                float nodeX = (1.0F - tNode) * apexX + tNode * rayBaseX;
                float nodeZ = (1.0F - tNode) * apexZ + tNode * rayBaseZ;

                DrainageNode node = new DrainageNode(nodeX, nodeZ, u, r, col);
                node.isMountain = this.isMountainTerrain(nodeX, nodeZ);
                rowNodes.add(node);
            }
            graph.rows.add(rowNodes);
        }

        // 1b. Generate lowland candidate rows and nodes outside the uplift bands (uniform ~225m spacing)
        if (lowlandRowCount > 0) {
            float tLeft14 = this.findPlateauT(apexX, apexZ, v1X, v1Z, allPlateaus[0], scratch);
            float tRight14 = this.findPlateauT(apexX, apexZ, v2X, v2Z, allPlateaus[0], scratch);

            for (int r = upliftRowCount; r < totalRowCount; r++) {
                int stepIndex = (r - upliftRowCount) + 1;
                float fracLowland = (float) stepIndex / (float) (lowlandRowCount + 1);

                float tLeft = (1.0F - fracLowland) * tLeft14 + fracLowland * 1.0F;
                float leftX = (1.0F - tLeft) * apexX + tLeft * v1X;
                float leftZ = (1.0F - tLeft) * apexZ + tLeft * v1Z;

                float tRight = (1.0F - fracLowland) * tRight14 + fracLowland * 1.0F;
                float rightX = (1.0F - tRight) * apexX + tRight * v2X;
                float rightZ = (1.0F - tRight) * apexZ + tRight * v2Z;

                float rowWidth = NoiseUtil.sqrt((rightX - leftX) * (rightX - leftX) + (rightZ - leftZ) * (rightZ - leftZ));
                int nodeCount = Math.max(1, Math.round(rowWidth / targetSpacing));
                nodeCount = Math.max(graph.rows.get(r - 1).size(), nodeCount);
                nodeCount = Math.min(128, nodeCount);

                List<DrainageNode> rowNodes = new ArrayList<>(nodeCount);
                for (int col = 0; col < nodeCount; col++) {
                    float s = (2.0F * col + 1.0F) / (2.0F * (float) nodeCount);
                    float jitter = (faceRandom.nextFloat() - 0.5F) * (0.24F / (float) nodeCount);
                    float clampedS = NoiseUtil.clamp(s + jitter, 0.02F, 0.98F);

                    float rayBaseX = (1.0F - clampedS) * v1X + clampedS * v2X;
                    float rayBaseZ = (1.0F - clampedS) * v1Z + clampedS * v2Z;

                    float tRay14 = this.findPlateauT(apexX, apexZ, rayBaseX, rayBaseZ, allPlateaus[0], scratch);
                    float nodeT = (1.0F - fracLowland) * tRay14 + fracLowland * 1.0F;
                    float nodeX = (1.0F - nodeT) * apexX + nodeT * rayBaseX;
                    float nodeZ = (1.0F - nodeT) * apexZ + nodeT * rayBaseZ;

                    DrainageNode node = new DrainageNode(nodeX, nodeZ, 0.0F, r, col);
                    node.isMountain = this.isMountainTerrain(nodeX, nodeZ);
                    rowNodes.add(node);
                }
                graph.rows.add(rowNodes);
            }
        }

        // 2. Identify culling: nodes below sea level (open ocean)
        int rowCount = totalRowCount;
        for (int r = 0; r < rowCount; r++) {
            List<DrainageNode> curRow = graph.rows.get(r);
            for (DrainageNode node : curRow) {
                if (this.isBelowSeaLevel(node.x, node.z)) {
                    node.isCulled = true;
                }
            }
        }

        // If riverCount is 0, no nodes are populated and zero connections are formed
        if (riverCount <= 0) {
            return graph;
        }

        // 3. Starting from top of the centroid and working down row by row
        for (int r = 0; r < rowCount; r++) {
            List<DrainageNode> curRow = graph.rows.get(r);
            int curCount = curRow.size();

            float spawnChance = getNodeSpawnChance(r, rowCount, riverCount);

            // Collect nodes in this row that will connect downward
            List<DrainageNode> activeNodes = new ArrayList<>();

            // Downward candidates for this row
            List<DrainageNode> nextRow = (r < rowCount - 1) ? graph.rows.get(r + 1) : null;

            for (int col = 0; col < curCount; col++) {
                DrainageNode node = curRow.get(col);
                if (node.isCulled) {
                    continue;
                }

                // Position-seeded roll for this specific candidate node
                long posSeed = ((long) Float.floatToIntBits(node.x) * 3129871L)
                             ^ ((long) Float.floatToIntBits(node.z) * 6182933L)
                             ^ (faceSeed + (long) r * 1013L + col);
                Random posRandom = new Random(posSeed);
                float positionBasedRoll = posRandom.nextFloat();

                if (node.feederCount == 0) {
                    if (r == 0 && riverCount < 30 && !activeNodes.isEmpty()) {
                        // Don't place headwaters at every position in topmost row
                        continue;
                    }

                    // Anti-bundling spacing check: prevent parallel river channels running side-by-side
                    // down the same slope. If an immediately adjacent slot in this row is already carrying
                    // a river or active headwater, do not spawn a new parallel headwater next to it!
                    if (riverCount < 30) {
                        if (col > 0 && curRow.get(col - 1).isPopulated) {
                            continue;
                        }
                        if (col < curCount - 1 && curRow.get(col + 1).isPopulated) {
                            continue;
                        }
                    }

                    float effectiveSpawnChance = spawnChance;

                    // Empty node: check spawn chance > position based roll
                    if (effectiveSpawnChance > positionBasedRoll) {
                        node.isPopulated = true;
                        activeNodes.add(node);
                    }
                } else {
                    // Already connected to row above
                    node.isPopulated = true;
                    activeNodes.add(node);
                }
            }

            if (activeNodes.isEmpty()) {
                continue;
            }

            // If at the bottom terrace (ocean baseline) OR nextRow is null:
            if (r == rowCount - 1 || nextRow == null || nextRow.isEmpty()) {
                for (DrainageNode edgeNode : activeNodes) {
                    if (isBridged) {
                        DrainageNode waterMouth = findWaterMouthNode(edgeNode, apexX, apexZ, v1X, v1Z, v2X, v2Z, isBridged, rowCount);
                        if (waterMouth != null) {
                            // Encountered water (ocean/bay) before border buffer: connect cleanly to coastline mouth
                            boolean isHeadwater = (edgeNode.feederCount == 0);
                            boolean isMain = (edgeNode.feederCount >= 2);
                            graph.addConnection(new RiverConnection(edgeNode, waterMouth, isHeadwater, isMain));
                        } else {
                            // Bridged border segment: always ends in a lake if it doesn't encounter water; never reaches actual border
                            edgeNode.isLake = true;
                        }
                    } else {
                        // Open ocean face: trace continuous meandering path across lowlands to the ocean
                        connectToOcean(graph, edgeNode, apexX, apexZ, faceSeed, rowCount);
                    }
                }
                continue;
            }

            // Intermediate rows: each active node considers downhill candidates in nextRow
            int nextCount = nextRow.size();
            int lastTargetCol = -1;

            for (int i = 0; i < activeNodes.size(); i++) {
                DrainageNode node = activeNodes.get(i);
                boolean isHeadwater = (node.feederCount == 0);

                long connSeed = ((long) Float.floatToIntBits(node.x) * 7192831L)
                              ^ ((long) Float.floatToIntBits(node.z) * 4827103L)
                              ^ (faceSeed + (long) r * 37L + (long) node.col * 17L);
                Random connRandom = new Random(connSeed);
                long faceWarpSeed = ((long) faceIndex * 123456789L) ^ ((this.context != null && this.context.seed != null) ? this.context.seed.root() : this.seed);

                float s = (2.0F * (float) node.col + 1.0F) / (2.0F * (float) curCount);
                float targetColF = s * (float) nextCount - 0.5F;
                int baseCol = Math.max(0, Math.min(nextCount - 1, Math.round(targetColF)));

                // On bridged faces, rivers terminate before reaching the border row (rowCount - 1)
                if (isBridged && r >= rowCount - 2) {
                    node.isLake = true;
                    continue;
                }

                // 1. Dendritic Convergence / Tributary Confluence Check:
                // Check if the immediately preceding river on this row (at lastTargetCol) is within natural
                // confluence reach (distance <= 750m) and can receive this tributary.
                DrainageNode mergeCand = null;
                float mergeDist = Float.MAX_VALUE;
                if (lastTargetCol >= 0 && lastTargetCol < nextCount) {
                    DrainageNode prevTarget = nextRow.get(lastTargetCol);
                    float mdx = prevTarget.x - node.x;
                    float mdz = prevTarget.z - node.z;
                    float distSq = mdx * mdx + mdz * mdz;

                    if (distSq <= 750.0F * 750.0F && !prevTarget.isCulled && !isBelowSeaLevel(prevTarget.x, prevTarget.z)) {
                        RiverConnection testMerge = new RiverConnection(node, prevTarget, isHeadwater, true);
                        if (!hasUphillRise(testMerge, graph.outgoing, graph.incoming, faceWarpSeed)) {
                            mergeCand = prevTarget;
                            mergeDist = (float) Math.sqrt(distSq);
                        }
                    }
                }

                // Calculate confluence merging probability based on distance, trunk hierarchy, and valley slope
                float mergeChance = 0.0F;
                if (mergeCand != null && riverCount < 30) {
                    if (mergeCand.feederCount >= 2) {
                        // Merging into an already established main trunk valley
                        mergeChance = 0.92F;
                    } else if (mergeDist <= 350.0F) {
                        // Very close adjacent streams (~225m) on the same slope: strong natural convergence
                        mergeChance = 0.88F;
                    } else if (mergeDist <= 550.0F) {
                        // Moderate convergence distance (~450m)
                        mergeChance = 0.72F;
                    } else {
                        // Outer convergence reach (~650-750m)
                        mergeChance = 0.52F;
                    }

                    // Inward basin slope bias: streams on the right flank (s > 0.5) naturally flow inward toward center
                    if (s > 0.45F) {
                        mergeChance = Math.min(0.96F, mergeChance * 1.20F);
                    }

                    // Terrain bias: if merge candidate is a valley, boost convergence; if mountain, reduce
                    if (!mergeCand.isMountain) {
                        mergeChance = Math.min(0.96F, mergeChance * 1.25F);
                    } else {
                        mergeChance *= 0.60F;
                    }
                }

                if (mergeCand != null && connRandom.nextFloat() < mergeChance) {
                    // Form confluence! The tributary merges into the existing river channel
                    lastTargetCol = mergeCand.col;
                    mergeCand.feederCount++;
                    mergeCand.isPopulated = true;
                    boolean isMain = true;
                    graph.addConnection(new RiverConnection(node, mergeCand, isHeadwater, isMain));
                    continue;
                }

                // 2. Independent Downhill Routing:
                // If not merging, select from candidate slots strictly to the right (> lastTargetCol)
                // to maintain non-crossing invariants.
                int minIndependentCol = Math.max(lastTargetCol + 1, 0);
                if (minIndependentCol >= nextCount) {
                    // No independent columns available without crossing
                    if (mergeCand != null) {
                        // Fall back to merging into the adjacent river
                        lastTargetCol = mergeCand.col;
                        mergeCand.feederCount++;
                        mergeCand.isPopulated = true;
                        graph.addConnection(new RiverConnection(node, mergeCand, isHeadwater, true));
                        continue;
                    }

                    if (isBridged) {
                        DrainageNode waterMouth = findWaterMouthNode(node, apexX, apexZ, v1X, v1Z, v2X, v2Z, isBridged, rowCount);
                        if (waterMouth != null) {
                            boolean isMain = (node.feederCount >= 2);
                            graph.addConnection(new RiverConnection(node, waterMouth, isHeadwater, isMain));
                        } else {
                            node.isLake = true;
                        }
                    } else {
                        if (r < rowCount - 2) {
                            node.isLake = true;
                        } else {
                            connectToOcean(graph, node, apexX, apexZ, faceSeed, rowCount);
                        }
                    }
                    continue;
                }

                int c1 = Math.max(minIndependentCol, Math.min(nextCount - 1, baseCol));
                int c2 = (c1 + 1 < nextCount) ? c1 + 1 : c1;
                if (c1 == c2 && c1 > minIndependentCol) {
                    c2 = c1 - 1;
                }

                DrainageNode cand1 = nextRow.get(c1);
                DrainageNode cand2 = nextRow.get(c2);

                // Both candidates culled
                if (cand1.isCulled && cand2.isCulled) {
                    if (isBridged) {
                        DrainageNode waterMouth = findWaterMouthNode(node, apexX, apexZ, v1X, v1Z, v2X, v2Z, isBridged, rowCount);
                        if (waterMouth != null) {
                            boolean isMain = (node.feederCount >= 2);
                            graph.addConnection(new RiverConnection(node, waterMouth, isHeadwater, isMain));
                        } else {
                            node.isLake = true;
                        }
                    } else {
                        connectToOcean(graph, node, apexX, apexZ, faceSeed, rowCount);
                    }
                    continue;
                }

                // Exactly one candidate culled
                if (cand1.isCulled || cand2.isCulled) {
                    if (!isBridged && r >= rowCount - 3) {
                        connectToOcean(graph, node, apexX, apexZ, faceSeed, rowCount);
                        continue;
                    }

                    DrainageNode validCand = cand1.isCulled ? cand2 : cand1;
                    if (validCand.col < minIndependentCol) {
                        if (mergeCand != null) {
                            lastTargetCol = mergeCand.col;
                            mergeCand.feederCount++;
                            mergeCand.isPopulated = true;
                            graph.addConnection(new RiverConnection(node, mergeCand, isHeadwater, true));
                            continue;
                        }
                        if (isBridged) {
                            DrainageNode waterMouth = findWaterMouthNode(node, apexX, apexZ, v1X, v1Z, v2X, v2Z, isBridged, rowCount);
                            if (waterMouth != null) {
                                boolean isMain = (node.feederCount >= 2);
                                graph.addConnection(new RiverConnection(node, waterMouth, isHeadwater, isMain));
                            } else {
                                node.isLake = true;
                            }
                        } else {
                            node.isLake = true;
                        }
                        continue;
                    }

                    lastTargetCol = validCand.col;
                    validCand.feederCount++;
                    validCand.isPopulated = true;
                    boolean isMain = (node.feederCount >= 2) || (r <= 2 && node.col == 0);
                    graph.addConnection(new RiverConnection(node, validCand, isHeadwater, isMain));
                    continue;
                }

                // Neither candidate is culled
                // Mountain Blockage Rule:
                if (riverCount < 30 && cand1.isMountain && cand2.isMountain) {
                    if (connRandom.nextFloat() < 0.75F) {
                        node.isLake = true;
                        continue;
                    }
                }

                boolean isMain = (node.feederCount >= 2) || (r <= 2 && node.col == 0);
                RiverConnection testConn1 = new RiverConnection(node, cand1, isHeadwater, isMain);
                RiverConnection testConn2 = new RiverConnection(node, cand2, isHeadwater, isMain);
                boolean rise1 = hasUphillRise(testConn1, graph.outgoing, graph.incoming, faceWarpSeed);
                boolean rise2 = (cand1 == cand2) ? rise1 : hasUphillRise(testConn2, graph.outgoing, graph.incoming, faceWarpSeed);

                DrainageNode chosen;
                if (rise1 && !rise2) {
                    chosen = cand2;
                } else if (!rise1 && rise2) {
                    chosen = cand1;
                } else if (rise1 && rise2) {
                    if (mergeCand != null) {
                        // Merge into adjacent valley river rather than getting blocked
                        chosen = mergeCand;
                        lastTargetCol = chosen.col;
                        chosen.feederCount++;
                        chosen.isPopulated = true;
                        graph.addConnection(new RiverConnection(node, chosen, isHeadwater, true));
                        continue;
                    }
                    node.isLake = true;
                    continue;
                } else {
                    boolean off1 = (node.gradient > 0.08F) && isOffUpliftPlateau(cand1);
                    boolean off2 = (node.gradient > 0.08F) && isOffUpliftPlateau(cand2);

                    if (off1 && !off2) {
                        chosen = cand2;
                    } else if (!off1 && off2) {
                        chosen = cand1;
                    } else {
                        boolean fed1 = cand1.feederCount > 0;
                        boolean fed2 = cand2.feederCount > 0;
                        boolean fedDiff = (cand1 != cand2) && (fed1 ^ fed2);
                        boolean mtnDiff = (cand1 != cand2) && (cand1.isMountain ^ cand2.isMountain);

                        if (cand1 == cand2) {
                            chosen = cand1;
                        } else if (fedDiff && !mtnDiff) {
                            // Candidates have equal mountain status on row r + 1, but differ in feeder status.
                            // Check 2-row lookahead to avoid merging into a channel that dead-ends into mountains.
                            int laMtn1 = countLookaheadMountains(cand1, graph.rows, r + 1, rowCount);
                            int laMtn2 = countLookaheadMountains(cand2, graph.rows, r + 1, rowCount);
                            if (laMtn1 != laMtn2) {
                                float score1 = fed1 ? 4.0F : 1.0F;
                                float score2 = fed2 ? 4.0F : 1.0F;
                                if (laMtn1 < laMtn2) score1 *= 4.0F; else score2 *= 4.0F;
                                float p1 = score1 / (score1 + score2);
                                chosen = (connRandom.nextFloat() < p1) ? cand1 : cand2;
                            } else {
                                DrainageNode fed = fed1 ? cand1 : cand2;
                                DrainageNode unfed = fed1 ? cand2 : cand1;
                                chosen = (connRandom.nextFloat() < 0.80F) ? fed : unfed;
                            }
                        } else if (mtnDiff && !fedDiff) {
                            DrainageNode nonMtn = (!cand1.isMountain) ? cand1 : cand2;
                            DrainageNode mtn = (cand1.isMountain) ? cand1 : cand2;
                            chosen = (connRandom.nextFloat() < 0.80F) ? nonMtn : mtn;
                        } else if (fedDiff && mtnDiff) {
                            float score1 = 1.0F;
                            float score2 = 1.0F;
                            if (!cand1.isMountain) score1 *= 4.0F; else score2 *= 4.0F;
                            if (fed1) score1 *= 4.0F; else score2 *= 4.0F;
                            float p1 = score1 / (score1 + score2);
                            chosen = (connRandom.nextFloat() < p1) ? cand1 : cand2;
                        } else {
                            // Two-row lookahead downstream check:
                            // Where candidate nodes are equal otherwise (!mtnDiff && !fedDiff),
                            // evaluate whether their downstream candidates on row r + 2 lead into mountains.
                            int laMtn1 = countLookaheadMountains(cand1, graph.rows, r + 1, rowCount);
                            int laMtn2 = countLookaheadMountains(cand2, graph.rows, r + 1, rowCount);

                            if (laMtn1 != laMtn2) {
                                DrainageNode laNonMtn = (laMtn1 < laMtn2) ? cand1 : cand2;
                                DrainageNode laMtn = (laMtn1 < laMtn2) ? cand2 : cand1;
                                chosen = (connRandom.nextFloat() < 0.80F) ? laNonMtn : laMtn;
                            } else {
                                // Both candidates equal on 1-row and 2-row lookahead:
                                // Organic meandering with inward drainage basin bias towards central valley
                                if (s < 0.45F && cand2.col > cand1.col) {
                                    chosen = (connRandom.nextFloat() < 0.68F) ? cand2 : cand1;
                                } else if (s > 0.55F && cand1.col < cand2.col) {
                                    chosen = (connRandom.nextFloat() < 0.68F) ? cand1 : cand2;
                                } else {
                                    chosen = (connRandom.nextFloat() < 0.55F) ? cand1 : cand2;
                                }
                            }
                        }
                    }
                }

                lastTargetCol = chosen.col;
                chosen.feederCount++;
                chosen.isPopulated = true;
                graph.addConnection(new RiverConnection(node, chosen, isHeadwater, isMain));
            }
        }

        // 4. Place lakes on explicit nodes sitting centrally on ContinentalHydrology terrace plateaus
        float lakeChance = (this.lake != null) ? this.lake.chance : 0.25F;
        if (lakeChance > 0.0F && riverCount < 30) {
            for (int r = 0; r < rowCount; r++) {
                List<DrainageNode> row = graph.rows.get(r);
                for (int col = 0; col < row.size(); col++) {
                    DrainageNode node = row.get(col);

                    // Strictly verify node sits on land (never spawn lakes in ocean or on culled nodes)
                    if (node.isCulled || isBelowSeaLevel(node.x, node.z)) {
                        node.isLake = false;
                        continue;
                    }

                    // In non-lowland locations, ensure lakes never leave the flatness factor alpha mask
                    float nodeFlatness = 1.0F;
                    if (node.gradient > 0.01F) {
                        this.continent.apply(scratch.reset(), node.x, node.z);
                        nodeFlatness = ContinentalHydrology.getFlatnessFactor(scratch.waterTable);
                        if (nodeFlatness <= 0.05F) {
                            continue; // Never spawn outside the flatness factor alpha mask in non-lowlands
                        }
                    }

                    long lakeSeed = ((long) Float.floatToIntBits(node.x) * 8192837L)
                                  ^ ((long) Float.floatToIntBits(node.z) * 1928371L)
                                  ^ (faceSeed + (long) r * 2003L + (long) col * 17L);
                    Random lakeRandom = new Random(lakeSeed);

                    // A. Confluence nodes: multiple incoming river channels meet -> High chance
                    if (node.feederCount >= 2) {
                        float confluenceLakeChance = lakeChance * 0.45F * nodeFlatness;
                        if (lakeRandom.nextFloat() < confluenceLakeChance) {
                            node.isLake = true;
                        }
                    }
                    // B. Inline flowing river nodes: single feeder with downstream outflow -> Rare chance
                    else if (node.feederCount == 1 && graph.outgoing.containsKey(node)) {
                        float inlineLakeChance = lakeChance * 0.08F * nodeFlatness;
                        if (lakeRandom.nextFloat() < inlineLakeChance) {
                            node.isLake = true;
                        }
                    }
                    // C. Spring / Headwater nodes: river origin feeding a downstream river
                    else if (node.feederCount == 0 && graph.outgoing.containsKey(node)) {
                        float springLakeChance = lakeChance * 0.20F * nodeFlatness;
                        if (lakeRandom.nextFloat() < springLakeChance) {
                            node.isLake = true;
                        }
                    }
                }
            }
        }

        // 4a. Validate full-spline monotonicity with complete tangent topology:
        // If any connection exhibits an uphill rise along its final rounded spline, prune it so the upstream node forms a terminal lake.
        long faceWarpSeed = ((long) faceIndex * 123456789L) ^ ((this.context != null && this.context.seed != null) ? this.context.seed.root() : this.seed);
        List<RiverConnection> uphillConns = new ArrayList<>();
        for (RiverConnection conn : graph.connections) {
            if (!conn.isLateral && hasUphillRise(conn, graph.outgoing, graph.incoming, faceWarpSeed)) {
                uphillConns.add(conn);
            }
        }
        for (RiverConnection conn : uphillConns) {
            graph.connections.remove(conn);
            graph.outgoing.remove(conn.from);
            List<RiverConnection> inList = graph.incoming.get(conn.to);
            if (inList != null) {
                inList.remove(conn);
            }
            conn.to.feederCount = Math.max(0, conn.to.feederCount - 1);
            conn.from.isLake = true;
        }

        // 4b. Guaranteed lake basin for ANY terminal river on land (flowing river terminating without ocean outlet)
        for (List<DrainageNode> row : graph.rows) {
            for (DrainageNode node : row) {
                if (node.feederCount >= 1 && !graph.outgoing.containsKey(node)) {
                    if (!node.isCulled && !isBelowSeaLevel(node.x, node.z)) {
                        node.isLake = true;
                    }
                }
            }
        }
        for (DrainageNode node : graph.coastalNodes) {
            if (node.feederCount >= 1 && !graph.outgoing.containsKey(node)) {
                if (!node.isCulled && !isBelowSeaLevel(node.x, node.z)) {
                    node.isLake = true;
                }
            }
        }

        // 5. Chance-based isolated lake spawning at any open node after the river pass completes (including lowland areas)
        if (lakeChance > 0.0F && riverCount < 30) {
            for (int r = 0; r < rowCount; r++) {
                List<DrainageNode> row = graph.rows.get(r);
                for (int col = 0; col < row.size(); col++) {
                    DrainageNode node = row.get(col);

                    // Strictly verify node sits on land (never spawn lakes in ocean or on culled nodes)
                    if (node.isCulled || isBelowSeaLevel(node.x, node.z)) {
                        continue;
                    }

                    // In non-lowland locations, ensure lakes never leave the flatness factor alpha mask
                    float nodeFlatness = 1.0F;
                    if (node.gradient > 0.01F) {
                        this.continent.apply(scratch.reset(), node.x, node.z);
                        nodeFlatness = ContinentalHydrology.getFlatnessFactor(scratch.waterTable);
                        if (nodeFlatness <= 0.05F) {
                            continue; // Never spawn outside the flatness factor alpha mask in non-lowlands
                        }
                    }

                    // Any open node: untouched by rivers (not populated, no incoming feeders, no outgoing river, not already a lake)
                    if (!node.isPopulated && node.feederCount == 0 && !graph.outgoing.containsKey(node) && !node.isLake) {
                        long lakeSeed = ((long) Float.floatToIntBits(node.x) * 8192837L)
                                      ^ ((long) Float.floatToIntBits(node.z) * 1928371L)
                                      ^ (faceSeed + (long) r * 2003L + (long) col * 17L);
                        Random lakeRandom = new Random(lakeSeed);

                        float isolatedLakeChance = lakeChance * 0.12F * nodeFlatness;
                        if (lakeRandom.nextFloat() < isolatedLakeChance) {
                            node.isLake = true;
                        }
                    }
                }
            }
        }

        // 6. Make neighbouring lakes adjacent to another in the same row always bridge together to form a larger, diverse lake
        if (riverCount < 30) {
            for (int r = 0; r < rowCount; r++) {
                List<DrainageNode> row = graph.rows.get(r);
                int rowSize = row.size();
                for (int col = 0; col < rowSize - 1; col++) {
                    DrainageNode nodeA = row.get(col);
                    DrainageNode nodeB = row.get(col + 1);

                    if (nodeA.isLake && nodeB.isLake) {
                        bridgeAdjacentLakes(graph, nodeA, nodeB);
                    }
                }
            }
        }

        return graph;
    }

    /**
     * Bridges two neighbouring lakes adjacent to each other on the same row with a lateral connection,
     * merging them into a larger, diverse multi-lobed lake body at the exact same elevation.
     */
    private void bridgeAdjacentLakes(FaceDrainageGraph graph, DrainageNode nodeA, DrainageNode nodeB) {
        RiverConnection outA = graph.outgoing.get(nodeA);
        RiverConnection outB = graph.outgoing.get(nodeB);

        // Already bridged together
        if ((outA != null && outA.to == nodeB) || (outB != null && outB.to == nodeA)) {
            return;
        }

        nodeA.isPopulated = true;
        nodeB.isPopulated = true;

        if (outA == null && outB == null) {
            // Neither has an outgoing connection: connect nodeA -> nodeB
            nodeB.feederCount++;
            boolean isHead = (nodeA.feederCount == 0);
            graph.addConnection(new RiverConnection(nodeA, nodeB, isHead, false, true));
        } else if (outA != null && outB == null) {
            // nodeA already has an outlet (e.g. downstream): connect nodeB -> nodeA
            nodeA.feederCount++;
            boolean isHead = (nodeB.feederCount == 0);
            graph.addConnection(new RiverConnection(nodeB, nodeA, isHead, false, true));
        } else if (outA == null && outB != null) {
            // nodeB already has an outlet: connect nodeA -> nodeB
            nodeB.feederCount++;
            boolean isHead = (nodeA.feederCount == 0);
            graph.addConnection(new RiverConnection(nodeA, nodeB, isHead, false, true));
        } else {
            // Both already have outgoing connections: redirect the branch with fewer feeders into the other
            if (nodeA.feederCount <= nodeB.feederCount && !outA.isLateral) {
                graph.connections.remove(outA);
                graph.outgoing.remove(nodeA);
                List<RiverConnection> inList = graph.incoming.get(outA.to);
                if (inList != null) inList.remove(outA);
                outA.to.feederCount = Math.max(0, outA.to.feederCount - 1);

                nodeB.feederCount++;
                boolean isHead = (nodeA.feederCount == 0);
                graph.addConnection(new RiverConnection(nodeA, nodeB, isHead, false, true));
            } else if (!outB.isLateral) {
                graph.connections.remove(outB);
                graph.outgoing.remove(nodeB);
                List<RiverConnection> inList = graph.incoming.get(outB.to);
                if (inList != null) inList.remove(outB);
                outB.to.feederCount = Math.max(0, outB.to.feederCount - 1);

                nodeA.feederCount++;
                boolean isHead = (nodeB.feederCount == 0);
                graph.addConnection(new RiverConnection(nodeB, nodeA, isHead, false, true));
            }
        }
    }

    /**
     * Looks forward from edgeNode along the sector drainage trajectory to find if a coastline crossing
     * (sea level water) is encountered within maxStep blocks.
     * On bridged segments, enforces a strict buffer from the Voronoi border so that rivers never reach the border.
     * Returns a mouth node at the water if encountered, or null if water was not encountered.
     */
    private DrainageNode findWaterMouthNode(
            DrainageNode edgeNode, float apexX, float apexZ,
            float v1X, float v1Z, float v2X, float v2Z,
            boolean isBridged, int rowCount
    ) {
        float dx = edgeNode.x - apexX;
        float dz = edgeNode.z - apexZ;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4F) {
            return null;
        }

        float ux = dx / len;
        float uz = dz / len;

        float maxStep = 500.0F;

        if (isBridged) {
            // Compute distance from edgeNode along ray to the Voronoi border segment (v1 -> v2)
            float bx = v2X - v1X;
            float bz = v2Z - v1Z;
            float v1ax = v1X - apexX;
            float v1az = v1Z - apexZ;
            float num = v1ax * bz - v1az * bx;
            float den = dx * bz - dz * bx;

            float borderBuffer = 200.0F;
            if (Math.abs(den) > 1.0e-5F) {
                float lambda = num / den;
                float distToBorder = (lambda - 1.0F) * len;
                float allowedStep = Math.max(0.0F, distToBorder - borderBuffer);
                maxStep = Math.min(maxStep, allowedStep);
            } else {
                maxStep = 0.0F;
            }

            if (maxStep <= 15.0F) {
                return null; // Too close to border, terminate in lake instead
            }
        }

        float curDist = 0.0F;
        float stepSize = 15.0F;
        float mouthDist = -1.0F;

        while (curDist <= maxStep) {
            float testX = edgeNode.x + ux * curDist;
            float testZ = edgeNode.z + uz * curDist;
            if (this.isBelowSeaLevel(testX, testZ)) {
                // Binary search refined coastline crossing
                float lo = curDist - stepSize;
                float hi = curDist;
                for (int b = 0; b < 6; b++) {
                    float mid = (lo + hi) * 0.5F;
                    if (this.isBelowSeaLevel(edgeNode.x + ux * mid, edgeNode.z + uz * mid)) {
                        hi = mid;
                    } else {
                        lo = mid;
                    }
                }
                mouthDist = hi;
                break;
            }
            curDist += stepSize;
        }

        if (mouthDist < 0.0F) {
            return null; // Did not encounter water within allowed bounds
        }

        float mouthX = edgeNode.x + ux * mouthDist;
        float mouthZ = edgeNode.z + uz * mouthDist;
        int mouthRow = Math.min(rowCount - 1, edgeNode.row + 1);
        DrainageNode mouthNode = new DrainageNode(mouthX, mouthZ, 0.0F, mouthRow, edgeNode.col);
        mouthNode.isPopulated = true;
        return mouthNode;
    }

    /**
     * Connects an edge node on an unbridged (open ocean) face across the continental lowlands/plains
     * all the way to the true ocean coastline (where edgeValue <= shallowOcean).
     * Subdivides long coastal paths into organic meandering segments of ~225 blocks spacing,
     * maintaining smooth spline continuity all the way to the ocean mouth.
     */
    private void connectToOcean(
            FaceDrainageGraph graph, DrainageNode edgeNode,
            float apexX, float apexZ, long faceSeed, int rowCount
    ) {
        float dx = edgeNode.x - apexX;
        float dz = edgeNode.z - apexZ;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4F) {
            return;
        }

        float ux = dx / len;
        float uz = dz / len;

        // Trace outward along the drainage ray to pinpoint the true open ocean coastline
        float maxOceanDist = Math.max(6000.0F, this.continentScale * 2.5F);
        float stepSize = 30.0F;
        float curDist = 0.0F;
        float mouthDist = -1.0F;
        float lookahead = 200.0F;

        while (curDist <= maxOceanDist) {
            float testX = edgeNode.x + ux * curDist;
            float testZ = edgeNode.z + uz * curDist;
            if (this.isBelowSeaLevel(testX, testZ)) {
                // Verify that this is the true open ocean (remains below sea level across lookahead window)
                boolean openWater = true;
                for (float checkDist = curDist + stepSize; checkDist <= curDist + lookahead && checkDist <= maxOceanDist; checkDist += stepSize) {
                    if (!this.isBelowSeaLevel(edgeNode.x + ux * checkDist, edgeNode.z + uz * checkDist)) {
                        openWater = false;
                        curDist = checkDist; // Jump forward past the coastal barrier/sandbar
                        break;
                    }
                }

                if (openWater) {
                    // Binary search refined coastline crossing (6 iterations)
                    float lo = Math.max(0.0F, curDist - stepSize);
                    float hi = curDist;
                    for (int b = 0; b < 6; b++) {
                        float mid = (lo + hi) * 0.5F;
                        if (this.isBelowSeaLevel(edgeNode.x + ux * mid, edgeNode.z + uz * mid)) {
                            hi = mid;
                        } else {
                            lo = mid;
                        }
                    }
                    // Extend slightly past the coastline crossing into shallow ocean to form a submerged estuary mouth
                    mouthDist = hi + 35.0F;
                    if (!this.isBelowSeaLevel(edgeNode.x + ux * mouthDist, edgeNode.z + uz * mouthDist)) {
                        mouthDist = hi;
                    }
                    break;
                }
            }
            curDist += stepSize;
        }

        if (mouthDist < 0.0F) {
            // Did not reach ocean within max distance: terminate in an organic endorheic lake on this terrace
            edgeNode.isLake = true;
            return;
        }

        // Subdivide coastal journey into organic meandering segments (~225m spacing)
        float targetSpacing = 225.0F;
        int K = Math.max(1, Math.round(mouthDist / targetSpacing));
        float segStep = mouthDist / (float) K;

        // Unit normal vector perpendicular to flow direction
        float normX = -uz;
        float normZ = ux;

        DrainageNode prevNode = edgeNode;
        boolean isMain = (edgeNode.feederCount >= 2);
        int baseRow = Math.max(rowCount, edgeNode.row + 1);

        for (int j = 1; j < K; j++) {
            float frac = (float) j / (float) K;
            float dist = j * segStep;
            float baseX = edgeNode.x + ux * dist;
            float baseZ = edgeNode.z + uz * dist;

            // Organic meandering perpendicular offset with smooth envelope tapering to 0 at ends
            float offsetNoise = Simplex2.sample(baseX * 0.002F, baseZ * 0.002F, (int) faceSeed);
            float envelope = (float) Math.sin(frac * Math.PI);
            float offset = offsetNoise * 65.0F * envelope;

            float nodeX = baseX + normX * offset;
            float nodeZ = baseZ + normZ * offset;
            float grad = edgeNode.gradient * (1.0F - frac);
            int nodeRow = baseRow + (j - 1);

            DrainageNode interNode = new DrainageNode(nodeX, nodeZ, grad, nodeRow, edgeNode.col);
            interNode.isPopulated = true;
            interNode.feederCount = 1;
            graph.coastalNodes.add(interNode);

            boolean isHead = (prevNode == edgeNode && edgeNode.feederCount == 0);
            graph.addConnection(new RiverConnection(prevNode, interNode, isHead, isMain, false, true));
            prevNode = interNode;
        }

        // Final mouth node at the exact ocean coastline
        float mouthX = edgeNode.x + ux * mouthDist;
        float mouthZ = edgeNode.z + uz * mouthDist;
        int mouthRow = baseRow + (K - 1);
        DrainageNode mouthNode = new DrainageNode(mouthX, mouthZ, 0.0F, mouthRow, edgeNode.col);
        mouthNode.isPopulated = true;
        mouthNode.feederCount = 1;
        graph.coastalNodes.add(mouthNode);

        boolean isHead = (prevNode == edgeNode && edgeNode.feederCount == 0);
        graph.addConnection(new RiverConnection(prevNode, mouthNode, isHead, isMain, false, true));
    }

    /**
     * Resolves the Voronoi polygon cell corresponding to world centroid coordinates (x, z).
     */
    public static PlateUpliftBridge.CellVoronoi findVoronoiCell(PlateUpliftBridge bridge, float frequency, int x, int z) {
        float fx = x * frequency;
        float fz = z * frequency;
        int xi = NoiseUtil.floor(fx);
        int zi = NoiseUtil.floor(fz);

        PlateUpliftBridge.CellVoronoi best = null;
        long bestDistSq = Long.MAX_VALUE;

        for (int cy = zi - 2; cy <= zi + 2; cy++) {
            for (int cx = xi - 2; cx <= xi + 2; cx++) {
                PlateUpliftBridge.CellVoronoi v = bridge.getVoronoi(cx, cy);
                int vcx = Math.round(v.centroidX / frequency);
                int vcz = Math.round(v.centroidY / frequency);
                long dSq = (long)(vcx - x) * (long)(vcx - x) + (long)(vcz - z) * (long)(vcz - z);
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    best = v;
                    if (dSq == 0) {
                        return best;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Fallback for non-uplift continent generators.
     */
    private List<Network.Builder> generateLegacyRoots(int x, int z, Random random, GenWarp warp) {
        List<Network.Builder> roots = new ArrayList<>(this.count);
        for (int i = 0; i < this.count; ++i) {
            float angle = random.nextFloat() * 6.2831855F;
            float dx = NoiseUtil.sin(angle);
            float dz = NoiseUtil.cos(angle);
            float startMod = 0.05F + random.nextFloat() * 0.45F;
            float length = this.continent.getDistanceToOcean(x, z, dx, dz);
            float startDist = Math.max(400.0F, startMod * length);
            float x2 = x + dx * startDist;
            float z2 = z + dz * startDist;
            float x3 = x + dx * length;
            float z3 = z + dz * length;
            float valleyWidth = 275.0F * River.MAIN_VALLEY.next(random);
            float boundingRadius = this.main.calculateBoundingRadius(this.levels);
            River river = new River((float) (int) x2, (float) (int) z2, (float) (int) x3, (float) (int) z3, boundingRadius);
            RiverCarverSettings settings = new RiverCarverSettings(random);
            settings.fadeIn = this.main.fade;
            settings.valleySize = valleyWidth;
            RiverWarp riverWarp = RiverWarp.create(0.1F, 0.85F, random);
            FTFRiverCarver carver = new UpliftRiverCarver(river, riverWarp, this.main, settings, this.levels, this.lake, this.continent instanceof UpliftContinentGenerator);
            if (this.isInBridgedSector(x, z, dx, dz) || this.isInBridgedSector(x2, z2) || this.terminatesOnLand(x3, z3) || this.endsInNeighbourCell(x, z, x3, z3)) {
                continue;
            }
            roots.add(Network.builder(carver));
        }
        return roots;
    }
}
