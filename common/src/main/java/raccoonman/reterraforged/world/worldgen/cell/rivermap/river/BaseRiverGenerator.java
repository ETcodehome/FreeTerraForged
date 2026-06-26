package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.RiverGenerator;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.wetland.Wetland;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.wetland.WetlandConfig;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import raccoonman.reterraforged.world.worldgen.util.Variance;

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
    
    public BaseRiverGenerator(T continent, GeneratorContext context) {
        this.continent = continent;
        this.levels = context.levels;
        this.continentScale = context.preset.world().continent.continentScale;
        this.minEdgeValue = context.preset.world().controlPoints.inland;
        this.seed = context.seed.root() + context.preset.rivers().seedOffset;
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

    protected float getWaterTable(float x, float z) {
        if (this.continent instanceof UpliftContinentGenerator upliftGenerator) {
            return upliftGenerator.getWaterTable(x, z);
        }
        // Fallback value for other continent types that don't utilize a custom water table property
        return 0.0F;
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
                    if (this.continent.getEdgeValue(x2, z2) >= this.minEdgeValue) {
                        RiverConfig forkConfig = parent.carver.createForkConfig(offset, this.levels);
                        River river = new River(x2, z2, x1, z1);
                        if (!this.riverOverlaps(river, parent, rivers)) {
                            float valleyWidth = 275.0f * River.FORK_VALLEY.next(random);
                            RiverCarverSettings settings = new RiverCarverSettings(random);
                            settings.connecting = true;
                            settings.fadeIn = config.fade;
                            settings.valleySize = valleyWidth;
                            RiverWarp forkWarp = parent.carver.getWarp().createChild(0.15f, 0.75f, 0.65f, random);

                            // Sample the water table exactly at the parent junction point (x1, z1)
                            float junctionWaterTable = this.getWaterTable(x1, z1) + levels.water;

                            // Pass junctionWaterTable as the final argument
                            RTFRiverCarver fork = new UpliftRiverCarver(river, forkWarp, forkConfig, settings, this.levels, this.lake, this.continent instanceof UpliftContinentGenerator, junctionWaterTable);
                            Network.Builder builder = Network.builder(fork);
                            parent.children.add(builder);
                            this.generateForks(builder, River.FORK_SPACING, config, random, warp, rivers, depth + 1);
                        }
                    }
                }
            }
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
            builder.wetlands.add(new Wetland(random.nextInt(), new Vec2f(x1, z1), new Vec2f(x2, z2), width, this.levels));
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
