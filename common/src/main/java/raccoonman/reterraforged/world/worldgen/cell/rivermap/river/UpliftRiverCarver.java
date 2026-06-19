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

    // Environmental Variation Noises
    private Noise widthNoise;
    private Noise depthNoise;
    private Noise terraceNoise;
    private Noise asymmetryNoise;
    private Noise slopeRoughnessNoise;
    private Noise scarNoise;

    // New Drainage Noises
    private Noise gullyNoise;
    private Noise rivuletNoise;

    // Organic Lake Shoreline Distortion Noise
    private Noise lakeWarpNoise;
    public LakeConfig lakeConfig;
    private Noise valleyWallWarpNoise;

    // --- PER-RIVER VARIANCE FIELDS ---
    private final float riverValleyWidthModifier;
    private boolean isUpliftContinent;

    private final float steepSlope;
    private final float gentleSlope;

    public UpliftRiverCarver(River river, RiverWarp warp, RiverConfig config, RiverCarverSettings settings, Levels levels, LakeConfig lakeConfig, boolean isUpliftContinent) {
        this.fade = settings.fadeIn;
        this.fadeInv = 1.0F / settings.fadeIn;

        this.bedWidth = new Range(1.5F, (float)(config.bedWidth * config.bedWidth));

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
        this.slopeRoughnessNoise = Noises.simplex(2847, 50, 2);
        this.scarNoise = Noises.simplex(7392, 120, 1);

        // Drainage initialization
        this.gullyNoise = Noises.simplex(9876, 65, 2);
        this.rivuletNoise = Noises.simplex(5432, 20, 2);

        // Multi-octave simplex noise for complex, jagged lake boundaries (Scale 55, 3 Octaves)
        this.lakeWarpNoise = Noises.simplex(7439, 55, 3);
        this.valleyWallWarpNoise = Noises.simplex(9437, 80, 3);

        this.lakeConfig = lakeConfig;
        this.isUpliftContinent = isUpliftContinent;

        // --- INITIALIZE DETERMINISTIC PER-RIVER VALLEY VARIANCE ---
        int rh1 = Float.floatToIntBits(river.x1);
        int rh2 = Float.floatToIntBits(river.z1);
        long uniqueRiverSeed = ((long) rh1 << 32) | (rh2 & 0xFFFFFFFFL);
        uniqueRiverSeed ^= 0x4B3C2B1A5L; // Unique salt modifier for valley layout variance

        Random riverVarRand = new Random(uniqueRiverSeed);
        // Generates a scaling factor between 0.70 and 1.30 (+/- 30% total width deviation per river system)
        this.riverValleyWidthModifier = 0.70F + riverVarRand.nextFloat() * 0.60F;

        this.steepSlope = (float) Math.tan(Math.toRadians(55.0F));
        this.gentleSlope = (float) Math.tan(Math.toRadians(22.0F));
    }

    @Override
    public void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT) {
        // Unwarped — stable water channel, smooth bed profile
        float distSqToCurr = this.getDistance2(currX, currZ, currT);
        float currentLinearDist = (float) Math.sqrt(distSqToCurr);

        // Warped — organic valley wall shaping only
        float warp = this.valleyWallWarpNoise.compute(currX * 0.1F, currZ * 0.1F, 9437);
        float warpedLinearDist = (float) Math.sqrt(this.getDistance2(currX + warp * 8.0F, currZ + warp * 5.0F, currT));

        float flatnessInput = isUpliftContinent ? cell.waterTable : currT;
        float flatnessFactor = NoiseUtil.clamp(ContinentalHydrology.getFlatnessFactor(flatnessInput), 0.0F, 1.0F);
        float scaleFactor = 1.0F + 0.75F * flatnessFactor;
        float sqScaleFactor = scaleFactor * scaleFactor;

        // --- ENVIRONMENTAL VARIATION SAMPLES ---
        float widthVar = this.widthNoise.compute(currX, currZ, 8241);
        float depthVar = this.depthNoise.compute(currX, currZ, 3912);
        float terraceMask = this.terraceNoise.compute(currX, currZ, 5510);
        float asymmetry = this.asymmetryNoise.compute(currX, currZ, 1193);

        // --- DRAINAGE CALCULATION (Ridged Noise) ---
        float gullyRaw = this.gullyNoise.compute(currX, currZ, 9876);
        float gullyShape = 1.0F - Math.abs(gullyRaw);
        gullyShape *= gullyShape;

        float rivuletRaw = this.rivuletNoise.compute(currX, currZ, 5432);
        float rivuletShape = 1.0F - Math.abs(rivuletRaw);
        rivuletShape = rivuletShape * rivuletShape * rivuletShape;

        float steepnessScale = NoiseUtil.lerp(0.5F, 1.5F, 1.0F - flatnessFactor);
        float drainageMask = (gullyShape * 0.7F) + (rivuletShape * 0.3F);
        drainageMask = NoiseUtil.clamp(drainageMask * steepnessScale, 0.0F, 1.0F);

        float dynamicWidthMult = 1.0F + (widthVar * 0.35F);
        float dynamicDepthMult = 1.0F + (depthVar * 0.25F);
        float sideBias = 1.0F + (asymmetry * 0.4F);

        // --- 1. TARGET ELEVATIONS ---
        float oceanHeightOffset = levels.water;
        float targetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanHeightOffset;

        float bedWidthSized = this.getScaledSize(currT, this.bedWidth);
        // Scale bed depth proportionally to width: narrower rivers = shallower
        float depthScaling = (float) Math.sqrt(bedWidthSized / this.bedWidth.max());
        float baseBedDepthOffset = oceanHeightOffset - config.bedHeight;
        float bedDepthOffset = baseBedDepthOffset * dynamicDepthMult * depthScaling;
        float targetBedFloor = targetWaterLevel - bedDepthOffset;

        float bankHeightOffset = (config.maxBankHeight - config.minBankHeight);
        float targetValleyFloor = targetWaterLevel + bankHeightOffset;
        float originalTerrainHeight = cell.height;
        float heightDiscrepancy = originalTerrainHeight - targetValleyFloor;
        float mountainFactor = 1.0F - flatnessFactor;
        float maxDiscrepancy = 200.0F * this.levels.unit; // tune this
        float discrepancyFactor = NoiseUtil.clamp(heightDiscrepancy / maxDiscrepancy, 0.0F, 1.0F);



        // --- RADII BOUNDARIES ---
        float biasedScale = sqScaleFactor * dynamicWidthMult * sideBias;
        float zone1Radius = (float) Math.sqrt(this.getScaledSize(currT, this.bedWidth) * biasedScale);

        float baseZone1Radius = (float) Math.sqrt(this.getScaledSize(currT, this.bedWidth) * sqScaleFactor);
        zone1Radius = Math.max(zone1Radius, baseZone1Radius);

        // --- ORGANIC LAKE SHORELINE WARPING MODULATION ---
        float plateauInput = isUpliftContinent ? cell.waterTable : currT;
        int plateauIndex = ContinentalHydrology.getStepId(plateauInput);
        float widenMultiplier = 1.0F;

        if (this.shouldWidenOnPlateau(plateauIndex, lakeConfig, currT)) {
            float lakeScaleMin = lakeConfig.sizeMin / 100.0F;
            float lakeScaleMax = lakeConfig.sizeMax / 100.0F;

            float baseStepScale = this.getLakeScaleForPlateau(plateauIndex, lakeScaleMin, lakeScaleMax);
            float shorelineWarp = this.lakeWarpNoise.compute(currX, currZ, 7439);
            float organicWarpFactor = baseStepScale * (1.0F + shorelineWarp * 0.45F);

            // --- SMOOTH DISTANCE FADE FACTOR ---
            float distanceMask = 1.0F;
            float fadeWindow = 0.04F;

            if (currT < lakeConfig.distanceMin) {
                distanceMask = NoiseUtil.clamp((currT - (lakeConfig.distanceMin - fadeWindow)) / fadeWindow, 0.0F, 1.0F);
            } else if (currT > lakeConfig.distanceMax) {
                distanceMask = NoiseUtil.clamp(((lakeConfig.distanceMax + fadeWindow) - currT) / fadeWindow, 0.0F, 1.0F);
            }

            distanceMask = distanceMask * distanceMask * (3.0F - 2.0F * distanceMask);

            widenMultiplier = 1.0F + (flatnessFactor * organicWarpFactor * distanceMask);
            zone1Radius *= widenMultiplier;
        }

        // Additive chaining recalculates layout bounds
        float zone2Width = (config.maxBankHeight - config.minBankHeight) / this.levels.unit * biasedScale;
        float zone2Radius = zone1Radius + zone2Width;

        // --- APPLY PER-RIVER VARIANCE TO ZONE 3 VALLEY FLOOR ---
        float zone3Width = config.bankWidth * dynamicWidthMult * this.riverValleyWidthModifier;
        float zone3Radius = zone2Radius + zone3Width;

        float localSlope = heightDiscrepancy / Math.max(zone3Width, 1.0F);
        float slopeFactor = NoiseUtil.clamp(localSlope / (10.0F * this.levels.unit), 0.0F, 3.0F);
        float discrepancyScale = 1.0F + slopeFactor;
        discrepancyScale = NoiseUtil.clamp(discrepancyScale, 1.0F, 6.0F);

        // Zone 4 dynamically accommodates the changes automatically via the chain
        float zone4Radius = zone3Radius + (zone3Width * (4 + discrepancyScale)) * sideBias;
        float slopeRatio = heightDiscrepancy / Math.max(zone3Width, 1.0F);
        float narrowingFactor = NoiseUtil.clamp(slopeRatio / (30.0F * this.levels.unit), 0.0F, 1.0F);
        float mountainNarrowing = 1.0F - (narrowingFactor * mountainFactor * 0.5F);
        zone4Radius *= mountainNarrowing;
        zone4Radius = Math.max(zone4Radius, zone3Radius + zone3Width * 1.5F);

        if (warpedLinearDist >= zone4Radius) return;

        float slopeRoughness = 0.0F;
        float scarRaw = 0.0F;
        float fanNoise = 0.0F;
        if (currentLinearDist >= zone2Radius) {
            slopeRoughness = this.slopeRoughnessNoise.compute(currX, currZ, 2847);
            scarRaw = this.scarNoise.compute(currX, currZ, 7392);
            fanNoise = this.gullyNoise.compute(currX, currZ, 9877);
        }

        // --- 3. PROFILE SELECTION ---
        float finalHeight;

        if (currentLinearDist < zone1Radius) {
            finalHeight = carveZone1Riverbed(cell, currT, distSqToCurr, bedDepthOffset, oceanHeightOffset, sqScaleFactor, targetWaterLevel, widenMultiplier);
            cell.riverZone = RiverCarverSettings.RiverZone.Riverbed;
        } else if (currentLinearDist < zone2Radius) {
            finalHeight = carveZone2BankStep(currentLinearDist, zone1Radius, zone2Radius, targetWaterLevel, targetValleyFloor, terraceMask, drainageMask, flatnessFactor);
            if (cell.riverZone != RiverCarverSettings.RiverZone.Riverbed) {
                cell.riverZone = RiverCarverSettings.RiverZone.Banks;
            }
        } else if (warpedLinearDist < zone3Radius) {
            finalHeight = carveZone3ValleyFloor(targetValleyFloor, terraceMask, drainageMask);
            if (cell.riverZone != RiverCarverSettings.RiverZone.Riverbed && cell.riverZone != RiverCarverSettings.RiverZone.Banks) {
                cell.riverZone = RiverCarverSettings.RiverZone.ValleyFloor;
            }
        } else {
            finalHeight = carveZone4Fadeout(cell.height, warpedLinearDist, zone3Radius, zone4Radius, targetValleyFloor, terraceMask, drainageMask, flatnessFactor, slopeRoughness, scarRaw, fanNoise);
            if (cell.riverZone != RiverCarverSettings.RiverZone.Riverbed && cell.riverZone != RiverCarverSettings.RiverZone.Banks && cell.riverZone != RiverCarverSettings.RiverZone.ValleyFloor) {
                cell.riverZone = RiverCarverSettings.RiverZone.ValleyFadeout;
            }
        }

        if (heightDiscrepancy > 0.0F) {
            // How far from the valley center? zone 1-3 = strong carve, zone 4 = blend more
            float distanceProgress = 0.0F;
            if (warpedLinearDist >= zone3Radius) {
                distanceProgress = (warpedLinearDist - zone3Radius) / (zone4Radius - zone3Radius);
                distanceProgress = NoiseUtil.clamp(distanceProgress, 0.0F, 1.0F);
            }

            // In high-discrepancy terrain, the outer part of zone 4 retains more original slope
            // Inner zones keep full carve influence
            // Only strongly preserve original slope in steep/high-discrepancy terrain
            float blendAmount = 0.5F * mountainFactor * (1.0F + slopeRoughness * 0.3F);
            float carveInfluence = 1.0F - (distanceProgress * discrepancyFactor * blendAmount);

            finalHeight = NoiseUtil.lerp(originalTerrainHeight, finalHeight, carveInfluence);
        }

        if (finalHeight < cell.height) {
            cell.height = finalHeight;
        }

        updateValleyMask(prevX, prevZ, prevT, currX, currZ, currT, distSqToCurr, sqScaleFactor, targetBedFloor, cell);
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

    private float carveZone2BankStep(float distance, float zone1Radius, float zone2Radius,
                                     float targetWaterLevel, float targetValleyFloor, float terraceMask,
                                     float drainageMask, float flatnessFactor) {

        float progress = (distance - zone1Radius) / (zone2Radius - zone1Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        // Flat terrain: subtle bank; Mountain: pronounced bank step
        float bankSteps = NoiseUtil.lerp(4.0F, 2.0F, flatnessFactor);           // flat=2, mountain=4
        float bankEdgeWidth = NoiseUtil.lerp(0.4F, 0.7F, flatnessFactor);       // flat=0.7, mountain=0.4
        float bankTerraceStrength = NoiseUtil.lerp(0.8F, 0.5F, flatnessFactor);   // flat=0.5, mountain=0.8
        progress = applyTerracing(progress, terraceMask, drainageMask, bankSteps, bankEdgeWidth, bankTerraceStrength);

        // Drainage gullies are more aggressive on steep terrain
        float drainageScale = NoiseUtil.lerp(0.2F, 0.5F, 1.0F - flatnessFactor);
        float arc = progress * (1.0F - progress) * 4.0F;
        progress = Math.max(0.0F, progress - (drainageMask * drainageScale * arc));

        float smoothProgress = progress * progress * (3.0F - 2.0F * progress);
        return NoiseUtil.lerp(targetWaterLevel, targetValleyFloor, smoothProgress);
    }

    private float carveZone3ValleyFloor(float targetValleyFloor, float terraceMask, float drainageMask) {
        float bumpiness = (terraceMask * 0.4F) - (drainageMask * 0.6F);
        return targetValleyFloor + (bumpiness * this.levels.unit);
    }

    private float carveZone4Fadeout(float originalTerrainHeight, float distance,
                                    float zone3Radius, float zone4Radius, float targetValleyFloor,
                                    float terraceMask, float drainageMask, float flatnessFactor,
                                    float slopeRoughness, float scarRaw, float fanNoise) {

        float progress = (distance - zone3Radius) / (zone4Radius - zone3Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        // Physical slope mask - strongest at mid-slope
        float slopeMask = progress * (1.0F - progress) * 4.0F;

        // Terracing + drainage on progress
        float terrainSteps = NoiseUtil.lerp(7.0F, 3.0F, flatnessFactor) * (0.8F + terraceMask * 0.4F);
        float terrainEdgeWidth = NoiseUtil.lerp(0.25F, 0.6F, flatnessFactor) * (0.7F + terraceMask * 0.6F);
        float terrainTerraceStrength = NoiseUtil.lerp(0.8F, 0.5F, flatnessFactor);
        float modifiedProgress = applyTerracing(progress, terraceMask, drainageMask,
                terrainSteps, terrainEdgeWidth, terrainTerraceStrength);
        modifiedProgress = Math.max(0.0F, modifiedProgress - (drainageMask * 0.25F * slopeMask));

        // Profile shape: concave talus apron at base, steep cliff above
        float profileProgress;
        if (modifiedProgress < 0.35F) {
            float p = modifiedProgress / 0.35F;
            profileProgress = 0.35F * p * p * (3.0F - 2.0F * p); // goes 0 -> 0.35
        } else {
            float p = (modifiedProgress - 0.35F) / 0.65F;
            profileProgress = 0.35F + p * 0.65F; // goes 0.35 -> 1
        }

        // Threshold slope clip
        float thresholdSlope = NoiseUtil.lerp(this.steepSlope, this.gentleSlope, flatnessFactor);
        float horizontalDistance = distance - zone3Radius;
        float maxRise = horizontalDistance * thresholdSlope;
        float thresholdHeight = targetValleyFloor + maxRise;

        float smoothHeight = NoiseUtil.lerp(targetValleyFloor, originalTerrainHeight, profileProgress);
        float clippedHeight = Math.min(smoothHeight, thresholdHeight);

        // Slope roughness
        float roughnessAmount = slopeMask * (1.0F - flatnessFactor) * 4.0F * this.levels.unit;
        float finalHeight = clippedHeight + slopeRoughness * roughnessAmount;

        // Debris fans / talus apron at the base
        float fanStrength = Math.max(0.0F, fanNoise) * (1.0F - flatnessFactor) * (1.0F - progress);
        float fanHeight = fanStrength * 3.0F * this.levels.unit;
        float fanTargetHeight = targetValleyFloor + fanHeight;
        finalHeight = NoiseUtil.lerp(finalHeight, fanTargetHeight, fanStrength);

        // Landslide scars
        if (scarRaw > 0.85F) {
            float scarDepth = (scarRaw - 0.85F) / 0.15F;
            scarDepth *= scarDepth;
            finalHeight -= scarDepth * (1.0F - flatnessFactor) * 6.0F * this.levels.unit * slopeMask;
        }

        return finalHeight;
    }

    private float applyTerracing(float progress, float terraceMask, float drainageMask, float steps, float edgeWidth, float terraceStrength) {
        float intactTerrace = Math.max(0.0F, terraceMask - (drainageMask * 1.5F));
        float maskStrength = NoiseUtil.clamp(intactTerrace * 1.5F, 0.0F, 1.0F);
        float effectiveStrength = NoiseUtil.lerp(maskStrength, terraceStrength, 0.5F);

        if (effectiveStrength > 0.0F) {
            float steppedProgress = softStep(progress, steps, edgeWidth);
            return NoiseUtil.lerp(progress, steppedProgress, effectiveStrength * 0.65F);
        }
        return progress;
    }

    private float softStep(float progress, float steps, float edgeWidth) {
        if (progress <= 0.0F) return 0.0F;
        if (progress >= 1.0F) return 1.0F;
        if (edgeWidth <= 0.0F) {
            return (float) Math.ceil(progress * steps) / steps;
        }

        float scaled = progress * steps;
        float stepIndex = (float) Math.floor(scaled);
        float fractional = scaled - stepIndex; // 0..1 within each step

        // We want the RISER (wall face) on the INNER side (low fractional)
        // and the FLAT terrace on the OUTER side (high fractional)
        //
        // Think of each step as:
        //   [0..riserWidth)  = riser zone  (steep, faces center)
        //   [riserWidth..1)  = flat zone   (horizontal terrace)
        //
        float riserWidth = NoiseUtil.clamp(edgeWidth, 0.0F, 1.0F);

        float result;
        if (fractional < riserWidth) {
            // In the riser zone — transition from this step's floor up to the terrace level
            float transitionProgress = fractional / riserWidth;
            // Smooth with hermite interpolation
            transitionProgress = transitionProgress * transitionProgress * (3.0F - 2.0F * transitionProgress);

            float stepFloor = stepIndex / steps;
            float stepCeiling = (stepIndex + 1.0F) / steps;
            result = NoiseUtil.lerp(stepFloor, stepCeiling, transitionProgress);
        } else {
            // On the flat terrace — snap to the step ceiling
            result = (stepIndex + 1.0F) / steps;
        }

        return result;
    }

    private void updateValleyMask(float prevX, float prevZ, float prevT, float currX, float currZ, float currT, float distSqToCurr, float sqScaleFactor, float targetBedFloor, Cell cell) {
        float distSqToPrev = this.getDistance2(prevX, prevZ, prevT);
        float valleyInfluence = this.getDistanceAlpha(currT, Math.min(distSqToCurr, distSqToPrev), this.valleyWidth, sqScaleFactor);
        if (valleyInfluence > 0.0F) {
            valleyInfluence = this.valleyCurve.apply(valleyInfluence);
            cell.riverMask = Math.min(cell.riverMask, 1.0F - valleyInfluence);
        }
    }

    // --- PLATEAU SELECTION HELPER METHODS ---

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