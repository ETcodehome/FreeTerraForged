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

        // Hold the central water channel at original configuration size
        this.bedWidth = new Range(0.25F, (float)(config.bedWidth * config.bedWidth));

        // Scale the erosion influence area out 3x wider (Radius * 3 means Width Sq * 9)
        float erosionScale = 3.0F;
        float sqErosionScale = erosionScale * erosionScale;

        this.banksWidth = new Range(1.5625F * sqErosionScale, (float)(config.bankWidth * config.bankWidth) * sqErosionScale);

        float expandedValley = settings.valleySize * erosionScale;
        this.valleyWidth = new Range(expandedValley * expandedValley, expandedValley * expandedValley);

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

        // Calculate Flatness Factor from the Continental Hydrology profile
        float flatnessFactor = ContinentalHydrology.getFlatnessFactor(cell.waterTable);
        flatnessFactor = NoiseUtil.clamp(flatnessFactor, 0.0F, 1.0F);

        // Compute the dynamic scale factors (Linear max width means squaring the linear scale)
        float wideningMidStep = 0.75F;
        float linearScale = 1.0F + (wideningMidStep * flatnessFactor);
        float sqScaleFactor = linearScale * linearScale;

        // Valley Alpha (Metadata blending) - Dynamically scaled by current flatness
        float valleyInfluence = this.getDistanceAlpha(currT, Math.min(distSqToCurr, distSqToPrev), this.valleyWidth, sqScaleFactor);
        if (valleyInfluence > 0.0F) {
            valleyInfluence = this.valleyCurve.apply(valleyInfluence);
            cell.riverMask = Math.min(cell.riverMask, 1.0F - valleyInfluence);
        }

        // Base Elevations & Target Floor (Matches original depth definitions)
        float oceanHeightOffset = levels.water;
        float bedDepthOffset = oceanHeightOffset - config.bedHeight;
        float targetBedFloor = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) - bedDepthOffset + oceanHeightOffset;
        float targetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanHeightOffset;

        // True Linear Distance System
        float currentLinearDist = (float) Math.sqrt(distSqToCurr);
        float maxBankSize = this.getScaledSize(currT, this.banksWidth) * sqScaleFactor;
        float bankRadius = (float) Math.sqrt(maxBankSize);

        float maxBedSize = this.getScaledSize(currT, this.bedWidth) * sqScaleFactor;
        float bedRadius = (float) Math.sqrt(maxBedSize);

        // Escape immediately if we are outside the expanded erosion zone
        if (currentLinearDist >= bankRadius) return;

        // Track if we are inside the core water channel
        boolean isInsideChannel = currentLinearDist < bedRadius;

        // Banks Stage (Only runs OUTSIDE the core water channel)
        if (!isInsideChannel) {

            // How far inside the bank erosion zone are we? (0.0 at mountain edge, 1.0 at water's edge)
            float bankProgress = (bankRadius - currentLinearDist) / (bankRadius - bedRadius);
            bankProgress = NoiseUtil.clamp(bankProgress, 0.0F, 1.0F);

            // High-frequency micro-perturbation to make bank borders jagged.
            float microNoise = (float) Math.sin(currX * 0.4F) * (float) Math.cos(currZ * 0.4F);

            // Multiply by (1.0F - bankProgress) so the distortion fades to zero at the river's edge,
            // preventing the distance field from compressing and creating steep walls.
            float lateralWarp = microNoise * 0.08F * (1.0F - bankProgress);
            float roughBankProgress = NoiseUtil.clamp(bankProgress + lateralWarp, 0.0F, 1.0F);

            // Cubic smoothstep using the roughened progress
            float smoothBankAlpha = roughBankProgress * roughBankProgress * (3.0F - 2.0F * roughBankProgress);

            // Runoff Gully Noise Component
            float gullyFreq = 0.05F;
            float nx = currX * gullyFreq;
            float nz = currZ * gullyFreq;
            float noiseSample = (float) Math.sin(nx + Math.cos(nz)) * (float) Math.cos(nz - Math.sin(nx));
            float gullyEffect = 1.0F - Math.abs(noiseSample);
            gullyEffect = gullyEffect * gullyEffect; // Sharpen gully channels

            // (Terracing / Micro-slopes)
            // High frequency layered noise to add small ledge textures inside the valley walls
            float detailFreq = 0.25F;
            float detailSample = (float) Math.sin(currX * detailFreq) * (float) Math.sin(currZ * detailFreq);
            // Modulate gully effect so it feels rocky and broken rather than a smooth slide
            float roughGullyEffect = NoiseUtil.lerp(gullyEffect, gullyEffect * (0.8F + detailSample * 0.2F), bankProgress);

            // Shoreline Taper, fade out the noise completely as it approaches the water's edge
            float noiseWeight = bankProgress * bankProgress;
            float finalErosionInfluence = smoothBankAlpha * NoiseUtil.lerp(roughGullyEffect, 1.0F, noiseWeight);

            // To protect water width, the bank interpolates toward the shoreline height not the deep river floor
            float rawCarvedHeight = NoiseUtil.lerp(cell.height, targetWaterLevel, finalErosionInfluence);

            // Calculate allowed drop relative to the uncarved mountain edge boundary using PURE physical distance
            float horizontalDistanceFromBoundary = bankRadius - currentLinearDist;

            // Lowered the base slope factor to push the valley walls back.
            // We keep a tiny variation (0.05F) to keep it looking natural, but its maximum limit is heavily restricted.
            float dynamicSlopeFactor = 0.55F + (detailSample * 0.05F);
            float maxAllowableDrop = horizontalDistanceFromBoundary * dynamicSlopeFactor;
            float slopeLimitedHeight = cell.height - maxAllowableDrop;

            // Enforce the slope cap relative to the natural hill profile
            float finalBankHeight = Math.max(rawCarvedHeight, slopeLimitedHeight);

            if (finalBankHeight < cell.height) {
                cell.height = finalBankHeight;

                // If a deep gully dips below water level it should just use the min.
                if (cell.height < targetWaterLevel) {
                    cell.height = targetWaterLevel;
                }
            }

        } else {
            setHeightRiverInternals(cell, currT, distSqToCurr, targetBedFloor, oceanHeightOffset, bedDepthOffset, sqScaleFactor);
        }
    }

    public void setHeightRiverInternals(Cell cell, float currT, float distSqToCurr, float targetBedFloor, float oceanHeightOffset, float bedDepthOffset, float sqScaleFactor){
        float bedInfluence = this.getDistanceAlpha(currT, distSqToCurr, this.bedWidth, sqScaleFactor);
        cell.height = Math.min(cell.height, ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) - (bedDepthOffset * bedInfluence) + oceanHeightOffset);
        cell.moisture = 1.0F;
        this.tag(cell, targetBedFloor);
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

    private float getDistanceAlpha(float t, float dist2, Range range, float sqScaleFactor) {
        float size2 = this.getScaledSize(t, range) * sqScaleFactor;
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
        if (cell.terrain.isLake()){
            return;
        }

        cell.erosionMask = true;
        cell.terrain = TerrainType.RIVER;

        float newMax = Math.max(this.waterLine, bedHeight);
        if (newMax > cell.riverWaterLevel) {
            cell.riverWaterLevel = Math.max(this.waterLine, bedHeight);
        }
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