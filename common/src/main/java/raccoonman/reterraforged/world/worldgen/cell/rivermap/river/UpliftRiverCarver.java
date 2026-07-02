package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import java.util.Random;
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
    public LakeConfig lakeConfig;
    private boolean isUpliftContinent;

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
        this.bedDepth = new Range(levels.water, config.bedHeight);
        this.banksDepth = new Range(config.minBankHeight, config.maxBankHeight);
        this.valleyCurve = settings.valleyCurve;
        this.levels = levels;

        // Initialize seamless noise modules
        this.widthNoise = Noises.simplex(8241, 150, 2);
        this.depthNoise = Noises.simplex(3912, 100, 2);
        this.terraceNoise = Noises.simplex(5510, 200, 1);
        this.asymmetryNoise = Noises.simplex(1193, 250, 1);

        // Broad spatial period (~360 blocks) so the valley floor gradually pinches
        // narrow then flares wide as the river travels through the world
        this.valleyPinchNoise = Noises.simplex(6204, 360, 2);

        // Drainage initialization
        this.gullyNoise = Noises.simplex(9876, 65, 2);
        this.rivuletNoise = Noises.simplex(5432, 20, 2);

        // Multi-octave simplex noise for complex, jagged lake boundaries (Scale 55, 3 Octaves)
        this.lakeWarpNoise = Noises.simplex(7439, 55, 3);

        this.lakeConfig = lakeConfig;
        this.isUpliftContinent = isUpliftContinent;
    }

    @Override
    public void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT) {

        // fixed reference values
        float distSqToCurr = this.getDistance2(currX, currZ, currT);
        float currentLinearDist = (float) Math.sqrt(distSqToCurr);
        float shrinkFactor = NoiseUtil.clamp(currT * this.fadeInv, 0.0F, 1.0F);
        float flatnessInput = isUpliftContinent ? cell.waterTable : currT;
        float flatnessFactor = NoiseUtil.clamp(ContinentalHydrology.getFlatnessFactor(flatnessInput), 0.0F, 1.0F);
        float scaleFactor = 1.0F + 0.75F * flatnessFactor;
        float sqScaleFactor = scaleFactor * scaleFactor;

        // noise sampling
        float widthVar = this.widthNoise.compute(currX, currZ, 8241);
        float depthVar = this.depthNoise.compute(currX, currZ, 3912);
        float terraceMask = this.terraceNoise.compute(currX, currZ, 5510);
        float asymmetry = this.asymmetryNoise.compute(currX, currZ, 1193);
        float valleyPinchVar = this.valleyPinchNoise.compute(currX, currZ, 6204) * 2.0F;

        // always run mask logic to ensure clean falloffs
        updateValleyMask(prevX, prevZ, prevT, currT, distSqToCurr, sqScaleFactor, cell);

        // drainage falloffs
        float gullyRaw = this.gullyNoise.compute(currX, currZ, 9876);
        float gullyShape = 1.0F - Math.abs(gullyRaw);
        gullyShape *= gullyShape;
        float rivuletRaw = this.rivuletNoise.compute(currX, currZ, 5432);
        float rivuletShape = 1.0F - Math.abs(rivuletRaw);
        rivuletShape = rivuletShape * rivuletShape * rivuletShape;
        float drainageMask = (gullyShape * 0.7F) + (rivuletShape * 0.3F);

        // width falloffs
        float dynamicWidthMult = 1.0F + (widthVar * 0.35F);
        float dynamicDepthMult = 1.0F + (depthVar * 0.25F);
        float sideBias = 1.0F + (asymmetry * 0.4F);
        float valleyPinchMultiplier = NoiseUtil.clamp(1.0F + valleyPinchVar, 0.05F, 1.95F);

        // target elevations
        float oceanHeightOffset = levels.water;
        float targetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanHeightOffset;
        float baseBedDepthOffset = oceanHeightOffset - config.bedHeight;
        float bedDepthOffset = baseBedDepthOffset * dynamicDepthMult;
        float bankHeightOffset = (config.maxBankHeight - config.minBankHeight);
        float targetValleyFloor = targetWaterLevel + bankHeightOffset;
        float discrepancyScale = 1.0F + (levels.scale(cell.height - targetWaterLevel)) / 100.0F;
        float valleyFloorBumpiness = ((terraceMask * 0.4F) - (drainageMask * 0.6F)) * this.levels.unit;
        float actualValleyFloorHeight = targetValleyFloor + valleyFloorBumpiness;

        // Zone calculations
        float biasedScale = sqScaleFactor * dynamicWidthMult * sideBias;
        float zone1Radius = (float) Math.sqrt(this.getScaledSize(currT, this.bedWidth) * biasedScale);
        float lakeMultiplier = getLakeMultiplier(cell, currT, currX, currZ, flatnessFactor);
        zone1Radius *= lakeMultiplier;
        float zone2Width = (config.maxBankHeight - config.minBankHeight) / this.levels.unit * biasedScale;
        float zone2Radius = zone1Radius + zone2Width;
        float unshrunkZone3BaseWidth = config.bankWidth * dynamicWidthMult * valleyPinchMultiplier;
        float zone3BaseWidth = unshrunkZone3BaseWidth * shrinkFactor;
        float zone3Width = zone3BaseWidth * shrinkFactor;
        float zone3Radius = zone2Radius + zone3Width;
        float zone4Radius = zone3Radius + (unshrunkZone3BaseWidth * (4 + discrepancyScale));

        // early exit guard if we're a cell outside the river influence
        if (currentLinearDist >= zone4Radius) return;

        storeFlowDirection(cell, currentLinearDist, zone1Radius);

        // calculate the final cell heights
        float finalHeight = cell.height;
        if (currentLinearDist < zone1Radius) {
            finalHeight = carveZone1Riverbed(cell, currT, distSqToCurr, bedDepthOffset, oceanHeightOffset, sqScaleFactor, targetWaterLevel, lakeMultiplier);
        } else if (currentLinearDist < zone2Radius) {
            finalHeight = carveZone2BankStep(currentLinearDist, zone1Radius, zone2Radius, targetWaterLevel, actualValleyFloorHeight, terraceMask, drainageMask);
        } else if (currentLinearDist < zone3Radius) {
            finalHeight = actualValleyFloorHeight;
        } else {
            finalHeight = carveZone4Fadeout(cell.height, currentLinearDist, zone3Radius, zone4Radius, actualValleyFloorHeight, terraceMask, drainageMask);
        }

        // Only commit data changes to the cell if our carving operations actually cut down the world
        if (finalHeight < cell.height) {
            cell.height = finalHeight;
            cell.riverZone = getRiverZoneTag(cell, currentLinearDist, zone1Radius, zone2Radius, zone3Radius);
        }
    }

    private void storeFlowDirection(Cell cell, float currentLinearDist, float zone1Radius){
        // Calculate the normalized downstream direction vector
        float len = (float) Math.sqrt(this.river.dx * this.river.dx + this.river.dz * this.river.dz);
        float dirX = this.river.dx / (len > 0 ? len : 1.0F);
        float dirZ = this.river.dz / (len > 0 ? len : 1.0F);

        // Compress the vector into a single byte angle to save memory
        // Math.atan2 returns -PI to PI, map this to -128 to 127
        byte flowAngle = (byte) Math.round(Math.atan2(dirZ, dirX) * 128.0 / Math.PI);

        // Assign to cell if within the actual water zone (Zone 1)
        if (currentLinearDist < zone1Radius) {
            cell.flowAngle = flowAngle;
            cell.hasFlow = true;
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
        return widenMultiplier; // no op
    }

    private float carveZone1Riverbed(Cell cell, float currT, float distSqToCurr, float bedDepthOffset, float oceanHeightOffset, float sqScaleFactor, float targetWaterLevel, float widenMultiplier) {
        float effectiveScaleFactor = sqScaleFactor * (widenMultiplier * widenMultiplier);
        float bedInfluence = this.getDistanceAlpha(currT, distSqToCurr, this.bedWidth, effectiveScaleFactor);
        bedInfluence = bedInfluence * bedInfluence * (3.0F - 2.0F * bedInfluence);

        float lakeDepthMulti = 0.35F + (lakeConfig.depth / 50.0F);
        float dynamicDepthOffset = bedDepthOffset * (1.0F + (widenMultiplier - 1.0F) * lakeDepthMulti);

        float bedHeight = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) - (dynamicDepthOffset * bedInfluence) + oceanHeightOffset;

        cell.moisture = 1.0F;
        this.tag(cell, targetWaterLevel);
        return bedHeight;
    }

    private float carveZone2BankStep(float distance, float zone1Radius, float zone2Radius, float targetWaterLevel, float targetValleyFloor, float terraceMask, float drainageMask) {
        float progress = (distance - zone1Radius) / (zone2Radius - zone1Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        progress = applyTerracing(progress, terraceMask, drainageMask, 3.0F);

        float arc = progress * (1.0F - progress) * 4.0F;
        progress = Math.max(0.0F, progress - (drainageMask * 0.3F * arc));

        float smoothProgress = progress * progress * (3.0F - 2.0F * progress);
        return NoiseUtil.lerp(targetWaterLevel, targetValleyFloor, smoothProgress);
    }

    private float carveZone4Fadeout(float originalTerrainHeight, float distance, float zone3Radius, float zone4Radius, float targetValleyFloor, float terraceMask, float drainageMask) {
        float progress = (distance - zone3Radius) / (zone4Radius - zone3Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        float modifiedProgress = applyTerracing(progress, terraceMask, drainageMask, 5.0F);

        float slopeMask = progress * (1.0F - progress) * 4.0F;
        modifiedProgress = Math.max(0.0F, modifiedProgress - (drainageMask * 0.25F * slopeMask));

        float smoothProgress = modifiedProgress * modifiedProgress * (3.0F - 2.0F * modifiedProgress);
        return NoiseUtil.lerp(targetValleyFloor, originalTerrainHeight, smoothProgress);
    }

    private float applyTerracing(float progress, float terraceMask, float drainageMask, float steps) {
        float intactTerrace = Math.max(0.0F, terraceMask - (drainageMask * 1.5F));

        // Balanced strength multiplier (1.75F) for a confident but natural terrace presence
        float terraceStrength = NoiseUtil.clamp(intactTerrace * 1.75F, 0.0F, 1.0F);

        if (terraceStrength <= 0.0F) {
            return progress;
        }

        float scaledProgress = progress * steps;
        float floor = (float) Math.floor(scaledProgress);
        float fract = scaledProgress - floor;

        // 0.78F puts the cliff face in the upper 22% of the step, distinctly steep but scalable.
        float baseCliffBias = 0.78F;
        float cliffBias = NoiseUtil.clamp(baseCliffBias - (drainageMask * 0.14F), 0.55F, 0.88F);

        float baseTalusHeight = 0.14F;
        float talusHeight = NoiseUtil.clamp(baseTalusHeight + (drainageMask * 0.22F), 0.04F, 0.45F);

        float steppedFract;
        if (fract < cliffBias) {
            float t = fract / cliffBias;

            // Split the difference between t^3 and t^4 using t^3.5
            // This gives a flat-ish shelf that transitions smoothly into the debris heap
            steppedFract = (float) Math.pow(t, 3.5F) * talusHeight;
        } else {
            float t = (fract - cliffBias) / (1.0F - cliffBias);

            // We blend a sharp quadratic curve (t^2) with a smooth S-curve (3t^2 - 2t^3).
            // This yields a cliff face that breaks out of the talus cleanly, but wraps
            // into the upper shelf with a slightly weathered, natural roll-over.
            float sharpCurve = t * t;
            float smoothCurve = t * t * (3.0F - 2.0F * t);
            float hybridCliff = NoiseUtil.lerp(sharpCurve, smoothCurve, 0.5F);

            steppedFract = NoiseUtil.lerp(talusHeight, 1.0F, hybridCliff);
        }

        float steppedProgress = (floor + steppedFract) / steps;

        // A 90% maximum blend weight ensures the steps stay well-defined
        // while allowing the regional terrain shape to gently break up the monotony.
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

        Random selectionRand = new Random(riverSeed);
        return selectionRand.nextFloat() < config.chance;
    }

    private float getLakeScaleForPlateau(int plateauIndex, float minScale, float maxScale) {
        int h1 = Float.floatToIntBits(this.river.x1);
        int h2 = Float.floatToIntBits(this.river.z1);
        long riverSeed = ((long) h1 << 32) | (h2 & 0xFFFFFFFFL);

        riverSeed ^= plateauIndex * 0x2545F4914L;

        Random scaleRand = new Random(riverSeed);
        return minScale + scaleRand.nextFloat() * (maxScale - minScale);
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