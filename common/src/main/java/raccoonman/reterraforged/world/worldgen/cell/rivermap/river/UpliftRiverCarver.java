package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunction;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;

public class UpliftRiverCarver implements RTFRiverCarver {
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
    private Noise widthNoise;
    private Noise depthNoise;
    private Noise terraceNoise;
    private Noise asymmetryNoise;
    private Noise valleyPinchNoise;
    private Noise gullyNoise;
    private Noise rivuletNoise;
    private Noise lakeWarpNoise;
    private Noise valleyWarpNoise;
    public LakeConfig lakeConfig;
    private boolean isUpliftContinent;

    // Fractional offset into Zone 3 where warping begins (8% into Zone 3)
    private static final float WARP_START_ZONE3_RATIO = 0.08F;

    // Fractional extension into Zone 4 where warping reaches full blend
    private static final float WARP_END_ZONE4_RATIO = 0.60F;

    // Maximum space shift / translation offset limit
    private static final float MAX_WARP_OFFSET = 0.05F;

    // MODERATE SAFEGUARDS
    private static final float MIN_ZONE2_WIDTH = 1.5F;       // Tight floor to prevent 0-block cliffs
    private static final float MIN_WARP_RAMP_SPAN = 6.0F;    // Tighter span to keep warp features pronounced

    public UpliftRiverCarver(River river, RiverWarp warp, RiverConfig config, RiverCarverSettings settings, Levels levels, LakeConfig lakeConfig, boolean isUpliftContinent) {
        this.fade = settings.fadeIn;
        this.fadeInv = 1.0F / settings.fadeIn;

        this.bedWidth = new Range(0.25F, (float)(config.bedWidth * config.bedWidth));

        float erosionScale = 3.5F;
        float sqErosionScale = erosionScale * erosionScale;

        float outerErosionScale = 1.15F;
        float sqOuterErosionScale = outerErosionScale * outerErosionScale;
        this.banksWidth = new Range(1.5625F * sqErosionScale, (float)(config.bankWidth * config.bankWidth) * sqOuterErosionScale);

        float expandedValley = settings.valleySize * erosionScale;
        this.valleyWidth = new Range(expandedValley * expandedValley, expandedValley * expandedValley);

        this.river = river;
        this.warp = warp;
        this.config = config;
        this.main = config.main;
        this.connecting = settings.connecting;
        this.waterLine = levels.water;

        // bedDepth stores bed level limits; banksDepth stores bank height limits above water
        this.bedDepth = new Range(levels.water, config.bedHeight);
        this.banksDepth = new Range(config.minBankHeight, config.maxBankHeight);
        this.valleyCurve = settings.valleyCurve;
        this.levels = levels;

        // Initialize seamless noise modules
        this.widthNoise = Noises.simplex(8241, 150, 2);
        this.depthNoise = Noises.simplex(3912, 100, 2);
        this.terraceNoise = Noises.simplex(5510, 200, 1);
        this.asymmetryNoise = Noises.simplex(1193, 250, 1);
        this.valleyPinchNoise = Noises.simplex(6204, 360, 2);
        this.valleyWarpNoise = Noises.simplex(4321, 160, 2);
        this.gullyNoise = Noises.simplex(9876, 65, 2);
        this.rivuletNoise = Noises.simplex(5432, 20, 2);
        this.lakeWarpNoise = Noises.simplex(7439, 55, 3);

        this.lakeConfig = lakeConfig;
        this.isUpliftContinent = isUpliftContinent;
    }

    @Override
    public void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT) {

        // Fixed reference values
        float distSqToCurr = this.getDistance2(currX, currZ, currT);
        float currentLinearDist = (float) Math.sqrt(distSqToCurr);
        float flatnessInput = isUpliftContinent ? cell.waterTable : currT;
        float flatnessFactor = NoiseUtil.clamp(ContinentalHydrology.getFlatnessFactor(flatnessInput), 0.0F, 1.0F);
        float scaleFactor = 1.0F;

        // Dynamic Bank Depth: Height of embankments ABOVE water level (minBankHeight -> maxBankHeight)
        float dynamicBankDepth = this.getScaledSize(currT, this.banksDepth);

        // Dynamic Bed Depth: Distance BELOW water level down to riverbed floor
        float maxPossibleBedDepth = this.waterLine - this.getScaledSize(currT, this.bedDepth);

        // Step 1: Sample layout-critical noise
        float widthVar = this.widthNoise.compute(currX, currZ, 8241);
        float asymmetry = this.asymmetryNoise.compute(currX, currZ, 1193);
        float valleyPinchVar = this.valleyPinchNoise.compute(currX, currZ, 6204) * 2.0F;

        updateValleyMask(prevX, prevZ, prevT, currT, distSqToCurr, scaleFactor, cell);

        // Layout parameters
        float dynamicWidthMult = 1.0F + (widthVar * 0.35F);
        float sideBias = 1.0F + (asymmetry * 0.4F);
        float valleyPinchMultiplier = NoiseUtil.clamp(1.0F + valleyPinchVar, 0.05F, 1.95F);

        // Zone radius calculations (Zone 2 width scales with dynamicBankDepth)
        float biasedScale = scaleFactor * dynamicWidthMult * sideBias;
        float zone1Radius = (float) Math.sqrt(this.getScaledSize(currT, this.bedWidth) * biasedScale);
        float lakeMultiplier = getLakeMultiplier(cell, currT, currX, currZ, flatnessFactor);
        zone1Radius *= lakeMultiplier;

        float dynamicZone2Width = (dynamicBankDepth / levels.unit) * biasedScale;
        float zone2Width = Math.max(MIN_ZONE2_WIDTH, dynamicZone2Width);
        float zone2Radius = zone1Radius + zone2Width;

        float unshrunkZone3BaseWidth = config.bankWidth * dynamicWidthMult * valleyPinchMultiplier;
        float shrinkFactor = NoiseUtil.clamp(currT * this.fadeInv, 0.0F, 1.0F);
        float zone3BaseWidth = unshrunkZone3BaseWidth * shrinkFactor;
        float zone3Width = zone3BaseWidth * shrinkFactor;
        float zone3Radius = zone2Radius + zone3Width;

        // Calculate target water level
        float baseWaterLevel = ContinentalHydrology.getComplexWaterHeight(
                cell.waterTable,
                cell.globalContinentScale,
                cell.continentSizeModifier);

        float targetWaterLevel = baseWaterLevel + levels.water;

        float discrepancyScale = 1.0F + (levels.scale(cell.height - targetWaterLevel)) / 100.0F;
        float zone4Width = unshrunkZone3BaseWidth * (4.0F + discrepancyScale);
        float zone4Radius = zone3Radius + zone4Width;

        // Early Exit Guard
        if (currentLinearDist >= zone4Radius) return;

        // Defer remaining heavy noise evaluations
        float depthVar = this.depthNoise.compute(currX, currZ, 3912);
        float terraceMask = this.terraceNoise.compute(currX, currZ, 5510);
        float gullyRaw = this.gullyNoise.compute(currX, currZ, 9876);
        float rivuletRaw = this.rivuletNoise.compute(currX, currZ, 5432);
        float warpRaw = this.valleyWarpNoise.compute(currX, currZ, 4321);

        // BALANCED RAMP SPAN CALCULATION
        float warpStartDist = zone2Radius + (zone3Width * WARP_START_ZONE3_RATIO);
        float calculatedEndDist = zone3Radius + (zone4Width * WARP_END_ZONE4_RATIO);
        float warpEndDist = Math.max(warpStartDist + MIN_WARP_RAMP_SPAN, calculatedEndDist);
        float rampSpan = warpEndDist - warpStartDist;

        float zone3Weight = 0.0F;
        if (currentLinearDist > warpStartDist) {
            float progress = NoiseUtil.clamp((currentLinearDist - warpStartDist) / rampSpan, 0.0F, 1.0F);

            // Quintic Smoothstep (6t^5 - 15t^4 + 10t^3)
            float smoothProgress = progress * progress * progress * (progress * (progress * 6.0F - 15.0F) + 10.0F);

            if (currentLinearDist < warpEndDist) {
                zone3Weight = smoothProgress;
            } else {
                float fadeoutProgress = 1.0F - ((currentLinearDist - warpEndDist) / Math.max(0.001F, zone4Radius - warpEndDist));
                zone3Weight = NoiseUtil.clamp(fadeoutProgress, 0.0F, 1.0F);
            }
        }

        // Retain at least 60% warp amplitude even on small/narrow streams
        float widthWarpScale = NoiseUtil.clamp(shrinkFactor * dynamicWidthMult, 0.60F, 1.0F);
        float effectiveWarpOffset = MAX_WARP_OFFSET * widthWarpScale;

        float noiseOffset = warpRaw * effectiveWarpOffset;
        float warpedWaterHeight = ContinentalHydrology.getZone3WarpedWaterHeight(
                cell.waterTable,
                noiseOffset,
                zone3Weight,
                cell.globalContinentScale,
                cell.continentSizeModifier
        );
        targetWaterLevel = warpedWaterHeight + levels.water;

        // Drainage calculation adjustments
        float gullyShape = 1.0F - Math.abs(gullyRaw);
        gullyShape *= gullyShape;
        float rivuletShape = 1.0F - Math.abs(rivuletRaw);
        rivuletShape = rivuletShape * rivuletShape * rivuletShape;
        float drainageMask = (gullyShape * 0.7F) + (rivuletShape * 0.3F);

        // Dynamic Bed Depth modulated by depth noise module
        float dynamicDepthMult = 1.0F + (depthVar * 0.25F);
        float dynamicBedDepth = maxPossibleBedDepth * dynamicDepthMult;

        // Bank depth defines surrounding valley floor height above target water level
        float targetValleyFloor = targetWaterLevel + dynamicBankDepth;
        float valleyFloorBumpiness = ((terraceMask * 0.4F) - (drainageMask * 0.6F)) * this.levels.unit;
        float actualValleyFloorHeight = targetValleyFloor + valleyFloorBumpiness;

        // Calculate final cell heights
        float finalHeight = cell.height;
        if (currentLinearDist < zone1Radius) {
            finalHeight = carveZone1Riverbed(cell, currT, distSqToCurr, dynamicBedDepth, scaleFactor, targetWaterLevel, lakeMultiplier, flatnessFactor, depthVar);
        } else if (currentLinearDist < zone2Radius) {
            finalHeight = carveZone2BankStep(currentLinearDist, zone1Radius, zone2Radius, targetWaterLevel, actualValleyFloorHeight, terraceMask, drainageMask);
        } else if (currentLinearDist < zone3Radius) {
            finalHeight = actualValleyFloorHeight;
        } else {
            finalHeight = carveZone4Fadeout(cell.height, currentLinearDist, zone3Radius, zone4Radius, actualValleyFloorHeight, terraceMask, drainageMask);
        }

        // Only commit data changes to the cell if carving cut down the world
        if (finalHeight < cell.height) {
            cell.height = finalHeight;
            cell.riverZone = getRiverZoneTag(cell, currentLinearDist, zone1Radius, zone2Radius, zone3Radius);
        }
    }

    private RiverCarverSettings.RiverZone getRiverZoneTag(Cell cell, float currentLinearDist, float zone1Radius, float zone2Radius, float zone3Radius){
        RiverCarverSettings.RiverZone prospectiveZone = cell.riverZone;

        if (currentLinearDist < zone1Radius) {
            prospectiveZone = RiverCarverSettings.RiverZone.Riverbed;
        } else if (currentLinearDist < zone2Radius) {
            if (prospectiveZone != RiverCarverSettings.RiverZone.Riverbed) {
                prospectiveZone = RiverCarverSettings.RiverZone.Banks;
            }
        } else if (currentLinearDist < zone3Radius) {
            if (prospectiveZone != RiverCarverSettings.RiverZone.Riverbed && prospectiveZone != RiverCarverSettings.RiverZone.Banks) {
                prospectiveZone = RiverCarverSettings.RiverZone.ValleyFloor;
            }
        } else {
            if (prospectiveZone != RiverCarverSettings.RiverZone.Riverbed && prospectiveZone != RiverCarverSettings.RiverZone.Banks && prospectiveZone != RiverCarverSettings.RiverZone.ValleyFloor) {
                prospectiveZone = RiverCarverSettings.RiverZone.ValleyFadeout;
            }
        }

        return prospectiveZone;
    }

    private float getLakeMultiplier(Cell cell, float currT, float currX, float currZ, float flatnessFactor) {
        float plateauInput = isUpliftContinent ? cell.waterTable : currT;
        float widenMultiplier = 1.0F;
        int plateauIndex = ContinentalHydrology.getStepId(plateauInput);
        if (this.shouldWidenOnPlateau(plateauIndex, lakeConfig, currT)) {
            float lakeScaleMin = lakeConfig.sizeMin / 100.0F;
            float lakeScaleMax = lakeConfig.sizeMax / 100.0F;

            float baseStepScale = this.getLakeScaleForPlateau(plateauIndex, lakeScaleMin, lakeScaleMax);
            float shorelineWarp = this.lakeWarpNoise.compute(currX, currZ, 7439);
            float organicWarpFactor = baseStepScale * (1.0F + shorelineWarp * 0.45F);

            float distanceMask = 1.0F;
            float fadeWindow = 0.04F;

            if (currT < lakeConfig.distanceMin) {
                distanceMask = NoiseUtil.clamp((currT - (lakeConfig.distanceMin - fadeWindow)) / fadeWindow, 0.0F, 1.0F);
            } else if (currT > lakeConfig.distanceMax) {
                distanceMask = NoiseUtil.clamp(((lakeConfig.distanceMax + fadeWindow) - currT) / fadeWindow, 0.0F, 1.0F);
            }

            distanceMask = distanceMask * distanceMask * (3.0F - 2.0F * distanceMask);
            widenMultiplier = 1.0F + (flatnessFactor * organicWarpFactor * distanceMask);
        }
        return widenMultiplier;
    }

    private float carveZone1Riverbed(Cell cell, float currT, float distSqToCurr, float dynamicBedDepth, float sqScaleFactor, float targetWaterLevel, float widenMultiplier, float flatnessFactor, float depthVar) {
        float effectiveScaleFactor = sqScaleFactor * (widenMultiplier * widenMultiplier);
        float bedInfluence = this.getDistanceAlpha(currT, distSqToCurr, this.bedWidth, effectiveScaleFactor);
        bedInfluence = bedInfluence * bedInfluence * (3.0F - 2.0F * bedInfluence);

        float shallowNoiseFloor = (2.3F + (depthVar * 0.3F)) * this.levels.unit;
        float progressiveDepth = dynamicBedDepth;

        if (widenMultiplier > 1.0F) {
            float lakeDepthMulti = 0.35F + (lakeConfig.depth / 50.0F);
            float lakeVariance = 1.0F + (depthVar * 0.40F);
            progressiveDepth = progressiveDepth * (1.0F + (widenMultiplier - 1.0F) * lakeDepthMulti * lakeVariance);
        }

        float finalizedDepth = shallowNoiseFloor + (progressiveDepth * flatnessFactor);

        if (flatnessFactor > 0.4F) {
            float flatnessIntensity = (flatnessFactor - 0.4F) / 0.6F;
            float trenchNoise = (depthVar * 0.5F + 0.5F);
            float deepPocketBonus = flatnessIntensity * trenchNoise * 3.5F * this.levels.unit * currT;
            finalizedDepth += deepPocketBonus;
        }

        float absoluteFloor = 2.0F * this.levels.unit;
        if (finalizedDepth < absoluteFloor) {
            finalizedDepth = absoluteFloor;
        }

        // Riverbed sits BELOW target water level by finalizedDepth
        float bedHeight = targetWaterLevel - (finalizedDepth * bedInfluence);

        cell.moisture = 1.0F;
        this.tag(cell, targetWaterLevel);
        return bedHeight;
    }

    private float carveZone2BankStep(float distance, float zone1Radius, float zone2Radius, float targetWaterLevel, float targetValleyFloor, float terraceMask, float drainageMask) {
        float actualWidth = zone2Radius - zone1Radius;
        float progress = (distance - zone1Radius) / actualWidth;
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        // Light width gating only when bank is tighter than 1.5 blocks
        float widthTerraceFactor = NoiseUtil.clamp((actualWidth - 1.0F) / 1.0F, 0.0F, 1.0F);
        if (widthTerraceFactor > 0.0F) {
            progress = applyTerracing(progress, terraceMask * 0.45F * widthTerraceFactor, drainageMask, 2.0F);
        }

        float arc = progress * (1.0F - progress) * 4.0F;
        progress = Math.max(0.0F, progress - (drainageMask * 0.3F * arc));

        float smoothProgress = progress * progress * (3.0F - 2.0F * progress);

        // Slopes upward from targetWaterLevel at river edge to targetValleyFloor at outer bank
        return NoiseUtil.lerp(targetWaterLevel, targetValleyFloor, smoothProgress);
    }

    private float carveZone4Fadeout(float originalTerrainHeight, float distance, float zone3Radius, float zone4Radius, float targetValleyFloor, float terraceMask, float drainageMask) {
        float span = Math.max(6.0F, zone4Radius - zone3Radius);
        float progress = (distance - zone3Radius) / span;
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        float modifiedProgress = applyTerracing(progress, terraceMask, drainageMask, 5.0F);

        float slopeMask = progress * (1.0F - progress) * 4.0F;
        modifiedProgress = Math.max(0.0F, modifiedProgress - (drainageMask * 0.25F * slopeMask));

        float smoothProgress = modifiedProgress * modifiedProgress * (3.0F - 2.0F * modifiedProgress);
        return NoiseUtil.lerp(targetValleyFloor, originalTerrainHeight, smoothProgress);
    }

    private float applyTerracing(float progress, float terraceMask, float drainageMask, float steps) {
        float intactTerrace = Math.max(0.0F, terraceMask - (drainageMask * 1.5F));

        float terraceStrength = NoiseUtil.clamp(intactTerrace * 1.75F, 0.0F, 1.0F);

        if (terraceStrength <= 0.0F) {
            return progress;
        }

        float scaledProgress = progress * steps;
        float floor = (float) Math.floor(scaledProgress);
        float fract = scaledProgress - floor;

        float baseCliffBias = 0.78F;
        float cliffBias = NoiseUtil.clamp(baseCliffBias - (drainageMask * 0.14F), 0.55F, 0.88F);

        float baseTalusHeight = 0.14F;
        float talusHeight = NoiseUtil.clamp(baseTalusHeight + (drainageMask * 0.22F), 0.04F, 0.45F);

        float steppedFract;
        if (fract < cliffBias) {
            float t = fract / cliffBias;
            steppedFract = (float) Math.pow(t, 3.5F) * talusHeight;
        } else {
            float t = (fract - cliffBias) / (1.0F - cliffBias);
            float sharpCurve = t * t;
            float smoothCurve = t * t * (3.0F - 2.0F * t);
            float hybridCliff = NoiseUtil.lerp(sharpCurve, smoothCurve, 0.5F);

            steppedFract = NoiseUtil.lerp(talusHeight, 1.0F, hybridCliff);
        }

        float steppedProgress = (floor + steppedFract) / steps;

        return NoiseUtil.lerp(progress, steppedProgress, terraceStrength * 0.90F);
    }

    private void updateValleyMask(float prevX, float prevZ, float prevT, float currT, float distSqToCurr, float sqScaleFactor, Cell cell) {
        float distSqToPrev = this.getDistance2(prevX, prevZ, prevT);
        float valleyInfluence = this.getDistanceAlpha(currT, Math.min(distSqToCurr, distSqToPrev), this.valleyWidth, sqScaleFactor);
        if (valleyInfluence > 0.0F) {
            valleyInfluence = this.valleyCurve.apply(valleyInfluence);
            cell.riverMask = Math.min(cell.riverMask, 1.0F - valleyInfluence);
        }
    }

    private boolean shouldWidenOnPlateau(int plateauIndex, LakeConfig config, float currT) {
        if (plateauIndex < -1) return false;

        float fadeWindow = 0.04F;
        if (currT < config.distanceMin - fadeWindow) return false;
        if (currT > config.distanceMax + fadeWindow) return false;

        int h1 = Float.floatToIntBits(this.river.x1);
        int h2 = Float.floatToIntBits(this.river.z1);
        long riverSeed = ((long) h1 << 32) | (h2 & 0xFFFFFFFFL);

        riverSeed ^= plateauIndex * 0x5DEECE66DL;

        return getDeterministicFloat(riverSeed) < config.chance;
    }

    private float getLakeScaleForPlateau(int plateauIndex, float minScale, float maxScale) {
        int h1 = Float.floatToIntBits(this.river.x1);
        int h2 = Float.floatToIntBits(this.river.z1);
        long riverSeed = ((long) h1 << 32) | (h2 & 0xFFFFFFFFL);

        riverSeed ^= plateauIndex * 0x2545F4914L;

        return minScale + getDeterministicFloat(riverSeed) * (maxScale - minScale);
    }

    private static float getDeterministicFloat(long seed) {
        seed ^= (seed >>> 33);
        seed *= 0xff51afd7ed558ccdL;
        seed ^= (seed >>> 33);
        seed *= 0xc4ceb9fe1a85ec53L;
        seed ^= (seed >>> 33);
        return (float) (seed & 0xFFFFFF) / 16777216.0F;
    }

    @Override
    public RiverConfig createForkConfig(float t, Levels levels) {
        int bedHeight = levels.scale(this.getScaledSize(t, this.bedDepth));
        int bedWidth = (int)Math.round(Math.sqrt(this.getScaledSize(t, this.bedWidth)) * 0.75);

        int bankWidth = (int)Math.round(Math.sqrt(this.getScaledSize(t, this.banksWidth)) * 0.75);
        bedWidth = Math.max(1, bedWidth);
        bankWidth = Math.max(bedWidth + 1, bankWidth);
        return this.config.createFork(bedHeight, bedWidth, bankWidth, levels);
    }

    private float getDistance2(float x, float y, float t) {
        if (t <= 0.0F) return Line.distSq(x, y, this.river.x1, this.river.z1);
        if (t >= 1.0F) return Line.distSq(x, y, this.river.x2, this.river.z2);
        float px = this.river.x1 + t * this.river.dx;
        float py = this.river.z1 + t * this.river.dz;
        return Line.distSq(x, y, px, py);
    }

    private float getDistanceAlpha(float t, float dist2, Range range, float sqScaleFactor) {
        float size2 = this.getScaledSize(t, range) * sqScaleFactor;
        if (dist2 >= size2) return 0.0F;
        return 1.0F - dist2 / size2;
    }

    private float getScaledSize(float t, Range range) {
        if (t < 0.0F) return range.min();
        if (t > 1.0F) return range.max();
        if (range.min() == range.max()) return range.min();
        if (t >= this.fade) return range.max();
        return NoiseUtil.lerp(range.min(), range.max(), t * this.fadeInv);
    }

    private void tag(Cell cell, float bedHeight) {
        if (cell.terrain.isLake()) return;
        cell.erosionMask = true;
        cell.terrain = TerrainType.RIVER;
        float newMax = Math.max(this.waterLine, bedHeight);
        if (newMax > cell.riverWaterLevel) {
            cell.riverWaterLevel = Math.max(this.waterLine, bedHeight);
        }
    }

    @Override public boolean isMain() { return this.main; }
    @Override public River getRiver() { return this.river; }
    @Override public RiverWarp getWarp() { return this.warp; }
    @Override public RiverConfig getConfig() { return this.config; }
}