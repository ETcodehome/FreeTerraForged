package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import etcodehome.freeterraforged.world.worldgen.cell.continent.Continent;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.wetland.Wetland;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.wetland.WetlandConfig;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import etcodehome.freeterraforged.world.worldgen.util.Variance;
import etcodehome.freeterraforged.concurrent.Resource;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.RiverGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;

public abstract class BaseRiverGenerator<T extends Continent> implements RiverGenerator {
    protected int count;
    protected int continentScale;
    protected float minEdgeValue;
    protected int seed;
    protected LakeConfig lake;
    protected RiverConfig main;
    protected RiverConfig fork;
    protected WetlandConfig wetland;
    protected T continent;
    protected Levels levels;
    /** True when the continent can bridge cells together (UPLIFT), so cell borders may be dry land instead of ocean. */
    protected final boolean bridgeAware;
    protected final float shallowOcean;
    /** Forks share one fixed water level taken from their junction (only meaningful with uplift hydrology). */
    protected final boolean fixedForkLevels;

    public BaseRiverGenerator(T continent, GeneratorContext context) {
        this.continent = continent;
        this.levels = context.levels;
        this.continentScale = context.preset.world().continent.continentScale;
        this.minEdgeValue = context.preset.world().controlPoints.inland;
        this.shallowOcean = context.preset.world().controlPoints.shallowOcean;
        this.bridgeAware = continent instanceof UpliftContinentGenerator;
        this.fixedForkLevels = continent instanceof UpliftContinentGenerator;
        this.seed = Seed.toInt(context.seed.root() + context.preset.rivers().seedOffset);
        this.count = context.preset.rivers().riverCount;
        this.main = RiverConfig.builder(context.levels).bankHeight(context.preset.rivers().mainRivers.minBankHeight, context.preset.rivers().mainRivers.maxBankHeight).bankWidth(context.preset.rivers().mainRivers.bankWidth).bedWidth(context.preset.rivers().mainRivers.bedWidth).bedDepth(context.preset.rivers().mainRivers.bedDepth).fade(context.preset.rivers().mainRivers.fade).length(5000).main(true).order(0).build();
        this.fork = RiverConfig.builder(context.levels).bankHeight(context.preset.rivers().branchRivers.minBankHeight, context.preset.rivers().branchRivers.maxBankHeight).bankWidth(context.preset.rivers().branchRivers.bankWidth).bedWidth(context.preset.rivers().branchRivers.bedWidth).bedDepth(context.preset.rivers().branchRivers.bedDepth).fade(context.preset.rivers().branchRivers.fade).length(4500).order(1).build();
        this.wetland = new WetlandConfig(context.preset.rivers().wetlands);
        this.lake = LakeConfig.of(context.preset.rivers().lakes, context.levels);
    }

    @Override
    public Rivermap generateRivers(int x, int z, long id) {

        // Generate the river warp to use
        GenWarp warp = GenWarp.make((int) id, this.continentScale);

        // early exit guard to return if continent is skipped to prevent rivers spawning in the oceans
        if (this.continent.getEdgeValue(x, z) < this.minEdgeValue) {
            return new Rivermap(x, z, new Network[0], warp);
        }

        // seed the rivers uniquely per continent
        Random random = new Random(id + this.seed);
        List<Network.Builder> rivers = this.generateRoots(x, z, random, warp);

        Collections.shuffle(rivers, random);
        for (Network.Builder root : rivers) {
            this.generateForks(root, River.MAIN_SPACING, this.fork, random, warp, rivers, 0);
        }
        for (Network.Builder river : rivers) {
            this.generateWetlands(river, random);
        }
        Network[] networks = rivers.stream().map(Network.Builder::build).toArray(Network[]::new);
        return new Rivermap(x, z, networks, warp);
    }

    public List<Network.Builder> generateRoots(int x, int z, Random random, GenWarp warp) {
        return Collections.emptyList();
    }

    public void generateForks(Network.Builder parent, Variance spacing, RiverConfig config, Random random, GenWarp warp, List<Network.Builder> rivers, int depth) {
        if (depth > 2) {
            return;
        }
        float length = 0.44F * parent.carver.getRiver().length;
        if (length < 300.0f) {
            return;
        }
        int direction = random.nextBoolean() ? 1 : -1;
        for (float offset = 0.25F; offset < 0.9f; offset += spacing.next(random)) {
            for (boolean attempt = true; attempt; attempt = false) {
                direction = -direction;
                float parentAngle = parent.carver.getRiver().getAngle();
                float forkAngle = direction * 6.2831855F * River.FORK_ANGLE.next(random);
                float angle = parentAngle + forkAngle;
                float dx = NoiseUtil.sin(angle);
                float dz = NoiseUtil.cos(angle);
                long v1 = parent.carver.getRiver().pos(offset);
                float x1 = PosUtil.unpackLeftf(v1);
                float z1 = PosUtil.unpackRightf(v1);
                if (this.continent.getEdgeValue(x1, z1) >= this.minEdgeValue) {
                    float x2 = x1 - dx * length;
                    float z2 = z1 - dz * length;
                    if (this.continent.getEdgeValue(x2, z2) >= this.minEdgeValue && !this.endsInNeighbourCell(x1, z1, x2, z2)) {
                        RiverConfig forkConfig = parent.carver.createForkConfig(offset, this.levels);
                        River river = new River(x2, z2, x1, z1);
                        if (!this.riverOverlaps(river, parent, rivers)) {
                            float valleyWidth = 275.0f * River.FORK_VALLEY.next(random);
                            RiverCarverSettings settings = new RiverCarverSettings(random);
                            settings.connecting = true;
                            settings.fadeIn = config.fade;
                            settings.valleySize = valleyWidth;
                            settings.fixedWaterOffset = this.resolveForkWaterOffset(parent, x1, z1);
                            settings.junctionFreeRadius = parent.carver.getFootprintRadius();
                            RiverWarp forkWarp = parent.carver.getWarp().createChild(0.15f, 0.75f, 0.65f, random);
                            FTFRiverCarver fork = new UpliftRiverCarver(river, forkWarp, forkConfig, settings, this.levels, this.lake, this.continent instanceof UpliftContinentGenerator);
                            Network.Builder builder = Network.builder(fork);
                            parent.children.add(builder);
                            this.generateForks(builder, River.FORK_SPACING, config, random, warp, rivers, depth + 1);
                        }
                    }
                }
            }
        }
    }

    /**
     * A river end that is still on land is only possible where the ray reached a cell border without hitting the coast,
     * i.e. the border is bridged to a neighbouring continent. The rivermap is only applied inside this continent's own
     * cell, so such a river would stop dead in the middle of the merged landmass.
     */
    protected boolean terminatesOnLand(float x, float z) {
        return this.bridgeAware && this.continent.getEdgeValue(x, z) > this.shallowOcean;
    }

    /**
     * True if a fork's upstream end lies in a different cell than its junction point, which (with the edge check
     * already passed) means it crossed a bridged border and would be cut off at the boundary.
     */
    protected boolean endsInNeighbourCell(float x1, float z1, float x2, float z2) {
        return this.bridgeAware && this.continent.getNearestCenter(x2, z2) != this.continent.getNearestCenter(x1, z1);
    }

    /**
     * The first fork off a main river samples the continental hydrology water offset at its junction once;
     * every descendant fork inherits that same value instead of re-deriving it per cell.
     */
    protected float resolveForkWaterOffset(Network.Builder parent, float junctionX, float junctionZ) {
        if (!this.fixedForkLevels) {
            return Float.NaN;
        }
        float inherited = parent.carver.getFixedWaterOffset();
        if (!Float.isNaN(inherited)) {
            return inherited;
        }
        return this.sampleWaterOffset(junctionX, junctionZ);
    }

    protected float sampleWaterOffset(float x, float z) {
        try (Resource<Cell> resource = Cell.getResource()) {
            Cell cell = resource.get();
            this.continent.apply(cell, x, z);
            return ContinentalHydrology.getComplexWaterHeight(cell.waterTable, this.continentScale, cell.continentSizeModifier);
        }
    }

    public void generateWetlands(Network.Builder builder, Random random) {
        int skip = random.nextInt(this.wetland.skipSize);
        if (skip == 0) {
            float width = this.wetland.width.next(random);
            float length = this.wetland.length.next(random);
            float riverLength = builder.carver.getRiver().length();
            float startPos = random.nextFloat() * 0.75f;
            float endPos = startPos + random.nextFloat() * (length / riverLength);
            long start = builder.carver.getRiver().pos(startPos);
            long end = builder.carver.getRiver().pos(endPos);
            float x1 = PosUtil.unpackLeftf(start);
            float z1 = PosUtil.unpackRightf(start);
            float x2 = PosUtil.unpackLeftf(end);
            float z2 = PosUtil.unpackRightf(end);
            builder.wetlands.add(new Wetland(random.nextInt(), new NoiseUtil.Vec2f(x1, z1), new NoiseUtil.Vec2f(x2, z2), width, this.levels));
        }
        for (Network.Builder child : builder.children) {
            this.generateWetlands(child, random);
        }
    }

    public boolean riverOverlaps(River river, Network.Builder parent, List<Network.Builder> rivers) {
        for (Network.Builder other : rivers) {
            if (other.overlaps(river, parent, 250.0f)) {
                return true;
            }
        }
        return false;
    }

}