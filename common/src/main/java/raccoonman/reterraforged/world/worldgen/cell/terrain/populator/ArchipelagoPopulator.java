package raccoonman.reterraforged.world.worldgen.cell.terrain.populator;

import raccoonman.reterraforged.data.worldgen.preset.settings.IslandSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import raccoonman.reterraforged.world.worldgen.biome.Erosion;
import raccoonman.reterraforged.world.worldgen.biome.Weirdness;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.util.Seed;

public class ArchipelagoPopulator implements CellPopulator {
    private static final float DOME_EXPONENT_MIN = 1.3F;
    private static final float DOME_EXPONENT_MAX = 3.6F;
    private static final float DOME_HEIGHT_SCALE = 0.95F;

    private static final float CHANNEL_DEPTH_MIN = 0.08F;
    private static final float CHANNEL_DEPTH_MAX = 0.32F;
    private static final float PEAK_DRIFT_STRENGTH = 0.35F;
    private static final float MAX_VALLEY_CARVE_FRACTION = 0.55F;

    private static final float BASE_SUMMIT_PERTURB_STRENGTH = 0.42F;

    private IslandSettings settings;
    private Levels levels;
    private ControlPoints controlPoints;
    private int oceanDepth;

    private float oceanDepthScale;
    private float islandSizeScale;
    private float falloffStartThreshold;

    private Noise sizeNoise;
    private Noise densityNoise;
    private Noise regionNoise;

    private Noise peakDrift;
    private Noise channelPattern;
    private Noise summitPerturb;

    private Noise beachVariance;

    // Coastal & underwater noise overlays
    private Noise angularCoastalNoise;      // atan2(gz, gx) direction-driven noise for broken contours
    private Noise coastalModulationNoise;  // Secondary noise to vary intensity/characteristics spatially
    private Noise shelfDetailNoise;        // Fine detail for subsea shelf variation

    // Below-waterline surface detail
    private Noise oceanFloorRidge;      // Fine ridged noise — additive texture on the seabed/shelf surface
    private Noise oceanFloorVariance;   // Broad low-frequency mask — varies detail amplitude by region

    private Noise islandErosion;
    private Noise islandWeirdness;
    private Noise beachErosion;
    private Noise beachWeirdness;

    private float domeExponent;
    private float summitPerturbStrength;
    private float channelDepthScale;
    private float valleySharpness;
    private float channelWarpDist;
    private float gradientStep;
    private float macroDensityPercentage;

    public ArchipelagoPopulator(IslandSettings settings, Levels levels, ControlPoints controlPoints, Seed seed, int oceanDepth) {
        this.settings = settings;
        this.levels = levels;
        this.controlPoints = controlPoints;
        this.oceanDepth = oceanDepth;
        int salt = seed.get();

        int size = Math.round(settings.islandSize);
        float hScale = Math.max(0.1F, settings.islandHorizontalScale);
        float mountainHScale = Math.max(0.1F, settings.mountainHorizontalScale);
        float volcanismHScale = Math.max(0.1F, settings.volcanismHorizontalScale);

        // Falloff scaling multipliers
        this.oceanDepthScale = Math.max(1.0F, (float) oceanDepth / 30.0F);
        this.islandSizeScale = Math.max(0.5F, (float) size / 185.0F);
        // Start underwater shape transition earlier (lower noise threshold) to widen underwater apron ~3x
        this.falloffStartThreshold = NoiseUtil.clamp(0.5F - (0.35F * 3.0F * this.islandSizeScale * this.oceanDepthScale), 0.01F, 0.40F);

        this.macroDensityPercentage = NoiseUtil.clamp(this.settings.macroDensityPercentage, 0.0F, 1.0F);

        // MACRO FOOTPRINT: Scales ONLY with islandSize
        Noise sizeN = Noises.simplex(1273 + salt, Math.max(1, Math.round(size * 3.5F)), 3);
        sizeN = Noises.warpPerlin(sizeN, 1273 + salt, Math.max(1, Math.round(size * 2.0F)), 2, size * 0.5F);
        sizeN = Noises.warpPerlin(sizeN, 4830 + salt, Math.max(1, Math.round(size * 0.5F)), 1, size * 0.3F);
        sizeN = Noises.warpPerlin(sizeN, 8932 + salt, Math.max(1, Math.round(size * 0.08F)), 2, size * 0.15F);
        sizeN = Noises.clamp(sizeN, 0.0F, 1.0F);
        this.sizeNoise = sizeN;

        Noise densityN = Noises.simplex(9735 + salt, 4000, 3);
        densityN = Noises.warpPerlin(densityN, 9735 + salt, 2000, 2, 1000.0F);
        densityN = Noises.clamp(densityN, 0.0F, 1.0F);
        this.densityNoise = densityN;

        Noise regionN = Noises.simplex(1492 + salt, 12000, 2);
        regionN = Noises.warpPerlin(regionN, 1492 + salt, 4000, 2, 2000.0F);
        regionN = Noises.clamp(regionN, 0.0F, 1.0F);
        this.regionNoise = regionN;

        // INTERNAL & COASTAL FEATURES: Scale using islandHorizontalScale
        this.peakDrift = Noises.simplex(3391 + salt, Math.max(1, Math.round(size * 1.2F * mountainHScale * hScale)), 1);
        this.channelPattern = Noises.simplex(7213 + salt, Math.max(1, Math.round(size * 0.32F * volcanismHScale * hScale)), 2);
        this.summitPerturb = Noises.simplex(5107 + salt, Math.max(1, Math.round(size * 0.4F * mountainHScale * hScale)), 2);
        this.beachVariance = Noises.simplex(5541 + salt, Math.max(1, Math.round(size * 0.21F * hScale)), 2);

        // Angular and modulation noises for broken coastal/subsea falloff
        this.angularCoastalNoise = Noises.simplex(8821 + salt, Math.max(1, Math.round(size * 0.25F * hScale)), 2);
        this.coastalModulationNoise = Noises.simplex(4109 + salt, Math.max(1, Math.round(size * 1.5F)), 2);
        this.shelfDetailNoise = Noises.simplex(6317 + salt, Math.max(1, Math.round(size * 0.12F * hScale)), 3);

        // Below-waterline surface detail
        this.oceanFloorRidge = Noises.perlinRidge(2718 + salt, Math.max(1, Math.round(size * 0.10F * hScale)), 3, 2.0F, 0.85F);
        this.oceanFloorVariance = Noises.simplex(3141 + salt, Math.max(1, Math.round(size * 2.2F)), 2);

        this.islandErosion = Erosion.LEVEL_4.source();
        this.islandWeirdness = Weirdness.MID_SLICE_NORMAL_DESCENDING.source();
        this.beachErosion = Erosion.LEVEL_4.source();
        this.beachWeirdness = Weirdness.MID_SLICE_NORMAL_DESCENDING.source();

        float mScale = NoiseUtil.clamp(this.settings.mountainScale, 0.0F, 1.0F);
        float mChance = NoiseUtil.clamp(this.settings.mountainChance, 0.0F, 1.0F);
        this.domeExponent = NoiseUtil.lerp(DOME_EXPONENT_MIN, DOME_EXPONENT_MAX, mScale);
        this.summitPerturbStrength = BASE_SUMMIT_PERTURB_STRENGTH * (0.4F + mScale * 0.8F) * mChance;

        float vScale = NoiseUtil.clamp(this.settings.volcanismScale, 0.0F, 1.0F);
        float vChance = NoiseUtil.clamp(this.settings.volcanoChance, 0.0F, 1.0F);
        this.channelDepthScale = NoiseUtil.lerp(CHANNEL_DEPTH_MIN, CHANNEL_DEPTH_MAX, vScale) * vChance;
        this.valleySharpness = NoiseUtil.lerp(1.1F, 2.5F, vScale);

        this.channelWarpDist = Math.max(1.0F, size * 0.08F * volcanismHScale * hScale);
        this.gradientStep = Math.max(0.75F, size * 0.02F * volcanismHScale * hScale);
    }

    private float macroDensityMask(float x, float z) {
        float densityValue = this.densityNoise.compute(x, z, 0);
        float regionValue = this.regionNoise.compute(x, z, 0);

        float regionThreshold = NoiseUtil.lerp(1.05F, -0.25F, this.macroDensityPercentage);
        float regionAlpha = smoothStep(regionThreshold, regionThreshold + 0.25F, regionValue);

        float densityThreshold = NoiseUtil.clamp(1.0F - this.settings.islandDensity * 0.8F, 0.05F, 0.98F);
        // Expanded density fade range to make macro-region transitions wider and smoother
        float densityFade = NoiseUtil.clamp((1.0F - densityThreshold) * 0.5F, 0.12F, 0.30F);
        float densityAlpha = smoothStep(densityThreshold, densityThreshold + densityFade, densityValue);

        return densityAlpha * regionAlpha;
    }

    private float rawShape(float x, float z) {
        float totalDensityMask = this.macroDensityMask(x, z);
        if (totalDensityMask <= 0.001F) {
            return 0.0F;
        }

        float sizeValue = this.sizeNoise.compute(x, z, 0);

        // Expanded transition range extending outer apron smoothly outward
        float shapeAlpha = smoothStep(this.falloffStartThreshold, 1.0F, sizeValue);
        float drift = this.peakDrift.compute(x, z, 0) * PEAK_DRIFT_STRENGTH;
        float driftedShape = NoiseUtil.clamp(shapeAlpha + drift * shapeAlpha, 0.0F, 1.0F);

        return driftedShape * totalDensityMask;
    }

    @Override
    public void apply(Cell cell, float x, float z) {
        float originalContinentEdge = cell.continentEdge;
        float originalHeight = cell.height;

        float shape = this.rawShape(x, z);

        float fadeStart = this.controlPoints.islandCoast;
        float fadeEnd = this.controlPoints.deepOcean;
        float continentFade = 1.0F - smoothStep(fadeStart, fadeEnd, originalContinentEdge);

        float islandAlpha = shape * continentFade;
        if (islandAlpha <= 0.001F) {
            return;
        }

        // Gradient & atan2 polar angle calculation for angular coastal features
        float shapeXOffset = this.rawShape(x + this.gradientStep, z);
        float shapeZOffset = this.rawShape(x, z + this.gradientStep);
        float gx = (shapeXOffset - shape) / this.gradientStep;
        float gz = (shapeZOffset - shape) / this.gradientStep;
        float gradMagSq = gx * gx + gz * gz;

        float angle = (gradMagSq > 1.0e-8F) ? (float) Math.atan2(gz, gx) : 0.0F;
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);

        // Spatial modulation noise: controls local intensity of angular features and shelf width
        float modulation = this.coastalModulationNoise.compute(x * 0.005F, z * 0.005F, 0);
        float modFactor = 0.5F + 0.5F * modulation; // 0.0 to 1.0

        // atan2 polar noise overlay + fine detail
        float angularNoiseVal = this.angularCoastalNoise.compute(cosA * 2.5F + x * 0.008F, sinA * 2.5F + z * 0.008F, 0);
        float detailNoiseVal = this.shelfDetailNoise.compute(x * 0.02F, z * 0.02F, 0);
        float coastalNoise = (angularNoiseVal * 0.65F + detailNoiseVal * 0.35F) * (0.2F + 0.8F * modFactor);

        // Perturb island alpha in underwater & coastal zone; scale noise perturbation down near islandAlpha = 0
        // to prevent abrupt step-jumps right at the early-return boundary.
        float noiseFade = smoothStep(0.0F, 0.12F, islandAlpha);
        float perturbedAlpha = NoiseUtil.clamp(islandAlpha + coastalNoise * 0.18F * (1.0F - islandAlpha * 0.8F) * noiseFade, 0.0F, 1.0F);

        // Subsea Falloff Width
        float baseBeachWidth = NoiseUtil.clamp(Math.max(0.05F, this.settings.beachWidth), 0.05F, 0.45F);
        float beachCoverage = NoiseUtil.clamp(this.settings.beachCoverage, 0.0F, 1.0F);
        float widthMultiplier = 3.0F * this.islandSizeScale * this.oceanDepthScale * (0.7F + 0.6F * modFactor);

        float shelfEnd = NoiseUtil.clamp(baseBeachWidth * 0.65F * widthMultiplier, 0.12F, 0.60F);

        float rawVariance = this.beachVariance.compute(x, z, 0) + coastalNoise * 0.5F;
        float cliffFactor = smoothStep(-0.2F, 0.6F, rawVariance);
        float activeBeachWidth = NoiseUtil.lerp(baseBeachWidth * widthMultiplier, 0.02F, cliffFactor);
        float coastEnd = NoiseUtil.clamp(shelfEnd + (activeBeachWidth * 0.5F), shelfEnd + 0.01F, 0.85F);
        float baseBeachEnd = coastEnd + (activeBeachWidth * beachCoverage * 1.5F);
        float bVariance = rawVariance * 0.15F * (1.0F - cliffFactor) - 0.05F * (1.0F - cliffFactor);
        float dynamicBeachEnd = NoiseUtil.clamp(baseBeachEnd + bVariance, coastEnd + 0.01F, 0.90F);

        // Macro region mask at this cell
        float regionMask = this.macroDensityMask(x, z);

        // Deep Ocean floor height scaled by oceanDepth parameter
        float trueOceanFloor = this.levels.water(-this.oceanDepth);
        float clampedOceanFloor = Math.min(originalHeight, trueOceanFloor);

        // Fade the ocean floor carve smoothly as perturbedAlpha approaches zero.
        // This ensures oceanFloorHeight seamlessly matches originalHeight at the outer edge with no cliff artifact.
        float floorCarveAlpha = regionMask * smoothStep(0.0F, shelfEnd, perturbedAlpha);
        float oceanFloorHeight = NoiseUtil.lerp(originalHeight, clampedOceanFloor, floorCarveAlpha);

        // Shallow shelf depth target
        int shelfDepth = Math.max(2, Math.round(3.0F + this.settings.offshoreDepth * 8.0F));
        float shelfTarget = Math.max(oceanFloorHeight, this.levels.water(-shelfDepth));

        // Smooth non-linear subsea falloff from deep ocean floor to shallow shelf
        float shelfAlpha = smoothStep(0.0F, shelfEnd, perturbedAlpha);
        float smoothFalloff = (float) Math.pow(shelfAlpha, 1.2F);
        float shelfHeight = NoiseUtil.lerp(oceanFloorHeight, shelfTarget, smoothFalloff);

        // Below-waterline surface detail
        float regionVarianceValue = 0.5F + 0.5F * this.oceanFloorVariance.compute(x, z, 0);
        float ridgeValue = NoiseUtil.clamp(this.oceanFloorRidge.compute(x, z, 0), 0.0F, 1.0F);

        float onsetWidth = shelfEnd * 0.3F;
        float onsetFade = smoothStep(0.0F, onsetWidth, perturbedAlpha);

        float shoreFadeWidth = shelfEnd * 0.20F;
        float shoreFadeStart = Math.max(0.0F, shelfEnd - shoreFadeWidth);
        float shoreFade = 1.0F - smoothStep(shoreFadeStart, shelfEnd, perturbedAlpha);

        float detailFade = onsetFade * shoreFade;

        float detailAmplitudeBlocks = NoiseUtil.lerp(1.5F, 6.0F, regionVarianceValue) * (0.4F + 0.6F * this.oceanDepthScale);
        float oceanFloorDetailBlocks = ridgeValue * detailAmplitudeBlocks * detailFade;

        shelfHeight += oceanFloorDetailBlocks / this.levels.terrainScaleFactor;

        float coastAlpha = smoothStep(shelfEnd, coastEnd, perturbedAlpha);
        float beachHeight = NoiseUtil.lerp(shelfHeight, this.levels.ground, coastAlpha);

        float landTransitionEnd = NoiseUtil.clamp(NoiseUtil.lerp(1.0F, dynamicBeachEnd + 0.32F, cliffFactor), dynamicBeachEnd + 0.05F, 1.0F);
        float landAlpha = smoothStep(dynamicBeachEnd, landTransitionEnd, perturbedAlpha);

        float inlandBase = landAlpha * this.settings.islandHeight * (0.035F + this.settings.islandBaseScale * 0.10F);

        float macroDome = shape;
        float linearDome = macroDome;
        float exponentialDome = (float) Math.pow(macroDome, this.domeExponent);
        float domeShape = NoiseUtil.lerp(linearDome * 0.40F, exponentialDome, macroDome);
        float domeContribution = domeShape * this.settings.islandHeight * this.settings.islandVerticalScale * DOME_HEIGHT_SCALE;

        float summitInfluence = smoothStep(0.6F, 0.95F, macroDome);
        float summitPerturbValue = this.summitPerturb.compute(x, z, 0) * summitInfluence * this.summitPerturbStrength;
        domeContribution += summitPerturbValue * this.settings.islandHeight * this.settings.islandVerticalScale;

        float carveDepth = 0.0F;
        if (gradMagSq > 1.0e-8F) {
            float invMag = 1.0F / (float) Math.sqrt(gradMagSq);
            float dirX = gx * invMag;
            float dirZ = gz * invMag;

            float warpedX = x + dirX * this.channelWarpDist;
            float warpedZ = z + dirZ * this.channelWarpDist;
            float channelValue = this.channelPattern.compute(warpedX, warpedZ, 0);

            float channelMask = NoiseUtil.clamp(1.0F - Math.abs(channelValue) * 1.6F, 0.0F, 1.0F);
            channelMask = (float) Math.pow(channelMask, this.valleySharpness);

            float carveEnvelope = smoothStep(0.28F, 0.45F, macroDome) * (1.0F - smoothStep(0.58F, 0.85F, macroDome));

            carveDepth = channelMask * carveEnvelope * this.channelDepthScale * this.settings.islandHeight * this.settings.islandVerticalScale;
        }

        float effectiveCarve = Math.min(carveDepth, domeContribution * MAX_VALLEY_CARVE_FRACTION);
        float reliefHeight = Math.max(0.0F, domeContribution - effectiveCarve);

        float targetHeight = this.levels.ground + inlandBase + reliefHeight;

        cell.height = NoiseUtil.lerp(beachHeight, targetHeight, landAlpha);
        cell.continentEdge = Math.max(originalContinentEdge, continentEdge(perturbedAlpha, shelfEnd, dynamicBeachEnd));

        if (perturbedAlpha < shelfEnd) {
            if (perturbedAlpha >= 0.01F) {
                cell.terrain = TerrainType.SHALLOW_OCEAN;
            }
        } else if (perturbedAlpha < dynamicBeachEnd) {
            cell.terrain = TerrainType.ISLAND_BEACH;
        } else if (macroDome > 0.5F && landAlpha > 0.5F && this.settings.mountainChance > 0.05F) {
            cell.terrain = TerrainType.ISLAND_MOUNTAINS;
        } else {
            cell.terrain = TerrainType.ISLAND;
        }

        if (perturbedAlpha >= shelfEnd) {
            if (cell.terrain == TerrainType.ISLAND_BEACH) {
                cell.erosion = this.beachErosion.compute(x, z, 0);
                cell.weirdness = this.beachWeirdness.compute(x, z, 0);
            } else {
                cell.erosion = this.islandErosion.compute(x, z, 0);
                cell.weirdness = this.islandWeirdness.compute(x, z, 0);
            }
        }
    }

    private float continentEdge(float islandAlpha, float shelfEnd, float beachEnd) {
        if (islandAlpha < shelfEnd) {
            float alpha = smoothStep(0.0F, shelfEnd, islandAlpha);
            return NoiseUtil.lerp(this.controlPoints.deepOcean, this.controlPoints.shallowOcean, alpha);
        }
        if (islandAlpha < beachEnd) {
            float alpha = smoothStep(shelfEnd, beachEnd, islandAlpha);
            return NoiseUtil.lerp(this.controlPoints.shallowOcean, this.controlPoints.coast, alpha);
        }
        float alpha = smoothStep(beachEnd, 1.0F, islandAlpha);
        return NoiseUtil.lerp(this.controlPoints.coast, this.controlPoints.inland, alpha);
    }

    private static float smoothStep(float min, float max, float value) {
        if (max <= min) {
            return value >= max ? 1.0F : 0.0F;
        }
        float alpha = NoiseUtil.clamp((value - min) / (max - min), 0.0F, 1.0F);
        return alpha * alpha * (3.0F - 2.0F * alpha);
    }
}