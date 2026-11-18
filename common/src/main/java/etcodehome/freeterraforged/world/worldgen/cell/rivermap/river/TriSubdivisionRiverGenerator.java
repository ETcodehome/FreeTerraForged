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
     * Samples a smooth, rounded, organic spline position between conn.from and conn.to at parameter t in [0, 1].
     * Uses Catmull-Rom cubic Hermite spline interpolation aligned with upstream and downstream flow directions,
     * combined with multi-frequency simplex domain warping and harmonic wiggles to match the organic meandering
     * style of FreeTerraForged rivers.
     */
    public static double[] sampleRoundedSpline(
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
            return new double[]{x1, z1};
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

        // Enforce tan1 forward projection dot(tan1, unitSeg) >= 0.50 (angle <= 60 deg)
        float dot1 = tan1X * unitSegX + tan1Z * unitSegZ;
        if (dot1 < 0.50F) {
            float perpX = tan1X - dot1 * unitSegX;
            float perpZ = tan1Z - dot1 * unitSegZ;
            float perpLen = (float) Math.sqrt(perpX * perpX + perpZ * perpZ);
            if (perpLen > 1.0e-4F) {
                tan1X = 0.50F * unitSegX + 0.8660254F * (perpX / perpLen);
                tan1Z = 0.50F * unitSegZ + 0.8660254F * (perpZ / perpLen);
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

        // Enforce tan2 forward projection dot(tan2, unitSeg) >= 0.50 (angle <= 60 deg)
        float dot2 = tan2X * unitSegX + tan2Z * unitSegZ;
        if (dot2 < 0.50F) {
            float perpX = tan2X - dot2 * unitSegX;
            float perpZ = tan2Z - dot2 * unitSegZ;
            float perpLen = (float) Math.sqrt(perpX * perpX + perpZ * perpZ);
            if (perpLen > 1.0e-4F) {
                tan2X = 0.50F * unitSegX + 0.8660254F * (perpX / perpLen);
                tan2Z = 0.50F * unitSegZ + 0.8660254F * (perpZ / perpLen);
            } else {
                tan2X = unitSegX;
                tan2Z = unitSegZ;
            }
        }

        // 4. Cubic Hermite spline interpolation with scale factor 0.65 to guarantee S'(t) . unitSeg > 0
        float h00 = (2.0F * t - 3.0F) * t * t + 1.0F;
        float h10 = ((t - 2.0F) * t + 1.0F) * t;
        float h01 = (-2.0F * t + 3.0F) * t * t;
        float h11 = (t - 1.0F) * t * t;

        float m1X = tan1X * segLen * 0.65F;
        float m1Z = tan1Z * segLen * 0.65F;
        float m2X = tan2X * segLen * 0.65F;
        float m2Z = tan2Z * segLen * 0.65F;

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
        float meanderAmp = 22.0F * envelope;

        // Secondary higher-frequency organic wiggle (~30 block wavelength)
        float wiggleFreq = 0.0350F;
        float rads = meanderNoise * (float) Math.PI + (sx + sz) * wiggleFreq;
        float wiggleAmp = 8.0F * envelope;

        // Tertiary high-frequency organic chatter (~12 block wavelength) for natural riverbank variation
        float chatterFreq = 0.0800F;
        float chatterNoise = Simplex2.sample(sx * chatterFreq, sz * chatterFreq, (int) (seed ^ 0x9E3779B9L));
        float chatterAmp = 3.0F * envelope;

        float totalOffset = meanderNoise * meanderAmp + NoiseUtil.cos(rads) * wiggleAmp + chatterNoise * chatterAmp;

        double finalX = sx + normX * totalOffset;
        double finalZ = sz + normZ * totalOffset;

        return new double[]{finalX, finalZ};
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
            double[] splinePt = sampleRoundedSpline(this.conn, this.outgoingMap, this.incomingMap, clampedT, this.seed);
            float lx = river.x1 + clampedT * river.dx;
            float lz = river.z1 + clampedT * river.dz;
            float dx = lx - (float) splinePt[0];
            float dz = lz - (float) splinePt[1];
            return PosUtil.packf(dx, dz);
        }
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
                boolean isTerminalLake = conn.to.isLake && !graph.outgoing.containsKey(conn.to);
                float boundingRadius = config.calculateBoundingRadius(this.levels);
                if (isTerminalLake) {
                    boundingRadius = Math.max(boundingRadius, 450.0F);
                }
                River river = new River(conn.from.x, conn.from.z, conn.to.x, conn.to.z, boundingRadius);

                RiverCarverSettings settings = new RiverCarverSettings(random);
                settings.fadeIn = conn.isHeadwater ? config.fade : 0.0F;
                settings.connecting = !conn.isHeadwater;
                settings.isTerminalLake = isTerminalLake;
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

            // Carve real lake basins for isolated valley lakes on this face
            for (List<DrainageNode> row : graph.rows) {
                for (DrainageNode lakeNode : row) {
                    if (lakeNode.isLake && !lakeNode.isPopulated && lakeNode.feederCount == 0 && !lakeNode.isCulled) {
                        RiverConfig config = this.fork;
                        float boundingRadius = 450.0F;
                        River lakeRiver = new River(lakeNode.x, lakeNode.z, lakeNode.x + 0.1F, lakeNode.z + 0.1F, boundingRadius);
                        RiverCarverSettings settings = new RiverCarverSettings(random);
                        settings.fadeIn = 0.0F;
                        settings.connecting = false;
                        settings.isTerminalLake = true;
                        settings.valleySize = 250.0F;
                        FTFRiverCarver carver = new UpliftRiverCarver(lakeRiver, RiverWarp.NONE, config, settings, this.levels, this.lake, true);
                        networks.add(Network.builder(carver));
                    }
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
            // Skip bridged borders with tectonic uplift connection to adjacent Voronoi peak (prevents uphill rivers)
            if (voronoi.isTectonicActive[i]) {
                continue;
            }

            // Skip thin sliver sectors (< 20 degrees)
            if (PlateUpliftBridge.isSectorTooThin(voronoi.lo[i], voronoi.hi[i], voronoi.len[i])) {
                continue;
            }

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

        float frac = riverCount / 30.0F;
        if (r == 0) {
            // Centroid peak tier: moderate spawn chance scaled with river count (not every face starts at the peak)
            return frac * 1.1F;
        }

        // Upper tiers spawn major tributaries; lower tiers spawn smaller feeds hooking in
        float elevWeight = (float) (rowCount - 1 - r) / (float) (rowCount - 1);
        float baseRate = 0.08F + 0.22F * elevWeight;

        float nonLinearFrac = (float) Math.pow(frac, 1.3);
        if (frac > 0.7F) {
            float t = (frac - 0.7F) / 0.3F;
            return baseRate * nonLinearFrac * (1.0F - t) + 1.0F * t;
        }
        return baseRate * nonLinearFrac * 1.8F;
    }

    /**
     * Finds the closest two candidate nodes on the row below BEFORE any ocean culling.
     * Ensures downstream routing only ever considers immediately adjacent downhill slots
     * and never jumps laterally across the continent.
     */
    private DrainageNode[] findClosestTwoDownstreamNodes(DrainageNode node, List<DrainageNode> nextRow) {
        if (nextRow == null || nextRow.isEmpty()) {
            return new DrainageNode[0];
        }
        if (nextRow.size() == 1) {
            return new DrainageNode[]{nextRow.get(0)};
        }
        DrainageNode best1 = null;
        DrainageNode best2 = null;
        float dist1 = Float.MAX_VALUE;
        float dist2 = Float.MAX_VALUE;
        for (DrainageNode cand : nextRow) {
            float dx = cand.x - node.x;
            float dz = cand.z - node.z;
            float dSq = dx * dx + dz * dz;
            if (dSq < dist1) {
                dist2 = dist1;
                best2 = best1;
                dist1 = dSq;
                best1 = cand;
            } else if (dSq < dist2) {
                dist2 = dSq;
                best2 = cand;
            }
        }
        return new DrainageNode[]{best1, best2};
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

        // Row count strictly matches ContinentalHydrology terracing step counts (NUM_STEPS = 15)
        int rowCount = ContinentalHydrology.NUM_STEPS;

        // Target node spacing along each row (50% denser than previous 350.0F baseline -> ~225.0F)
        float targetSpacing = 225.0F;

        // 1. Generate candidate rows and nodes for all 15 ContinentalHydrology terraces
        for (int r = 0; r < rowCount; r++) {
            // Map row 0 to top plateau (index 14, ~0.942) down to row 14 at coastal base (index 0, ~0.030)
            int plateauIndex = (allPlateaus.length - 2) - r;
            float u = allPlateaus[plateauIndex];
            float t = 1.0F - u;

            // Row endpoints across the face triangle
            float leftX = (1.0F - t) * apexX + t * v1X;
            float leftZ = (1.0F - t) * apexZ + t * v1Z;
            float rightX = (1.0F - t) * apexX + t * v2X;
            float rightZ = (1.0F - t) * apexZ + t * v2Z;

            float rowWidth = t * baseWidth;

            // Increased row subdivision count (by 50%): ~225 blocks spacing, monotonically non-decreasing
            int nodeCount = Math.max(1, Math.round(rowWidth / targetSpacing));
            if (r > 0) {
                nodeCount = Math.max(graph.rows.get(r - 1).size(), nodeCount);
            }
            nodeCount = Math.min(32, nodeCount);

            List<DrainageNode> rowNodes = new ArrayList<>(nodeCount);
            for (int col = 0; col < nodeCount; col++) {
                float s = (2.0F * col + 1.0F) / (2.0F * (float) nodeCount);

                // Small organic jitter (up to 12% of node spacing) while strictly staying in sector
                float jitter = (faceRandom.nextFloat() - 0.5F) * (0.24F / (float) nodeCount);
                float clampedS = NoiseUtil.clamp(s + jitter, 0.02F, 0.98F);

                float nodeX = (1.0F - clampedS) * leftX + clampedS * rightX;
                float nodeZ = (1.0F - clampedS) * leftZ + clampedS * rightZ;

                DrainageNode node = new DrainageNode(nodeX, nodeZ, u, r, col);
                node.isMountain = this.isMountainTerrain(nodeX, nodeZ);

                // On bridged tectonic borders, cull rows near the border to ensure rivers never reach the actual border
                if (isBridged && r >= rowCount - 2) {
                    node.isCulled = true;
                }
                rowNodes.add(node);
            }
            graph.rows.add(rowNodes);
        }

        // 2. Identify and propagate culling for nodes below sea level or downstream of ocean
        for (int r = 0; r < rowCount; r++) {
            List<DrainageNode> curRow = graph.rows.get(r);
            List<DrainageNode> prevRow = (r > 0) ? graph.rows.get(r - 1) : null;

            for (DrainageNode node : curRow) {
                // Direct sea level check
                if (this.isBelowSeaLevel(node.x, node.z)) {
                    node.isCulled = true;
                    continue;
                }

                // Upstream candidate check (propagating top-down from row r-1)
                if (prevRow != null) {
                    DrainageNode nearestUpstream = null;
                    float minDistSq = Float.MAX_VALUE;
                    for (DrainageNode uNode : prevRow) {
                        float dx = uNode.x - node.x;
                        float dz = uNode.z - node.z;
                        float dSq = dx * dx + dz * dz;
                        if (dSq < minDistSq) {
                            minDistSq = dSq;
                            nearestUpstream = uNode;
                        }
                    }

                    if (nearestUpstream != null && nearestUpstream.isCulled) {
                        node.isCulled = true;
                    }
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

                    float effectiveSpawnChance = spawnChance;

                    if (riverCount < 30) {
                        // 1. If a node has a neighbour that is a river being fed by a parent, increase headwater likelihood by 50%
                        boolean hasFedNeighbour = (col > 0 && !curRow.get(col - 1).isCulled && curRow.get(col - 1).feederCount > 0)
                                               || (col < curCount - 1 && !curRow.get(col + 1).isCulled && curRow.get(col + 1).feederCount > 0);
                        if (hasFedNeighbour) {
                            effectiveSpawnChance = Math.min(1.0F, effectiveSpawnChance * 1.50F);
                        }

                        // 4. If a node is mountain terrain and both downstream nodes are mountain terrain, prevent headwater spawning 80% of the time
                        if (node.isMountain && nextRow != null && !nextRow.isEmpty()) {
                            DrainageNode[] closest = findClosestTwoDownstreamNodes(node, nextRow);
                            if (closest.length > 0) {
                                boolean allMtn = true;
                                for (DrainageNode cand : closest) {
                                    if (!cand.isMountain) {
                                        allMtn = false;
                                        break;
                                    }
                                }
                                if (allMtn) {
                                    effectiveSpawnChance *= 0.20F;
                                }
                            }
                        }
                    }

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

            // Intermediate rows: each active node only ever considers its closest two downhill nodes in nextRow (before culling)
            int lastTargetCol = -1;

            for (int i = 0; i < activeNodes.size(); i++) {
                DrainageNode node = activeNodes.get(i);
                boolean isHeadwater = (node.feederCount == 0);

                DrainageNode[] closest = findClosestTwoDownstreamNodes(node, nextRow);
                if (closest.length == 0) {
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

                DrainageNode cand1 = closest[0];
                DrainageNode cand2 = (closest.length > 1) ? closest[1] : cand1;

                // 1. If BOTH downstream candidates are culled (ocean margin or bridged border buffer reached directly ahead):
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

                // Position-seeded random choice between the candidates
                long connSeed = ((long) Float.floatToIntBits(node.x) * 7192831L)
                              ^ ((long) Float.floatToIntBits(node.z) * 4827103L)
                              ^ (faceSeed + (long) r * 37L + (long) node.col * 17L);
                Random connRandom = new Random(connSeed);

                // 2. If EXACTLY ONE downstream candidate is culled (e.g. one in ocean, one on land):
                if (cand1.isCulled || cand2.isCulled) {
                    if (!isBridged) {
                        // Open ocean face: encountering the ocean margin directly ahead means the river has reached the coast.
                        // Connect cleanly and directly out into the ocean rather than turning sideways along the coastline.
                        connectToOcean(graph, node, apexX, apexZ, faceSeed, rowCount);
                        continue;
                    }

                    DrainageNode validCand = cand1.isCulled ? cand2 : cand1;
                    // If connecting to the only valid land candidate crosses an earlier river on this terrace:
                    if (validCand.col < lastTargetCol) {
                        DrainageNode waterMouth = findWaterMouthNode(node, apexX, apexZ, v1X, v1Z, v2X, v2Z, isBridged, rowCount);
                        if (waterMouth != null) {
                            boolean isMain = (node.feederCount >= 2);
                            graph.addConnection(new RiverConnection(node, waterMouth, isHeadwater, isMain));
                        } else {
                            node.isLake = true;
                        }
                        continue;
                    }

                    // Mountain check on valid candidate: only empty headwaters can be stopped by mountain ridges
                    if (validCand.isMountain && node.feederCount == 0 && riverCount < 30) {
                        if (connRandom.nextFloat() < 0.80F) {
                            node.isLake = true;
                            continue;
                        }
                    }

                    lastTargetCol = validCand.col;
                    validCand.feederCount++;
                    validCand.isPopulated = true;
                    boolean isMain = (node.feederCount >= 2) || (r <= 2 && node.col == 0);
                    graph.addConnection(new RiverConnection(node, validCand, isHeadwater, isMain));
                    continue;
                }

                // 3. NEITHER candidate is culled (both are valid land directly downhill):
                boolean bothMountain = cand1.isMountain && cand2.isMountain;
                if (bothMountain && node.feederCount == 0 && riverCount < 30) {
                    if (connRandom.nextFloat() < 0.80F) {
                        node.isLake = true;
                        continue;
                    }
                }

                DrainageNode leftCand = (cand1.col <= cand2.col) ? cand1 : cand2;
                DrainageNode rightCand = (cand1.col <= cand2.col) ? cand2 : cand1;

                // Non-crossing check:
                if (rightCand.col < lastTargetCol) {
                    // Both candidates would cross an earlier river
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

                DrainageNode chosen;
                if (leftCand.col < lastTargetCol) {
                    // Left candidate crosses, only right candidate is valid
                    chosen = rightCand;
                } else {
                    // Both candidates are valid without crossing!
                    boolean fed1 = cand1.feederCount > 0;
                    boolean fed2 = cand2.feederCount > 0;
                    boolean fedDiff = (cand1 != cand2) && (fed1 ^ fed2);
                    boolean mtnDiff = (cand1 != cand2) && (cand1.isMountain ^ cand2.isMountain);

                    if (cand1 == cand2) {
                        chosen = cand1;
                    } else if (fedDiff && !mtnDiff) {
                        // Bias candidate with an upstream parent 80% of the time
                        DrainageNode fed = fed1 ? cand1 : cand2;
                        DrainageNode unfed = fed1 ? cand2 : cand1;
                        chosen = (connRandom.nextFloat() < 0.80F) ? fed : unfed;
                    } else if (mtnDiff && !fedDiff) {
                        // Favor non-mountain candidate 80% of the time
                        DrainageNode nonMtn = (!cand1.isMountain) ? cand1 : cand2;
                        DrainageNode mtn = (cand1.isMountain) ? cand1 : cand2;
                        chosen = (connRandom.nextFloat() < 0.80F) ? nonMtn : mtn;
                    } else if (fedDiff && mtnDiff) {
                        // Both apply: combined scoring
                        float score1 = 1.0F;
                        float score2 = 1.0F;
                        if (!cand1.isMountain) score1 *= 4.0F; else score2 *= 4.0F;
                        if (fed1) score1 *= 4.0F; else score2 *= 4.0F;
                        float p1 = score1 / (score1 + score2);
                        chosen = (connRandom.nextFloat() < p1) ? cand1 : cand2;
                    } else {
                        // Base organic meandering (55% closer candidate, 45% second-nearest)
                        chosen = (connRandom.nextFloat() < 0.55F) ? cand1 : cand2;
                    }
                }

                lastTargetCol = chosen.col;
                chosen.feederCount++;
                chosen.isPopulated = true;
                boolean isMain = (node.feederCount >= 2) || (r <= 2 && node.col == 0);
                graph.addConnection(new RiverConnection(node, chosen, isHeadwater, isMain));
            }
        }

        // 4. Unconnected, non-mountain nodes have a chance to spawn lakes based on preset lake chance
        float lakeChance = (this.lake != null) ? this.lake.chance : 0.25F;
        if (lakeChance > 0.0F && riverCount < 30) {
            for (int r = 0; r < rowCount; r++) {
                List<DrainageNode> row = graph.rows.get(r);
                for (int col = 0; col < row.size(); col++) {
                    DrainageNode node = row.get(col);
                    if (!node.isCulled && !node.isPopulated && node.feederCount == 0 && !node.isMountain) {
                        long lakeSeed = ((long) Float.floatToIntBits(node.x) * 8192837L)
                                      ^ ((long) Float.floatToIntBits(node.z) * 1928371L)
                                      ^ (faceSeed + (long) r * 2003L + (long) col * 17L);
                        Random lakeRandom = new Random(lakeSeed);
                        if (lakeRandom.nextFloat() < lakeChance) {
                            node.isLake = true;
                        }
                    }
                }
            }
        }

        // 5. Laterally connect adjacent lake nodes on the same row with a river
        // (safe to do since they share the same uplift step plateau in ContinentalHydrology)
        if (riverCount < 30) {
            for (int r = 0; r < rowCount; r++) {
                List<DrainageNode> row = graph.rows.get(r);
                int rowSize = row.size();
                for (int col = 0; col < rowSize - 1; col++) {
                    DrainageNode nodeA = row.get(col);
                    DrainageNode nodeB = row.get(col + 1);

                    if (nodeA.isLake && nodeB.isLake) {
                        // Both adjacent nodes are lakes on the same terrace plateau
                        if (!graph.outgoing.containsKey(nodeA)) {
                            nodeA.isPopulated = true;
                            nodeB.isPopulated = true;
                            nodeB.feederCount++;

                            boolean isHeadwater = (nodeA.feederCount == 0);
                            boolean isMain = false;
                            graph.addConnection(new RiverConnection(nodeA, nodeB, isHeadwater, isMain, true));
                        }
                    }
                }
            }
        }

        return graph;
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
            // Fallback if ocean margin was unusually distant
            mouthDist = Math.min(maxOceanDist, 2000.0F);
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
