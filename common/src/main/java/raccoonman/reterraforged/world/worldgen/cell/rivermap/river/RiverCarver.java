package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import java.util.Random;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunctions;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;

public class RiverCarver implements Comparable<RiverCarver> {
    public boolean main;
    private boolean connecting;
    private float fade;
    private float fadeInv;
    private Range bedWidth;
    private Range banksWidth;
    private Range valleyWidth;
    private Range bedDepth;
    private Range banksDepth;
    private float waterLine;
    public River river;
    public RiverWarp warp;
    public RiverConfig config;
    public CurveFunction valleyCurve;
    private Levels levels;
    
    public RiverCarver(River river, RiverWarp warp, RiverConfig config, Settings settings, Levels levels) {
        this.fade = settings.fadeIn;
        this.fadeInv = 1.0F / settings.fadeIn;
        this.bedWidth = new Range(0.25F, (float)(config.bedWidth * config.bedWidth));
        this.banksWidth = new Range(1.5625F, (float)(config.bankWidth * config.bankWidth));
        this.valleyWidth = new Range(settings.valleySize * settings.valleySize, settings.valleySize * settings.valleySize);
        this.river = river;
        this.warp = warp;
        this.config = config;
        this.main = config.main;
        this.connecting = settings.connecting;
        this.waterLine = levels.water;
        this.bedDepth = new Range(levels.water, config.bedHeight);
        this.banksDepth = new Range(config.minBankHeight, config.maxBankHeight);
        this.valleyCurve = settings.valleyCurve;
        this.levels = levels;
    }

    @Override
    public int compareTo(RiverCarver o) {
        return Integer.compare(this.config.order, o.config.order);
    }

    public void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT) {
        float distSqToCurr = this.getDistance2(currX, currZ, currT);
        float distSqToPrev = this.getDistance2(prevX, prevZ, prevT);

        // 1. Valley Alpha
        float valleyInfluence = this.getDistanceAlpha(currT, Math.min(distSqToCurr, distSqToPrev), this.valleyWidth);
        if (valleyInfluence == 0.0F) return;

        valleyInfluence = this.valleyCurve.apply(valleyInfluence);
        cell.riverMask = Math.min(cell.riverMask, 1.0F - valleyInfluence);

        float oceanHeightOffset = levels.water;
        float bedDepthOffset = oceanHeightOffset - config.bedHeight; // difference between default waterlevel and bed depth relative to ocean level;
        float targetBedFloor = ContinentalHydrology.getWeightedWaterHeight(cell.continentUplift) - bedDepthOffset + oceanHeightOffset;

        // 3. Banks Stage
        float mouthModifier = getMouthModifier(cell);
        float bankInfluence = this.getDistanceAlpha(currT, distSqToCurr * mouthModifier, this.banksWidth);

        if (bankInfluence > 0.0F) {
            float carvedHeight = NoiseUtil.lerp(cell.height, targetBedFloor, bankInfluence);
            cell.height = Math.min(carvedHeight, cell.height);
            this.tag(cell, targetBedFloor);
        }

        // 4. Bed Stage
        float bedInfluence = this.getDistanceAlpha(currT, distSqToCurr, this.bedWidth);
        if (bedInfluence > 0.0F) {
            cell.moisture = 1.0f;
            cell.terrain = TerrainType.RIVER;
            cell.height = Math.min(NoiseUtil.lerp(cell.height, targetBedFloor, bedInfluence), cell.height);
            this.tag(cell, targetBedFloor);
        }
    }
    
    public RiverConfig createForkConfig(float t, Levels levels) {
        int bedHeight = levels.scale(this.getScaledSize(t, this.bedDepth));
        int bedWidth = (int)Math.round(Math.sqrt(this.getScaledSize(t, this.bedWidth)) * 0.75);
        int bankWidth = (int)Math.round(Math.sqrt(this.getScaledSize(t, this.banksWidth)) * 0.75);
        bedWidth = Math.max(1, bedWidth);
        bankWidth = Math.max(bedWidth + 1, bankWidth);
        return this.config.createFork(bedHeight, bedWidth, bankWidth, levels);
    }
    
    private float getDistance2(float x, float y, float t) {
        if (t <= 0.0F) {
            return Line.distSq(x, y, this.river.x1, this.river.z1);
        }
        if (t >= 1.0F) {
            return Line.distSq(x, y, this.river.x2, this.river.z2);
        }
        float px = this.river.x1 + t * this.river.dx;
        float py = this.river.z1 + t * this.river.dz;
        return Line.distSq(x, y, px, py);
    }
    
    private float getDistanceAlpha(float t, float dist2, Range range) {
        float size2 = this.getScaledSize(t, range);
        if (dist2 >= size2) {
            return 0.0F;
        }
        return 1.0F - dist2 / size2;
    }
    
    private float getScaledSize(float t, Range range) {
        if (t < 0.0F) {
            return range.min();
        }
        if (t > 1.0F) {
            return range.max();
        }
        if (range.min() == range.max()) {
            return range.min();
        }
        if (t >= this.fade) {
            return range.max();
        }
        return NoiseUtil.lerp(range.min(), range.max(), t * this.fadeInv);
    }

    private void tag(Cell cell, float bedHeight) {
        cell.erosionMask = true;
        cell.terrain = TerrainType.RIVER;
        cell.riverWaterLevel = Math.max(this.waterLine, bedHeight);
    }
    
    private static float getMouthModifier(Cell cell) {
        float modifier = NoiseUtil.map(cell.continentEdge, 0.0F, 0.5F, 0.5F);
        modifier *= modifier;
        return modifier;
    }
    
    public static CurveFunction getValleyType(Random random) {
        int value = random.nextInt(100);
        if (value < 5) {
            return CurveFunctions.scurve(0.4F, 1.0F);
        }
        if (value < 30) {
            return CurveFunctions.scurve(4.0F, 5.0F);
        }
        if (value < 50) {
            return CurveFunctions.scurve(3.0F, 0.25F);
        }
        return CurveFunctions.scurve(2.0F, -0.5F);
    }
    
    public static RiverCarver create(float x1, float z1, float x2, float z2, RiverConfig config, Levels levels, Random random) {
        River river = new River(x1, z1, x2, z2);
        RiverWarp warp = RiverWarp.create(0.35F, random);
        float valleyWidth = 275.0F * River.MAIN_VALLEY.next(random);
        Settings settings = creatSettings(random);
        settings.connecting = false;
        settings.fadeIn = config.fade;
        settings.valleySize = valleyWidth;
        return new RiverCarver(river, warp, config, settings, levels);
    }
    
    private static Settings creatSettings(Random random) {
        Settings settings = new Settings();
        settings.valleyCurve = getValleyType(random);
        return settings;
    }
    
    public static class Settings {
        public float valleySize;
        public float fadeIn;
        public boolean connecting;
        public CurveFunction valleyCurve;
        
        public Settings() {
            this.valleySize = 275.0F;
            this.fadeIn = 0.7F;
            this.connecting = false;
            this.valleyCurve = CurveFunctions.scurve(2.0F, -0.5F);
        }
    }
}
