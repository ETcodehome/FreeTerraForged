package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.function.CurveFunction;
import etcodehome.freeterraforged.world.worldgen.noise.module.Line;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public class UpliftRiverCarver implements FTFRiverCarver {
    public static final float DEFAULT_MAX_ZONE4_RADIUS = 1100.0F;
    public static final float DEFAULT_MAX_ZONE4_RADIUS_SQ = DEFAULT_MAX_ZONE4_RADIUS * DEFAULT_MAX_ZONE4_RADIUS;
    public static final float MAX_ZONE4_RADIUS = DEFAULT_MAX_ZONE4_RADIUS;
    public static final float MAX_ZONE4_RADIUS_SQ = DEFAULT_MAX_ZONE4_RADIUS_SQ;

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

    // Precomputed immutable constants to alleviate CPU overhead per cell
    private final float bankHeightOffset;
    private final float baseBedDepthOffset;
    private final float zone2WidthFactor;
    private final float maxZone4Radius;
    private final float maxZone4RadiusSq;

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

        // Calculate constants once during instantiation
        this.bankHeightOffset = config.maxBankHeight - config.minBankHeight;
        this.baseBedDepthOffset = levels.water - config.bedHeight;
        this.zone2WidthFactor = this.bankHeightOffset / levels.unit;
        this.maxZone4Radius = calculateMaxRadius(config, settings, levels, lakeConfig);
        this.maxZone4RadiusSq = this.maxZone4Radius * this.maxZone4Radius;
    }

    @Override
    public void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT) {

        // Fixed reference values
        float distSqToCurr = this.getDistance2(currX, currZ, currT);

        // Pre-bail if beyond the optimal calculated maximum zone 4 radius for this river/preset.
        // Avoids Math.sqrt, 3 layout noise samples, and valley mask math for outer bounding box corners.
        if (distSqToCurr > this.maxZone4RadiusSq) {
            return;
        }

        float currentLinearDist = (float) Math.sqrt(distSqToCurr);
        float flatnessInput = isUpliftContinent ? cell.waterTable : currT;
        float flatnessFactor = NoiseUtil.clamp(ContinentalHydrology.getFlatnessFactor(flatnessInput), 0.0F, 1.0F);
        float scaleFactor = 1.0F;

        // Step 1: Sample ONLY layout-critical noise arrays to determine structural boundaries
        float widthVar = this.widthNoise.compute(currX, currZ, 8241);
        float asymmetry = this.asymmetryNoise.compute(currX, currZ, 1193);
        float valleyPinchVar = this.valleyPinchNoise.compute(currX, currZ, 6204) * 2.0F;

        // Always run mask logic to ensure clean falloffs
        updateValleyMask(prevX, prevZ, prevT, currT, distSqToCurr, scaleFactor, cell);

        // Compute layout parameters
        float dynamicWidthMult = 1.0F + (widthVar * 0.35F);
        float sideBias = 1.0F + (asymmetry * 0.4F);
        float valleyPinchMultiplier = NoiseUtil.clamp(1.0F + valleyPinchVar, 0.05F, 1.95F);

        // Zone radius calculations
        float biasedScale = scaleFactor * dynamicWidthMult * sideBias;
        float zone1Radius = (float) Math.sqrt(this.getScaledSize(currT, this.bedWidth) * biasedScale);
        float lakeMultiplier = getLakeMultiplier(cell, currT, currX, currZ, flatnessFactor);
        zone1Radius *= lakeMultiplier;

        float zone2Width = this.zone2WidthFactor * biasedScale;
        float zone2Radius = zone1Radius + zone2Width;
        float unshrunkZone3BaseWidth = config.bankWidth * dynamicWidthMult * valleyPinchMultiplier;
        float shrinkFactor = NoiseUtil.clamp(currT * this.fadeInv, 0.0F, 1.0F);
        float zone3BaseWidth = unshrunkZone3BaseWidth * shrinkFactor;
        float zone3Width = zone3BaseWidth * shrinkFactor;
        float zone3Radius = zone2Radius + zone3Width;

        float targetWaterLevel =
            (ContinentalHydrology.getComplexWaterHeight(
                    cell.waterTable,
                    cell.globalContinentScale,
                    cell.continentSizeModifier)
            ) + levels.water;
        float targetValleyFloor = targetWaterLevel + this.bankHeightOffset;

        // Approach A: Slope-Enforcing Mountain Fadeout
        // Instead of tying mountain fadeout width strictly to bankWidth / 100 (which only yielded ~0.24 blocks
        // horizontal run per 1 block rise, creating 75-85 degree walls), scale horizontal fadeout run directly
        // with vertical elevation rise (heightDiffBlocks * 1.8F).
        // This guarantees a natural mountain valley flank (~22-26 degrees) regardless of mountain height.
        float heightDiff = Math.max(0.0F, cell.height - targetValleyFloor);
        int heightDiffBlocks = this.levels.scale(heightDiff);

        float baseFadeWidth = unshrunkZone3BaseWidth * 3.5F;
        float mountainFadeExtension = heightDiffBlocks * 1.8F;
        float zone4Width = baseFadeWidth + mountainFadeExtension;

        // Approach B: Fail-safe bounding clamp
        // Ensure zone4Radius never approaches the bounding box margin (cap at this.maxZone4Radius)
        // so that fadeout is 100% complete and fully restores natural terrain height well before any spatial bounds.
        float zone4Radius = Math.min(zone3Radius + zone4Width, this.maxZone4Radius);

        // Step 2: Early Exit Guard. If outside the maximum radius, skip the remaining expensive operations
        if (currentLinearDist >= zone4Radius) return;

        // Step 3: Defer remaining heavy noise evaluations until we are guaranteed to modify the cell
        float depthVar = this.depthNoise.compute(currX, currZ, 3912);
        float terraceMask = this.terraceNoise.compute(currX, currZ, 5510);
        float gullyRaw = this.gullyNoise.compute(currX, currZ, 9876);
        float rivuletRaw = this.rivuletNoise.compute(currX, currZ, 5432);

        // Drainage calculation adjustments
        float gullyShape = 1.0F - Math.abs(gullyRaw);
        gullyShape *= gullyShape;
        float rivuletShape = 1.0F - Math.abs(rivuletRaw);
        rivuletShape = rivuletShape * rivuletShape * rivuletShape;
        float drainageMask = (gullyShape * 0.7F) + (rivuletShape * 0.3F);

        // Calculate dynamic depth multiplier and apply downstream depth progression logic
        float dynamicDepthMult = 1.0F + (depthVar * 0.25F);
        float depthProgress = NoiseUtil.clamp(currT, 0.0F, 1.0F);
        float bedDepthOffset = this.baseBedDepthOffset * dynamicDepthMult * depthProgress;

        float valleyFloorBumpiness = ((terraceMask * 0.4F) - (drainageMask * 0.6F)) * this.levels.unit;
        float actualValleyFloorHeight = targetValleyFloor + valleyFloorBumpiness;

        // calculate the final cell heights
        float finalHeight = cell.height;
        if (currentLinearDist < zone1Radius) {
            finalHeight = carveZone1Riverbed(cell, currT, distSqToCurr, bedDepthOffset, scaleFactor, targetWaterLevel, lakeMultiplier, flatnessFactor, depthVar);
        } else if (currentLinearDist < zone2Radius) {
            finalHeight = carveZone2BankStep(currentLinearDist, zone1Radius, zone2Radius, targetWaterLevel, actualValleyFloorHeight, terraceMask, drainageMask);
        } else if (currentLinearDist < zone3Radius) {
            finalHeight = actualValleyFloorHeight;
        } else {
            finalHeight = carveZone4Fadeout(cell.height, currentLinearDist, zone3Radius, zone4Radius, actualValleyFloorHeight, terraceMask, drainageMask);
        }

        boolean carvedThisPass = finalHeight < cell.height;
        if (carvedThisPass) {
            cell.height = finalHeight;
            cell.riverZone = getRiverZoneTag(cell, currentLinearDist, zone1Radius, zone2Radius, zone3Radius, finalHeight, targetWaterLevel);
        }

        // Only this river's own zone1 (riverbed), carved on this exact pass, may claim flow.
        boolean isSubMerged = currentLinearDist < zone1Radius
                && carvedThisPass
                && finalHeight < targetWaterLevel;

        if (isSubMerged) {
            storeFlowDirection(cell, currX, currZ, currT, zone1Radius, currentLinearDist, lakeMultiplier);
        }
    }

    private void storeFlowDirection(Cell cell, float currX, float currZ, float currT, float zone1Radius, float currentLinearDist, float lakeMultiplier) {
        float dx = this.river.dx;
        float dz = this.river.dz;
        float segmentLength = (float) Math.sqrt(dx * dx + dz * dz);

        if (segmentLength < 0.0001F) {
            cell.hasFlow = false;
            cell.flowAngle = 0;
            return;
        }

        // 1. Pure downstream unit vector along the river spine
        float dsX = dx / segmentLength;
        float dsZ = dz / segmentLength;

        // 2. Projected point on spine (clamped strictly to segment bounds)
        float clampedT = NoiseUtil.clamp(currT, 0.0F, 1.0F);
        float projX = this.river.x1 + clampedT * dx;
        float projZ = this.river.z1 + clampedT * dz;

        // 3. Compute normalized (unit) inward direction vector
        float inwardX = projX - currX;
        float inwardZ = projZ - currZ;
        float inwardDist = (float) Math.sqrt(inwardX * inwardX + inwardZ * inwardZ);

        if (inwardDist > 0.0001F) {
            inwardX /= inwardDist;
            inwardZ /= inwardDist;
        } else {
            inwardX = 0.0F;
            inwardZ = 0.0F;
        }

        // 4. Inward pull dampening factor (1.0 in standard rivers, drops toward 0.0 in wide lakes)
        float lakeDampening = 1.0F / Math.max(1.0F, lakeMultiplier);

        // 5. Normalized distance across channel (0.0 at center, 1.0 at bank)
        float normalizedDist = zone1Radius > 0.0F ? NoiseUtil.clamp(currentLinearDist / zone1Radius, 0.0F, 1.0F) : 0.0F;

        // 6. Inward turn weight: modest at banks, zero at center, suppressed in open lakes
        float turnWeight = normalizedDist * 0.35F * lakeDampening;

        // 7. Blend downstream vector with capped unit inward vector
        float flowX = dsX + (inwardX * turnWeight);
        float flowZ = dsZ + (inwardZ * turnWeight);

        // 8. Calmer current in open lake bodies
        float velocityMagnitude = (1.0F - (normalizedDist * normalizedDist * 0.50F)) * lakeDampening;

        double radians = Math.atan2(flowZ, flowX);

        cell.flowAngle = ChunkFlowField.pack(velocityMagnitude, radians);
        cell.hasFlow = cell.flowAngle != 0;
    }

    private RiverCarverSettings.RiverZone getRiverZoneTag(Cell cell, float currentLinearDist, float zone1Radius, float zone2Radius, float zone3Radius, float finalHeight, float targetWaterLevel) {
        RiverCarverSettings.RiverZone prospectiveZone = cell.riverZone;
        boolean isSubmerged = finalHeight < (targetWaterLevel - 0.01F);

        if (currentLinearDist < zone1Radius && isSubmerged) {
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

    private float carveZone1Riverbed(Cell cell, float currT, float distSqToCurr, float bedDepthOffset, float sqScaleFactor, float targetWaterLevel, float widenMultiplier, float flatnessFactor, float depthVar) {
        float effectiveScaleFactor = sqScaleFactor * (widenMultiplier * widenMultiplier);
        float bedInfluence = this.getDistanceAlpha(currT, distSqToCurr, this.bedWidth, effectiveScaleFactor);
        bedInfluence = bedInfluence * bedInfluence * (3.0F - 2.0F * bedInfluence);

        // 1. Establish a baseline floor that naturally undulates between ~2.0 and ~2.6 blocks.
        // This ensures headwaters and shallows have organic ripples/sandbars instead of a flat sheet.
        float shallowNoiseFloor = (2.3F + (depthVar * 0.3F)) * this.levels.unit;

        // 2. Base deep-water capability (driven by downstream progress)
        float progressiveDepth = bedDepthOffset;

        // Repurpose depth noise to ensure lake basins break out of uniform parameters natively
        if (widenMultiplier > 1.0F) {
            float lakeDepthMulti = 0.35F + (lakeConfig.depth / 50.0F);
            float lakeVariance = 1.0F + (depthVar * 0.40F);
            progressiveDepth = progressiveDepth * (1.0F + (widenMultiplier - 1.0F) * lakeDepthMulti * lakeVariance);
        }

        // 3. Combine the base floor with progressive depth, scaled by the regional flatness.
        // Low flatness = channel is compacted tightly against our textured shallow floor.
        // High flatness = channel expands deeply away from the baseline.
        float finalizedDepth = shallowNoiseFloor + (progressiveDepth * flatnessFactor);

        // 4. Inject structural deep-pocket trenches specifically when flatness factor is high
        if (flatnessFactor > 0.4F) {
            float flatnessIntensity = (flatnessFactor - 0.4F) / 0.6F;
            float trenchNoise = (depthVar * 0.5F + 0.5F); // Map signed noise safely to [0.0, 1.0]
            float deepPocketBonus = flatnessIntensity * trenchNoise * 3.5F * this.levels.unit * currT;
            finalizedDepth += deepPocketBonus;
        }

        // Hard protective structural guard to catch extreme negative noise spikes
        float absoluteFloor = 2.0F * this.levels.unit;
        if (finalizedDepth < absoluteFloor) {
            finalizedDepth = absoluteFloor;
        }

        float bedHeight = targetWaterLevel - (finalizedDepth * bedInfluence);

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

        float heightDeltaBlocks = this.levels.scale(Math.max(0.0F, originalTerrainHeight - targetValleyFloor));
        float modifiedProgress = applyTerracing(progress, terraceMask, drainageMask, 5.0F, heightDeltaBlocks);

        float slopeMask = progress * (1.0F - progress) * 4.0F;
        modifiedProgress = Math.max(0.0F, modifiedProgress - (drainageMask * 0.25F * slopeMask));

        float smoothProgress = modifiedProgress * modifiedProgress * (3.0F - 2.0F * modifiedProgress);
        return NoiseUtil.lerp(targetValleyFloor, originalTerrainHeight, smoothProgress);
    }

    private float applyTerracing(float progress, float terraceMask, float drainageMask, float steps) {
        return applyTerracing(progress, terraceMask, drainageMask, steps, 0.0F);
    }

    private float applyTerracing(float progress, float terraceMask, float drainageMask, float steps, float heightDeltaBlocks) {
        float intactTerrace = Math.max(0.0F, terraceMask - (drainageMask * 1.5F));

        // Balanced strength multiplier (1.75F) for a confident but natural terrace presence
        float terraceStrength = NoiseUtil.clamp(intactTerrace * 1.75F, 0.0F, 1.0F);

        // Approach C: Height-Adaptive Terracing
        // When cutting through large mountains (> 30 blocks elevation rise),
        // soften cliff bias and terrace strength to prevent 30-block sheer quarry walls.
        float deepCutAlpha = NoiseUtil.clamp((heightDeltaBlocks - 30.0F) / 100.0F, 0.0F, 1.0F);
        terraceStrength = terraceStrength * (1.0F - (deepCutAlpha * 0.45F));

        if (terraceStrength <= 0.0F) {
            return progress;
        }

        float scaledProgress = progress * steps;
        float floor = (float) Math.floor(scaledProgress);
        float fract = scaledProgress - floor;

        // 0.78F puts the cliff face in the upper 22% of the step, distinctly steep but scalable.
        // In deep mountain cuts, soften towards 0.52F to distribute the slope naturally.
        float baseCliffBias = NoiseUtil.lerp(0.78F, 0.52F, deepCutAlpha);
        float cliffBias = NoiseUtil.clamp(baseCliffBias - (drainageMask * 0.14F), 0.45F, 0.88F);

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

        return getDeterministicFloat(riverSeed) < config.chance;
    }

    private float getLakeScaleForPlateau(int plateauIndex, float minScale, float maxScale) {
        int h1 = Float.floatToIntBits(this.river.x1);
        int h2 = Float.floatToIntBits(this.river.z1);
        long riverSeed = ((long) h1 << 32) | (h2 & 0xFFFFFFFFL);

        riverSeed ^= plateauIndex * 0x2545F4914L;

        return minScale + getDeterministicFloat(riverSeed) * (maxScale - minScale);
    }

    /**
     * A stateless, high-quality mixing hash function that maps a long seed
     * to a pseudo-random uniform float in the range [0.0f, 1.0f).
     * Eliminates object allocation completely.
     */
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

        // fade is beyond area of maximum influence
        if (dist2 >= size2) return 0.0F;

        // return a gradient between 1.0 (at the exact center) and 0.0 (right at the edge).
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
        float newMax = Math.max(this.waterLine, bedHeight);
        if (newMax > cell.riverWaterLevel) {
            cell.erosionMask = true;
            cell.terrain = TerrainType.RIVER;
            cell.riverWaterLevel = Math.max(this.waterLine, bedHeight);
        }
    }

    @Override public boolean isMain() { return this.main; }
    @Override public River getRiver() { return this.river; }
    @Override public RiverWarp getWarp() { return this.warp; }
    @Override public RiverConfig getConfig() { return this.config; }

    public float getMaxZone4Radius() { return this.maxZone4Radius; }
    public float getMaxZone4RadiusSq() { return this.maxZone4RadiusSq; }

    public static float calculateMaxRadius(RiverConfig config, RiverCarverSettings settings, Levels levels, LakeConfig lakeConfig) {
        float maxLakeMultiplier = 1.0F;
        if (lakeConfig != null) {
            maxLakeMultiplier += (lakeConfig.sizeMax / 100.0F) * 1.45F;
        }
        float maxZone1 = (float) (config.bedWidth * 1.375F * maxLakeMultiplier);
        float zone2WidthFactor = (config.maxBankHeight - config.minBankHeight) / levels.unit;
        float maxZone2 = zone2WidthFactor * 1.89F;

        // unshrunkZone3BaseWidth = config.bankWidth * dynamicWidthMult * valleyPinchMultiplier
        // dynamicWidthMult <= 1.35F, valleyPinchMultiplier <= 1.95F
        // maxUnshrunkZone3 = config.bankWidth * 1.35F * 1.95F = config.bankWidth * 2.6325F
        // baseFadeWidth = maxUnshrunkZone3 * 3.5F
        // maxZone3AndBaseFade = maxUnshrunkZone3 * (1.0F + 3.5F) = config.bankWidth * 11.84625F
        float maxZone3AndBaseFade = config.bankWidth * 11.84625F;

        // Mountain vertical elevation fade extension:
        // heightDiffBlocks <= levels.worldHeight
        // mountainFadeExtension = heightDiffBlocks * 1.8F <= levels.worldHeight * 1.8F
        float maxMountainFade = levels.worldHeight * 1.8F;

        float maxCarveRadius = maxZone1 + maxZone2 + maxZone3AndBaseFade + maxMountainFade;
        float maxValleyRadius = settings != null ? settings.valleySize * 3.5F : 275.0F * 3.5F;

        return Math.max(maxCarveRadius, maxValleyRadius) + 50.0F;
    }
}