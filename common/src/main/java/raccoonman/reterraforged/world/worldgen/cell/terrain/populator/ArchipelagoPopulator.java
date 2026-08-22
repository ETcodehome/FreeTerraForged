package raccoonman.reterraforged.world.worldgen.cell.terrain.populator;

import raccoonman.reterraforged.data.worldgen.preset.settings.IslandSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
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
 * erosion channels placed by warping a smooth noise field along the dome's own gradient direction,
 * the way runoff actually carves a shield/stratovolcano's flanks. There is no per-point
 * high-frequency noise anywhere in the relief pass, and carving is purely subtractive against the
 * smooth dome baseline and tapers to zero at both the summit and the coast — so a locally isolated
 * pit is structurally impossible, not just unlikely.
 */
public class ArchipelagoPopulator implements CellPopulator {
    // Dome shaping. Exponent controls how abruptly the summit rises out of broad lower flanks;
    // driven by settings.mountainScale so higher values give a steeper, more conical peak.
    private static final float DOME_EXPONENT_MIN = 1.3F;
    private static final float DOME_EXPONENT_MAX = 3.6F;
    private static final float DOME_HEIGHT_SCALE = 0.95F;

    // Radial channel carving (fluvial dissection of the flanks).
    private static final float VALLEY_SHARPNESS = 1.7F;
    private static final float CHANNEL_DEPTH_MIN = 0.08F;
    private static final float CHANNEL_DEPTH_MAX = 0.32F;
    private static final float PEAK_DRIFT_STRENGTH = 0.35F;

    // Breaks up flat mountaintops: a fairly large-scale (smaller than the whole-island drift lobe,
    // larger than the erosion channels) perturbation applied only near the summit, where the dome
    // curve saturates and would otherwise plateau.
    private static final float SUMMIT_PERTURB_STRENGTH = 0.42F;

    private IslandSettings settings;
    private Levels levels;
    private ControlPoints controlPoints;

    private Noise sizeNoise;
    private Noise densityNoise;

    // Very low frequency fields only - large enough relative to the island that they read as
    // "one broad lobe" of variation, never as small-scale wavy noise.
    private Noise peakDrift;
    private Noise channelPattern;
    private Noise summitPerturb;

    private Noise beachVariance;

    private Noise islandErosion;
    private Noise islandWeirdness;
    private Noise beachErosion;
    private Noise beachWeirdness;

    private float domeExponent;
    private float channelDepthScale;
    private float channelWarpDist;
    private float gradientStep;

    public ArchipelagoPopulator(IslandSettings settings, Levels levels, ControlPoints controlPoints, Seed seed) {
        this.settings = settings;
        this.levels = levels;
        this.controlPoints = controlPoints;
        int salt = seed.get();

        int size = Math.round(settings.islandSize);
        float hScale = Math.max(0.1F, settings.islandHorizontalScale);

        // Coastline & Falloff Distortion (island placement/shape mask - unchanged, this is the
        // only noise that's allowed to shape the macro landform, and it's already very low frequency)
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

        // Single broad lobe of variation across the whole island - shifts/elongates where the
        // summit sits without ever introducing small-scale bumps.
        this.peakDrift = Noises.simplex(3391 + salt, Math.max(1, Math.round(size * 1.2F / hScale)), 1);

        // Channel placement: a smooth 2-octave noise field, sampled via a direction-normalized
        // domain warp along the dome's gradient (see apply()). Using noise here instead of a
        // periodic function of angle avoids two artifacts a pure radial-spoke approach produces:
        // regular banding (a fixed-frequency wave is inherently evenly spaced) and pinching at the
        // summit (angle-around-a-point math goes unstable as the gradient shrinks toward zero).
        this.channelPattern = Noises.simplex(7213 + salt, Math.max(1, Math.round(size * 0.32F / hScale)), 2);

        // Large-scale summit perturbation - bigger wavelength than the erosion channels care about,
        // smaller than the whole-island peak drift, so it reads as broad rolling relief on the
        // mountaintop rather than either a flat cap or small-scale noise.
        this.summitPerturb = Noises.simplex(5107 + salt, Math.max(1, Math.round(size * 0.4F / hScale)), 2);

        // Ebbing & Flowing Beaches - wavelength bumped ~40% over the original so low-lying coastal
        // features are a bit smoother, without going wide enough to introduce visible large-scale
        // patterning.
        this.beachVariance = Noises.simplex(5541 + salt, Math.max(1, Math.round(size * 0.21F / hScale)), 2);

        this.islandErosion = Erosion.LEVEL_4.source();
        this.islandWeirdness = Weirdness.MID_SLICE_NORMAL_DESCENDING.source();
        this.beachErosion = Erosion.LEVEL_4.source();
        this.beachWeirdness = Weirdness.MID_SLICE_NORMAL_DESCENDING.source();

        // mountainScale -> summit steepness. volcanismScale -> how heavily eroded/dissected the
        // flanks are (reinterpreted: a volcanic island's post-eruption erosion intensity, rather
        // than a per-point chance gate).
        this.domeExponent = NoiseUtil.lerp(DOME_EXPONENT_MIN, DOME_EXPONENT_MAX, NoiseUtil.clamp(this.settings.mountainScale, 0.0F, 1.0F));
        this.channelDepthScale = NoiseUtil.lerp(CHANNEL_DEPTH_MIN, CHANNEL_DEPTH_MAX, NoiseUtil.clamp(this.settings.volcanismScale, 0.0F, 1.0F));
        this.channelWarpDist = Math.max(1.0F, size * 0.08F / hScale);
        this.gradientStep = Math.max(0.75F, size * 0.02F / hScale);
    }

    /**
     * Raw island shape mask before continent-edge fade: island footprint noise plus a broad drift
     * lobe. This is the only thing the summit dome and its gradient are derived from - no
     * high-frequency noise involved, so both the dome and the channel directions it produces stay
     * large-scale and coherent.
     */
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
        // Capture the continent edge value before it gets overridden
        float originalContinentEdge = cell.continentEdge;

        float shape = this.rawShape(x, z);

        // Calculate a fade factor based on proximity to the main continent
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

        // Dynamic Beach Ebb & Cliff Flow
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

        // Land Alpha: how far the interior dome/erosion relief has faded in past the beach.
        float landTransitionEnd = NoiseUtil.clamp(NoiseUtil.lerp(1.0F, dynamicBeachEnd + 0.32F, cliffFactor), dynamicBeachEnd + 0.05F, 1.0F);
        float landAlpha = smoothStep(dynamicBeachEnd, landTransitionEnd, islandAlpha);

        // --- Summit dome, built purely from the low-frequency shape field ---
        float macroDome = shape;
        float domeContribution = (float) Math.pow(macroDome, this.domeExponent) * this.settings.islandHeight * this.settings.islandVerticalScale * DOME_HEIGHT_SCALE;

        // Perturb the summit so fully-inland terrain doesn't flatten into a plateau once the dome
        // curve saturates near macroDome ~ 1. Influence ramps up the closer we get to fully inland,
        // so lower/mid flanks are untouched and only the summit region gets broken up.
        float summitInfluence = smoothStep(0.6F, 0.95F, macroDome);
        float summitPerturbValue = this.summitPerturb.compute(x, z, 0) * summitInfluence * SUMMIT_PERTURB_STRENGTH;
        domeContribution += summitPerturbValue * this.settings.islandHeight * this.settings.islandVerticalScale;

        // --- Radial erosion channels, derived from the dome's own gradient ---
        float shapeXOffset = this.rawShape(x + this.gradientStep, z);
        float shapeZOffset = this.rawShape(x, z + this.gradientStep);
        float gx = (shapeXOffset - shape) / this.gradientStep;
        float gz = (shapeZOffset - shape) / this.gradientStep;

        float carveDepth = 0.0F;
        float gradMagSq = gx * gx + gz * gz;
        if (gradMagSq > 1.0e-8F) {
            // Normalize the gradient to a pure direction before warping. Using the raw gradient's
            // magnitude here is what caused the old pinching: it shrinks toward zero near the flat
            // summit and blows up unpredictably near steep drops, so warp distance would swing
            // wildly. A unit direction keeps the warp bounded and stable everywhere.
            float invMag = 1.0F / (float) Math.sqrt(gradMagSq);
            float dirX = gx * invMag;
            float dirZ = gz * invMag;

            float warpedX = x + dirX * this.channelWarpDist;
            float warpedZ = z + dirZ * this.channelWarpDist;
            float channelValue = this.channelPattern.compute(warpedX, warpedZ, 0);

            // Valleys sit along contour lines of the noise field near zero; ridges are the broader
            // area away from those contours. Being noise-driven rather than a fixed-frequency wave,
            // spacing and width vary organically instead of banding evenly.
            float channelMask = NoiseUtil.clamp(1.0F - Math.abs(channelValue) * 1.6F, 0.0F, 1.0F);
            channelMask = (float) Math.pow(channelMask, VALLEY_SHARPNESS);

            // Dissection is confined to the mid/upper flanks — the lower flanks near the coast stay
            // smooth (real runoff dissection is most visible partway up a volcanic cone, not right
            // at the shoreline), and the summit stays clean.
            float carveEnvelope = smoothStep(0.28F, 0.45F, macroDome) * (1.0F - smoothStep(0.58F, 0.85F, macroDome));

            carveDepth = channelMask * carveEnvelope * this.channelDepthScale * this.settings.islandHeight * this.settings.islandVerticalScale;
        }

        float baseHeight = this.settings.islandHeight * (0.015F + this.settings.islandBaseScale * 0.08F);
        float reliefHeight = Math.max(0.0F, domeContribution - carveDepth);
        float targetHeight = this.levels.ground + baseHeight + reliefHeight;

        cell.height = NoiseUtil.lerp(beachHeight, targetHeight, landAlpha);
        cell.continentEdge = Math.max(originalContinentEdge, continentEdge(islandAlpha, shelfEnd, dynamicBeachEnd));

        if (islandAlpha < shelfEnd) {
            cell.terrain = TerrainType.SHALLOW_OCEAN;
        } else if (islandAlpha < dynamicBeachEnd) {
            cell.terrain = TerrainType.ISLAND_BEACH;
        } else if (macroDome > 0.5F && landAlpha > 0.5F) {
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