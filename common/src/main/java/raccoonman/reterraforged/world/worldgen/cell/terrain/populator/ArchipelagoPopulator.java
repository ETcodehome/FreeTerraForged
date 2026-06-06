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

public class ArchipelagoPopulator implements CellPopulator {
    private IslandSettings settings;
    private Levels levels;
    private ControlPoints controlPoints;

    private Noise sizeNoise;
    private Noise densityNoise;
    private Noise ridgeHeight;
    private Noise hillHeight;
    private Noise volcanoHeight;
    private Noise mountainSelector;
    private Noise volcanoSelector;

    // New Organic Detail Noises
    private Noise beachVariance;
    private Noise baseTerrainDetail;
    private Noise mountainGullies;

    private Noise islandErosion;
    private Noise islandWeirdness;
    private Noise beachErosion;
    private Noise beachWeirdness;

    public ArchipelagoPopulator(IslandSettings settings, Levels levels, ControlPoints controlPoints) {
        this.settings = settings;
        this.levels = levels;
        this.controlPoints = controlPoints;

        int size = Math.round(settings.islandSize);
        float hScale = Math.max(0.1F, settings.islandHorizontalScale);

        // Coastline & Falloff Distortion
        // A high-frequency warp at the end to "ruffle" the edges, preventing smooth, perfect arcs.
        Noise sizeN = Noises.simplex(1273, Math.max(1, Math.round(size * 3.5F / hScale)), 3);
        sizeN = Noises.warpPerlin(sizeN, 1273, Math.max(1, Math.round(size * 2.0F / hScale)), 2, size * 0.5F / hScale);
        sizeN = Noises.warpPerlin(sizeN, 4830, Math.max(1, Math.round(size * 0.5F / hScale)), 1, size * 0.3F / hScale);
        sizeN = Noises.warpPerlin(sizeN, 8932, Math.max(1, Math.round(size * 0.08F / hScale)), 2, size * 0.15F / hScale);
        sizeN = Noises.clamp(sizeN, 0.0F, 1.0F);
        this.sizeNoise = sizeN;

        Noise densityN = Noises.simplex(9735, 4000, 3);
        densityN = Noises.warpPerlin(densityN, 9735, 2000, 2, 1000.0F);
        densityN = Noises.clamp(densityN, 0.0F, 1.0F);
        this.densityNoise = densityN;

        Noise ridge = Noises.perlinRidge(4829, Math.max(1, Math.round(size * 1.6F / hScale)), 4, 2.1F, 0.82F);
        ridge = Noises.warpPerlin(ridge, 4830, Math.max(1, Math.round(size * 0.9F / hScale)), 2, size * 0.35F / hScale);
        ridge = Noises.clamp(ridge, 0.0F, 1.0F);
        this.ridgeHeight = ridge;

        Noise hills = Noises.billow(3811, Math.max(1, Math.round(size * 0.35F / hScale)), 3, 2.25F, 0.55F);
        hills = Noises.warpPerlin(hills, 3812, Math.max(1, Math.round(size * 0.7F / hScale)), 1, size * 0.2F / hScale);
        hills = Noises.clamp(hills, 0.0F, 1.0F);
        this.hillHeight = hills;

        Noise volcano = Noises.perlinRidge(6721, Math.max(1, Math.round(size * 0.45F / hScale)), 3, 2.4F, 0.9F);
        volcano = Noises.powCurve(volcano, 1.8F);
        volcano = Noises.clamp(volcano, 0.0F, 1.0F);
        this.volcanoHeight = volcano;

        // Ebbing & Flowing Beaches
        this.beachVariance = Noises.simplex(5541, Math.max(1, Math.round(size * 0.15F / hScale)), 2);

        // Rolling Base Terrain (breaks up flatness)
        this.baseTerrainDetail = Noises.simplex(7712, Math.max(1, Math.round(size * 0.1F / hScale)), 3);

        // Mountain Gullies / Erosion (adds rivulets)
        this.mountainGullies = Noises.perlinRidge(9912, Math.max(1, Math.round(size * 0.06F / hScale)), 3, 2.2F, 0.8F);

        this.mountainSelector = Noises.clamp(Noises.simplex(11867, Math.max(1, Math.round(size * 1.25F / hScale)), 2), 0.0F, 1.0F);
        this.volcanoSelector = Noises.clamp(Noises.simplex(22193, Math.max(1, Math.round(size * 1.75F / hScale)), 2), 0.0F, 1.0F);

        this.islandErosion = Erosion.LEVEL_4.source();
        this.islandWeirdness = Weirdness.MID_SLICE_NORMAL_DESCENDING.source();
        this.beachErosion = Erosion.LEVEL_4.source();
        this.beachWeirdness = Weirdness.MID_SLICE_NORMAL_DESCENDING.source();
    }

    @Override
    public void apply(Cell cell, float x, float z) {
        float sizeValue = this.sizeNoise.compute(x, z, 0);
        float densityValue = this.densityNoise.compute(x, z, 0);
        float densityThreshold = NoiseUtil.clamp(1.0F - this.settings.islandDensity * 0.8F, 0.05F, 0.98F);

        float shapeAlpha = smoothStep(0.5F, 1.0F, sizeValue);
        float densityFade = NoiseUtil.clamp((1.0F - densityThreshold) * 0.5F, 0.04F, 0.12F);
        float densityAlpha = smoothStep(densityThreshold, densityThreshold + densityFade, densityValue);
        float islandAlpha = shapeAlpha * densityAlpha;
        if (islandAlpha <= 0.001F) {
            return;
        }

        float beachWidth = NoiseUtil.clamp(Math.max(0.05F, this.settings.beachWidth), 0.05F, 0.45F);
        float beachCoverage = NoiseUtil.clamp(this.settings.beachCoverage, 0.0F, 1.0F);
        float shelfEnd = NoiseUtil.clamp(beachWidth * 0.65F, 0.04F, 0.35F);

        // Dynamic Beach Width
        float bVariance = this.beachVariance.compute(x, z, 0) * 0.15F - 0.05F;
        float baseBeachEnd = shelfEnd + beachWidth * (0.5F + beachCoverage * 1.5F);
        float dynamicBeachEnd = NoiseUtil.clamp(baseBeachEnd + bVariance, shelfEnd + 0.05F, 0.85F);

        float oceanHeight = cell.height;
        int offshoreDepth = Math.max(2, Math.round(4.0F + this.settings.offshoreDepth * 10.0F));
        float shelfTarget = Math.max(oceanHeight, this.levels.water(-offshoreDepth));
        float shelfAlpha = smoothStep(0.0F, shelfEnd, islandAlpha);
        float shelfHeight = NoiseUtil.lerp(oceanHeight, shelfTarget, shelfAlpha);

        float beachAlpha = smoothStep(shelfEnd, dynamicBeachEnd, islandAlpha);
        float beachHeight = NoiseUtil.lerp(shelfHeight, this.levels.ground, beachAlpha);

        float mountainStart = NoiseUtil.clamp(dynamicBeachEnd + 0.08F, 0.22F, 0.9F);
        float mountainEnd = Math.max(mountainStart + 0.08F, 0.72F);
        float mountainAlpha = smoothStep(mountainStart, mountainEnd, islandAlpha);

        float mountainGate = chanceMask(this.mountainSelector, this.settings.mountainChance, x, z);
        float volcanoGate = chanceMask(this.volcanoSelector, this.settings.volcanoChance, x, z);

        float hillValue = this.hillHeight.compute(x, z, 0) * (0.15F + mountainGate * 0.55F);
        float ridgeValue = this.ridgeHeight.compute(x, z, 0) * mountainGate;
        float volcanoValue = this.volcanoHeight.compute(x, z, 0) * volcanoGate;

        // Base Mountain Profile
        float mountainValue = NoiseUtil.clamp(hillValue * 0.35F + ridgeValue * 0.5F + volcanoValue * 0.75F, 0.0F, 1.0F);

        // Apply Gullies and Rivulets
        float gullyErosion = this.mountainGullies.compute(x, z, 0) * mountainAlpha * 0.45F;
        mountainValue = Math.max(0.0F, mountainValue - gullyErosion);

        // Apply Terracing to Mountains
        float terraceSteps = 7.0F; // Number of steps
        float terracedMountain = (float) Math.floor(mountainValue * terraceSteps) / terraceSteps;
        // Blend between raw and terraced to keep it organic, not perfectly flat stairs
        mountainValue = NoiseUtil.lerp(mountainValue, terracedMountain, 0.55F);

        float baseHeight = this.settings.islandHeight * (0.015F + this.settings.islandBaseScale * 0.08F);
        float reliefHeight = mountainValue * mountainAlpha * this.settings.islandHeight * this.settings.islandVerticalScale * 0.3F;

        // Base Terrain Detailing (Rolling Hills)
        float landAlpha = smoothStep(dynamicBeachEnd, 1.0F, islandAlpha);
        float landDetail = this.baseTerrainDetail.compute(x, z, 0) * this.settings.islandHeight * 0.08F * landAlpha;

        float targetHeight = this.levels.ground + baseHeight + reliefHeight + landDetail;

        cell.height = NoiseUtil.lerp(beachHeight, targetHeight, landAlpha);
        cell.continentEdge = Math.max(cell.continentEdge, continentEdge(islandAlpha, shelfEnd, dynamicBeachEnd));

        if (islandAlpha < shelfEnd) {
            cell.terrain = TerrainType.SHALLOW_OCEAN;
        } else if (islandAlpha < dynamicBeachEnd) {
            cell.terrain = TerrainType.ISLAND_BEACH;
        } else if (mountainAlpha > 0.35F && mountainValue > 0.35F && (mountainGate > 0.1F || volcanoGate > 0.2F)) {
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

    private static float chanceMask(Noise selector, float chance, float x, float z) {
        chance = NoiseUtil.clamp(chance, 0.0F, 1.0F);
        if (chance <= 0.0F) {
            return 0.0F;
        }
        if (chance >= 1.0F) {
            return 1.0F;
        }
        float threshold = 1.0F - chance;
        return smoothStep(threshold, Math.min(1.0F, threshold + 0.2F), selector.compute(x, z, 0));
    }

    private static float smoothStep(float min, float max, float value) {
        if (max <= min) {
            return value >= max ? 1.0F : 0.0F;
        }
        float alpha = NoiseUtil.clamp((value - min) / (max - min), 0.0F, 1.0F);
        return alpha * alpha * (3.0F - 2.0F * alpha);
    }
}