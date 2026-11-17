package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.continent.SimpleContinent;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.PlateUpliftBridge;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;

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

        public DrainageNode(float x, float z, float gradient, int row, int col) {
            this.x = x;
            this.z = z;
            this.gradient = gradient;
            this.row = row;
            this.col = col;
            this.feederCount = 0;
            this.isPopulated = false;
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

        public RiverConnection(DrainageNode from, DrainageNode to, boolean isHeadwater, boolean isMain) {
            this.from = from;
            this.to = to;
            this.isHeadwater = isHeadwater;
            this.isMain = isMain;
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
        public final List<List<DrainageNode>> rows = new ArrayList<>();
        public final List<RiverConnection> connections = new ArrayList<>();

        public FaceDrainageGraph(int faceIndex, float apexX, float apexZ, float v1X, float v1Z, float v2X, float v2Z) {
            this.faceIndex = faceIndex;
            this.apexX = apexX;
            this.apexZ = apexZ;
            this.v1X = v1X;
            this.v1Z = v1Z;
            this.v2X = v2X;
            this.v2Z = v2Z;
        }
    }

    public TriSubdivisionRiverGenerator(SimpleContinent continent, GeneratorContext context) {
        super(continent, context);
    }

    @Override
    public Rivermap generateRivers(int x, int z, long id) {
        GenWarp warp = GenWarp.make((int) id, this.continentScale);

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

        for (FaceDrainageGraph graph : graphs) {
            java.util.Map<RiverConnection, Network.Builder> builderMap = new java.util.IdentityHashMap<>();
            java.util.Map<DrainageNode, RiverConnection> outgoingMap = new java.util.IdentityHashMap<>();

            for (RiverConnection conn : graph.connections) {
                outgoingMap.put(conn.from, conn);

                RiverConfig config = conn.isMain ? this.main : this.fork;
                float boundingRadius = config.calculateBoundingRadius(this.levels);
                River river = new River(conn.from.x, conn.from.z, conn.to.x, conn.to.z, boundingRadius);

                RiverCarverSettings settings = new RiverCarverSettings(random);
                settings.fadeIn = conn.isHeadwater ? config.fade : 0.0F;
                settings.connecting = !conn.isHeadwater;
                settings.valleySize = 275.0F * (conn.isMain ? River.MAIN_VALLEY.next(random) : River.FORK_VALLEY.next(random));

                RiverWarp riverWarp = RiverWarp.create(0.1F, 0.85F, random);
                FTFRiverCarver carver = new UpliftRiverCarver(river, riverWarp, config, settings, this.levels, this.lake, true);

                builderMap.put(conn, Network.builder(carver));
            }

            // Wire upstream tributary feeders as children of their downstream parent builder
            for (RiverConnection conn : graph.connections) {
                Network.Builder curBuilder = builderMap.get(conn);
                RiverConnection downstream = outgoingMap.get(conn.to);

                if (downstream != null) {
                    Network.Builder downBuilder = builderMap.get(downstream);
                    downBuilder.children.add(curBuilder);
                } else {
                    // River mouth segment emptying into the ocean: top-level root
                    networks.add(curBuilder);
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
            // Skip bridged borders (connecting to adjacent landmasses)
            if (voronoi.isBridged[i]) {
                continue;
            }

            // Skip thin sliver sectors (< 20 degrees)
            if (PlateUpliftBridge.isSectorTooThin(voronoi.lo[i], voronoi.hi[i], voronoi.len[i])) {
                continue;
            }

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

            FaceDrainageGraph graph = buildFaceGraph(i, apexX, apexZ, v1X, v1Z, v2X, v2Z, faceAltitude, baseWidth, allPlateaus, faceRandom, faceSeed, riverCount);
            if (!graph.connections.isEmpty()) {
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
            // Centroid peak node has high chance to ignite the primary mountain river system
            return Math.min(0.95F, frac * 2.2F + 0.30F);
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
     * Subdivides a single face triangle into rows of plateau nodes and connects them into a non-crossing drainage tree.
     * Starts from top of the centroid and works down:
     * - If empty: spawns if spawn chance > position based roll, connecting to one of nearest two nodes in row below.
     * - If already connected to row above: connects to one of nearest two nodes in row below.
     */
    private FaceDrainageGraph buildFaceGraph(
            int faceIndex, float apexX, float apexZ, float v1X, float v1Z, float v2X, float v2Z,
            float faceAltitude, float baseWidth, float[] allPlateaus, Random faceRandom, long faceSeed, int riverCount
    ) {
        FaceDrainageGraph graph = new FaceDrainageGraph(faceIndex, apexX, apexZ, v1X, v1Z, v2X, v2Z);

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

                rowNodes.add(new DrainageNode(nodeX, nodeZ, u, r, col));
            }
            graph.rows.add(rowNodes);
        }

        // If riverCount is 0, no nodes are populated and zero connections are formed
        if (riverCount <= 0) {
            return graph;
        }

        // 2. Starting from top of the centroid and working down row by row
        for (int r = 0; r < rowCount; r++) {
            List<DrainageNode> curRow = graph.rows.get(r);
            int curCount = curRow.size();

            float spawnChance = getNodeSpawnChance(r, rowCount, riverCount);

            // Collect nodes in this row that will connect downward
            List<DrainageNode> activeNodes = new ArrayList<>();

            for (int col = 0; col < curCount; col++) {
                DrainageNode node = curRow.get(col);

                // Position-seeded roll for this specific candidate node
                long posSeed = ((long) Float.floatToIntBits(node.x) * 3129871L)
                             ^ ((long) Float.floatToIntBits(node.z) * 6182933L)
                             ^ (faceSeed + (long) r * 1013L + col);
                Random posRandom = new Random(posSeed);
                float positionBasedRoll = posRandom.nextFloat();

                if (node.feederCount == 0) {
                    // Empty node: check spawn chance > position based roll
                    if (spawnChance > positionBasedRoll) {
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

            // If at the bottom terrace (ocean baseline), connect populated nodes to ocean
            if (r == rowCount - 1) {
                for (DrainageNode bottomNode : activeNodes) {
                    boolean isHeadwater = (bottomNode.feederCount == 0);
                    float s = (2.0F * bottomNode.col + 1.0F) / (2.0F * (float) curCount);
                    float mouthX = (1.0F - s) * v1X + s * v2X;
                    float mouthZ = (1.0F - s) * v1Z + s * v2Z;

                    DrainageNode mouthNode = new DrainageNode(mouthX, mouthZ, 0.0F, rowCount, bottomNode.col);
                    mouthNode.isPopulated = true;
                    boolean isMain = (bottomNode.feederCount >= 2);
                    graph.connections.add(new RiverConnection(bottomNode, mouthNode, isHeadwater, isMain));
                }
                continue;
            }

            // Intermediate rows: connect to (position seeded random choice) one of nearest two nodes in row below
            List<DrainageNode> nextRow = graph.rows.get(r + 1);
            int nextCount = nextRow.size();

            int lastTargetCol = 0;

            for (int i = 0; i < activeNodes.size(); i++) {
                DrainageNode node = activeNodes.get(i);
                boolean isHeadwater = (node.feederCount == 0);

                // Find nearest two nodes in row below by Euclidean distance
                float bestDist1 = Float.MAX_VALUE;
                float bestDist2 = Float.MAX_VALUE;
                int nearCol1 = 0;
                int nearCol2 = 0;

                for (int k = 0; k < nextCount; k++) {
                    DrainageNode candidate = nextRow.get(k);
                    float dx = candidate.x - node.x;
                    float dz = candidate.z - node.z;
                    float dSq = dx * dx + dz * dz;

                    if (dSq < bestDist1) {
                        bestDist2 = bestDist1;
                        nearCol2 = nearCol1;
                        bestDist1 = dSq;
                        nearCol1 = k;
                    } else if (dSq < bestDist2) {
                        bestDist2 = dSq;
                        nearCol2 = k;
                    }
                }

                if (nextCount == 1) {
                    nearCol2 = nearCol1;
                }

                // Position-seeded random choice between the nearest two nodes
                long connSeed = ((long) Float.floatToIntBits(node.x) * 7192831L)
                              ^ ((long) Float.floatToIntBits(node.z) * 4827103L)
                              ^ (faceSeed + (long) r * 37L + (long) node.col * 17L);
                Random connRandom = new Random(connSeed);

                // Choose between the two nearest nodes (55% closer node, 45% second-nearest for lateral organic meandering)
                int chosenCol = (connRandom.nextFloat() < 0.55F) ? nearCol1 : nearCol2;

                // Enforce non-crossing constraint while allowing confluences (merging into the same node)
                int minCol = (i == 0) ? 0 : lastTargetCol;
                int targetCol = Math.max(minCol, chosenCol);
                targetCol = Math.min(nextCount - 1, targetCol);
                lastTargetCol = targetCol;

                DrainageNode targetNode = nextRow.get(targetCol);
                targetNode.feederCount++;
                targetNode.isPopulated = true;

                boolean isMain = (node.feederCount >= 2) || (r <= 2 && node.col == 0);
                graph.connections.add(new RiverConnection(node, targetNode, isHeadwater, isMain));
            }
        }

        return graph;
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
