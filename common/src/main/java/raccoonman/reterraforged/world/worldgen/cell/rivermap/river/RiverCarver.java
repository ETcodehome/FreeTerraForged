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

        // Scale the erosion influence area out 3.5x wider to allow wide, rolling valley bowls
        float erosionScale = 3.5F;
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

        // Compute the dynamic scale factors
        float wideningMidStep = 0.75F;
        float linearScale = 1.0F + (wideningMidStep * flatnessFactor);
        float sqScaleFactor = linearScale * linearScale;

        // Valley Alpha (Metadata blending only)
        float valleyInfluence = this.getDistanceAlpha(currT, Math.min(distSqToCurr, distSqToPrev), this.valleyWidth, sqScaleFactor);
        if (valleyInfluence > 0.0F) {
            valleyInfluence = this.valleyCurve.apply(valleyInfluence);
            cell.riverMask = Math.min(cell.riverMask, 1.0F - valleyInfluence);
        }

        // Base Elevations & Target Floor
        float oceanHeightOffset = levels.water;
        float bedDepthOffset = oceanHeightOffset - config.bedHeight;
        float targetBedFloor = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) - bedDepthOffset + oceanHeightOffset;
        float targetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanHeightOffset;

        // Core distance carving metrics
        float currentLinearDist = (float) Math.sqrt(distSqToCurr);
        float maxBankSize = this.getScaledSize(currT, this.banksWidth) * sqScaleFactor;
        float bankRadius = (float) Math.sqrt(maxBankSize);

        float maxBedSize = this.getScaledSize(currT, this.bedWidth) * sqScaleFactor;
        float bedRadius = (float) Math.sqrt(maxBedSize);

        // --- EXPANDED ADAPTIVE LATERAL INFLUENCE FLOOR ---
        // Dynamically scales the minimum bank run based on how deep the river cut is relative
        // to the original terrain height. Massive mountain steps trigger a quadratic expansion
        // component to guarantee a wide, sweeping horizontal transition ramp.
        float heightDelta = Math.max(0.0F, cell.height - targetWaterLevel);
        float baseMinBankRun = 4.0F;

        float bankRunLinearScale = 200.0F;
        float bankRunQuadraticScale = 150.0F;

        float dynamicMinBankRun = baseMinBankRun
                + (heightDelta * bankRunLinearScale)
                + (heightDelta * heightDelta * bankRunQuadraticScale);

        if (bankRadius < bedRadius + dynamicMinBankRun) {
            bankRadius = bedRadius + dynamicMinBankRun;
        }

        // Escape immediately if we are outside the expanded erosion zone for this segment step
        if (currentLinearDist >= bankRadius) return;

        boolean isInsideChannel = currentLinearDist < bedRadius;

        if (!isInsideChannel) {
            float bankProgress = (bankRadius - currentLinearDist) / (bankRadius - bedRadius);
            bankProgress = NoiseUtil.clamp(bankProgress, 0.0F, 1.0F);

            // High-frequency micro-perturbation
            float microNoise = (float) Math.sin(currX * 0.4F) * (float) Math.cos(currZ * 0.4F);
            // Multiplying by bankProgress * (1.0 - bankProgress) ensures the noise tapers
            // perfectly to 0 at the outer edge, preventing boundary discontinuities.
            float lateralWarp = microNoise * 0.12F * bankProgress * (1.0F - bankProgress);
            float roughBankProgress = NoiseUtil.clamp(bankProgress + lateralWarp, 0.0F, 1.0F);

            float smoothBankAlpha = roughBankProgress * roughBankProgress * (3.0F - 2.0F * roughBankProgress);

            // Runoff Gully Noise Component
            float gullyFreq = 0.05F;
            float nx = currX * gullyFreq;
            float nz = currZ * gullyFreq;
            float noiseSample = (float) Math.sin(nx + Math.cos(nz)) * (float) Math.cos(nz - Math.sin(nx));
            float gullyEffect = 1.0F - Math.abs(noiseSample);
            gullyEffect = gullyEffect * gullyEffect;

            // Terracing / Micro-slopes
            float detailFreq = 0.25F;
            float detailSample = (float) Math.sin(currX * detailFreq) * (float) Math.sin(currZ * detailFreq);
            float roughGullyEffect = NoiseUtil.lerp(gullyEffect, gullyEffect * (0.8F + detailSample * 0.2F), bankProgress);

            // --- PROXIMITY HEIGHT CEILING ENVELOPE ---
            float distanceWeight = 1.0F - bankProgress;
            float maxSafeRise = 0.02F + (distanceWeight * distanceWeight * 0.4F);
            float safeCeiling = targetWaterLevel + maxSafeRise;

            float baselineHeight = cell.height;
            if (baselineHeight > safeCeiling) {
                float excess = baselineHeight - safeCeiling;
                // Scale the envelope intensity by bankProgress so that at the outer edge (bankProgress = 0),
                // the squashing effect is exactly 0, removing the vertical slump cliff entirely.
                baselineHeight = baselineHeight - (excess * 0.6F * bankProgress);
            }

            float intermediateHeight = baselineHeight;
            if (baselineHeight > targetWaterLevel) {
                intermediateHeight = NoiseUtil.lerp(baselineHeight, targetWaterLevel, smoothBankAlpha);
            }

            // Headroom-scale carving rule for structural gullies
            if (roughGullyEffect > 0.0F && bankProgress > 0.0F) {
                float maxGullyDepth = (intermediateHeight - targetWaterLevel) * 0.45F;
                float gullyCut = roughGullyEffect * maxGullyDepth * bankProgress;
                intermediateHeight -= gullyCut;
            }

            float finalBankHeight = Math.max(targetWaterLevel, intermediateHeight);

            // Blends naturally with lakes: the lower profile wins, preventing harsh seams
            if (finalBankHeight < cell.height) {
                cell.height = finalBankHeight;
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