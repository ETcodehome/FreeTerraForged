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

/**
 * Generates archipelago landmasses as a single coherent volcanic landform: a large, smooth
 * summit dome (built entirely from the low-frequency island shape mask, not noise) dissected by
 * erosion channels placed by warping a smooth noise field along the dome's own gradient direction.
 */
public class ArchipelagoPopulator implements CellPopulator {
    // Dome shaping defaults
    private static final float DOME_EXPONENT_MIN = 1.3F;
    private static final float DOME_EXPONENT_MAX = 3.6F;
    private static final float DOME_HEIGHT_SCALE = 0.95F;

    // Radial channel carving defaults
    private static final float CHANNEL_DEPTH_MIN = 0.08F;
    private static final float CHANNEL_DEPTH_MAX = 0.32F;
    private static final float PEAK_DRIFT_STRENGTH = 0.35F;
    private static final float MAX_VALLEY_CARVE_FRACTION = 0.55F; // Channels cannot carve deeper than 55% of dome height

    // Base summit perturbation coefficient
    private static final float BASE_SUMMIT_PERTURB_STRENGTH = 0.42F;

    private IslandSettings settings;
    private Levels levels;
    private ControlPoints controlPoints;

    private Noise sizeNoise;
    private Noise densityNoise;

    private Noise peakDrift;
    private Noise channelPattern;
    private Noise summitPerturb;

    private Noise beachVariance;

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

    public ArchipelagoPopulator(IslandSettings settings, Levels levels, ControlPoints controlPoints, Seed seed) {
        this.settings = settings;
        this.levels = levels;
        this.controlPoints = controlPoints;
        int salt = seed.get();

        int size = Math.round(settings.islandSize);
        float hScale = Math.max(0.1F, settings.islandHorizontalScale);
        float mountainHScale = Math.max(0.1F, settings.mountainHorizontalScale);
        float volcanismHScale = Math.max(0.1F, settings.volcanismHorizontalScale);

        // Coastline & Falloff Distortion
        Noise sizeN = Noises.simplex(1273 + salt, Math.max(1, Math.round(size * 3.5F / hScale)), 3);
        sizeN = Noises.warpPerlin(sizeN, 1273 + salt, Math.max(1, Math.round(size * 2.0F / hScale)), 2, size * 0.5F / hScale);
        sizeN = Noises.warpPerlin(sizeN, 4830 + salt, Math.max(1, Math.round(size * 0.5F / hScale)), 1, size * 0.3F / hScale);
        sizeN = Noises.warpPerlin(sizeN, 8932 + salt, Math.max(1, Math.round(size * 0.08F / hScale)), 2, size * 0.15F / hScale);
        sizeN = Noises.clamp(sizeN, 0.0F, 1.0F);
        this.sizeNoise = sizeN;

        Noise densityN = Noises.simplex(9735 + salt, 4000, 3);
        densityN = Noises.warpPerlin(densityN, 9735 + salt, 2000, 2, 1000.0F);
        densityN = Noises.clamp(densityN, 0.0F, 1.0F);
        this.densityNoise = densityN;

        // Peak drift scaled horizontally by mountainHorizontalScale
        this.peakDrift = Noises.simplex(3391 + salt, Math.max(1, Math.round((size * 1.2F * mountainHScale) / hScale)), 1);

        // Channel pattern frequency scaled horizontally by volcanismHorizontalScale
        this.channelPattern = Noises.simplex(7213 + salt, Math.max(1, Math.round((size * 0.32F * volcanismHScale) / hScale)), 2);

        // Summit relief wavelength scaled horizontally by mountainHorizontalScale
        this.summitPerturb = Noises.simplex(5107 + salt, Math.max(1, Math.round((size * 0.4F * mountainHScale) / hScale)), 2);

        // Beach variance
        this.beachVariance = Noises.simplex(5541 + salt, Math.max(1, Math.round(size * 0.21F / hScale)), 2);

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

        this.channelWarpDist = Math.max(1.0F, (size * 0.08F * volcanismHScale) / hScale);
        this.gradientStep = Math.max(0.75F, (size * 0.02F * volcanismHScale) / hScale);
    }

    private float rawShape(float x, float z) {
        float sizeValue = this.sizeNoise.compute(x, z, 0);
        float densityValue = this.densityNoise.compute(x, z, 0);
        float densityThreshold = NoiseUtil.clamp(1.0F - this.settings.islandDensity * 0.8F, 0.05F, 0.98F);

        float shapeAlpha = smoothStep(0.5F, 1.0F, sizeValue);
        float densityFade = NoiseUtil.clamp((1.0F - densityThreshold) * 0.5F, 0.04F, 0.12F);
        float densityAlpha = smoothStep(densityThreshold, densityThreshold + densityFade, densityValue);

        float drift = this.peakDrift.compute(x, z, 0) * PEAK_DRIFT_STRENGTH;
        return NoiseUtil.clamp(shapeAlpha * densityAlpha + drift * shapeAlpha, 0.0F, 1.0F);
    }

    @Override
    public void apply(Cell cell, float x, float z) {
        float originalContinentEdge = cell.continentEdge;

        float shape = this.rawShape(x, z);

        float fadeStart = this.controlPoints.islandCoast;
        float fadeEnd = this.controlPoints.deepOcean;
        float continentFade = 1.0F - smoothStep(fadeStart, fadeEnd, originalContinentEdge);

        float islandAlpha = shape * continentFade;
        if (islandAlpha <= 0.001F) {
            return;
        }

        float beachWidth = NoiseUtil.clamp(Math.max(0.05F, this.settings.beachWidth), 0.05F, 0.45F);
        float beachCoverage = NoiseUtil.clamp(this.settings.beachCoverage, 0.0F, 1.0F);
        float shelfEnd = NoiseUtil.clamp(beachWidth * 0.65F, 0.04F, 0.35F);

        float rawVariance = this.beachVariance.compute(x, z, 0);
        float cliffFactor = smoothStep(-0.2F, 0.6F, rawVariance);
        float activeBeachWidth = NoiseUtil.lerp(beachWidth, 0.01F, cliffFactor);
        float coastEnd = NoiseUtil.clamp(shelfEnd + (activeBeachWidth * 0.5F), shelfEnd + 0.005F, 0.85F);
        float baseBeachEnd = coastEnd + (activeBeachWidth * beachCoverage * 1.5F);
        float bVariance = rawVariance * 0.15F * (1.0F - cliffFactor) - 0.05F * (1.0F - cliffFactor);
        float dynamicBeachEnd = NoiseUtil.clamp(baseBeachEnd + bVariance, coastEnd + 0.005F, 0.85F);

        float oceanHeight = cell.height;
        int offshoreDepth = Math.max(2, Math.round(4.0F + this.settings.offshoreDepth * 10.0F));
        float shelfTarget = Math.max(oceanHeight, this.levels.water(-offshoreDepth));
        float shelfAlpha = smoothStep(0.0F, shelfEnd, islandAlpha);
        float shelfHeight = NoiseUtil.lerp(oceanHeight, shelfTarget, shelfAlpha);

        float coastAlpha = smoothStep(shelfEnd, coastEnd, islandAlpha);
        float beachHeight = NoiseUtil.lerp(shelfHeight, this.levels.ground, coastAlpha);

        float landTransitionEnd = NoiseUtil.clamp(NoiseUtil.lerp(1.0F, dynamicBeachEnd + 0.32F, cliffFactor), dynamicBeachEnd + 0.05F, 1.0F);
        float landAlpha = smoothStep(dynamicBeachEnd, landTransitionEnd, islandAlpha);

        // Elevated inland baseline offset to ensure land stays well above sea level past the beach
        float inlandBase = landAlpha * this.settings.islandHeight * (0.035F + this.settings.islandBaseScale * 0.10F);

        // Blend linear dome profile with exponential power curve to keep lower/mid flanks elevated
        float macroDome = shape;
        float linearDome = macroDome;
        float exponentialDome = (float) Math.pow(macroDome, this.domeExponent);
        float domeShape = NoiseUtil.lerp(linearDome * 0.40F, exponentialDome, macroDome);
        float domeContribution = domeShape * this.settings.islandHeight * this.settings.islandVerticalScale * DOME_HEIGHT_SCALE;

        // Summit perturbation
        float summitInfluence = smoothStep(0.6F, 0.95F, macroDome);
        float summitPerturbValue = this.summitPerturb.compute(x, z, 0) * summitInfluence * this.summitPerturbStrength;
        domeContribution += summitPerturbValue * this.settings.islandHeight * this.settings.islandVerticalScale;

        // Radial erosion channels
        float shapeXOffset = this.rawShape(x + this.gradientStep, z);
        float shapeZOffset = this.rawShape(x, z + this.gradientStep);
        float gx = (shapeXOffset - shape) / this.gradientStep;
        float gz = (shapeZOffset - shape) / this.gradientStep;

        float carveDepth = 0.0F;
        float gradMagSq = gx * gx + gz * gz;
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

        // Cap channel carving depth to a maximum fraction of local dome height so valleys never gouge to baseline
        float effectiveCarve = Math.min(carveDepth, domeContribution * MAX_VALLEY_CARVE_FRACTION);
        float reliefHeight = Math.max(0.0F, domeContribution - effectiveCarve);

        float targetHeight = this.levels.ground + inlandBase + reliefHeight;

        cell.height = NoiseUtil.lerp(beachHeight, targetHeight, landAlpha);
        cell.continentEdge = Math.max(originalContinentEdge, continentEdge(islandAlpha, shelfEnd, dynamicBeachEnd));

        if (islandAlpha < shelfEnd) {
            cell.terrain = TerrainType.SHALLOW_OCEAN;
        } else if (islandAlpha < dynamicBeachEnd) {
            cell.terrain = TerrainType.ISLAND_BEACH;
        } else if (macroDome > 0.5F && landAlpha > 0.5F && this.settings.mountainChance > 0.05F) {
            cell.terrain = TerrainType.ISLAND_MOUNTAINS;
        } else {
            cell.terrain = TerrainType.ISLAND;
        }

        if (islandAlpha >= shelfEnd) {
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