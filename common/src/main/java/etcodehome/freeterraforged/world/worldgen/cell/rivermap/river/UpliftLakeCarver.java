package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import java.util.Random;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

/**
 * High-quality, organic lake basin carver for FreeTerraForged uplift continents.
 * <p>
 * Ensures every lake surface is 100% flat at a single uniform elevation matching its
 * continental terrace plateau, completely eliminating stepped lake water artifacts.
 * Shapes shorelines organically using multi-lobed harmonics and high-frequency domain warping.
 */
public class UpliftLakeCarver implements FTFRiverCarver {

    private final River river;
    private final RiverConfig config;
    private final Levels levels;
    private final LakeConfig lakeConfig;
    private final float nodeX;
    private final float nodeZ;
    private final float plateauGradient;
    private final float baseRadius;
    private final float bankFadeWidth;
    private final float maxCarveRadius;
    private final float maxCarveRadiusSq;
    private final float baseBedDepth;
    private final float bankHeightOffset;
    private final float targetWaterLevel;
    private final float targetBankHeight;
    private final float anglePhase1;
    private final float anglePhase2;
    private final float anglePhase3;
    private final Noise lakeWarpNoise;
    private final Noise lakeBedNoise;

    public UpliftLakeCarver(
            TriSubdivisionRiverGenerator.DrainageNode lakeNode,
            RiverConfig config,
            Levels levels,
            LakeConfig lakeConfig,
            float continentScale,
            long worldSeed
    ) {
        this.nodeX = lakeNode.x;
        this.nodeZ = lakeNode.z;
        this.plateauGradient = lakeNode.gradient;
        this.config = config;
        this.levels = levels;
        this.lakeConfig = lakeConfig;

        long nodeSeed = ((long) Float.floatToIntBits(lakeNode.x) * 314159265L)
                      ^ ((long) Float.floatToIntBits(lakeNode.z) * 271828182L)
                      ^ worldSeed;
        Random rand = new Random(nodeSeed);

        // 1. Strictly bound lake radius to the flat plateau band of its ContinentalHydrology terrace step.
        // This mathematically prevents lake basins from spilling across transition ramps into adjacent
        // terraces, guaranteeing that 100% of the carved basin and fadeout stay at a single uniform elevation.
        float scale = (continentScale > 0.0F) ? continentScale : 3000.0F;
        this.bankFadeWidth = Math.min(22.0F, Math.max(14.0F, config.bankWidth * 1.1F));

        float maxAllowedBaseRadius;
        if (this.plateauGradient <= 0.01F) {
            // Lowlands: completely flat, open plains outside the continental uplift terraces.
            // Not constrained by terrace transition ramps, so allow standard natural lake radius.
            maxAllowedBaseRadius = 45.0F;
        } else {
            int stepId = ContinentalHydrology.getStepId(this.plateauGradient);
            double pStart = ContinentalHydrology.getPlateauStart(stepId);
            double pEnd = ContinentalHydrology.getPlateauEnd(stepId);
            double gradientMargin = Math.min(Math.abs(this.plateauGradient - pStart), Math.abs(pEnd - this.plateauGradient));
            float blockMargin = (float) (gradientMargin * scale);

            // Effective max shoreline multiplier is 1.45; ensure effectiveFadeRadius strictly < blockMargin
            maxAllowedBaseRadius = Math.max(16.0F, (blockMargin * 0.88F - this.bankFadeWidth) / 1.45F);
        }

        float minR = Math.min(30.0F, maxAllowedBaseRadius * 0.55F);
        float maxR = Math.min(65.0F, maxAllowedBaseRadius);

        float calculatedRadius;
        if (lakeNode.feederCount >= 2) {
            // Confluence lake: broad, prominent valley lake where multiple tributaries meet
            calculatedRadius = NoiseUtil.lerp(minR, maxR, 0.70F) * (0.90F + rand.nextFloat() * 0.20F);
        } else if (lakeNode.feederCount >= 1 && lakeNode.isPopulated) {
            // Inline lake: scenic body along flowing river course
            calculatedRadius = NoiseUtil.lerp(minR, maxR, 0.45F) * (0.90F + rand.nextFloat() * 0.20F);
        } else if (lakeNode.feederCount >= 1) {
            // Terminal lake: valley basin terminus (e.g. blocked by mountains or endorheic)
            calculatedRadius = NoiseUtil.lerp(minR, maxR, 0.55F) * (0.90F + rand.nextFloat() * 0.20F);
        } else if (lakeNode.isPopulated) {
            // Spring / headwater lake: mountain source lake feeding a downstream river
            calculatedRadius = NoiseUtil.lerp(minR, maxR, 0.45F) * (0.90F + rand.nextFloat() * 0.20F);
        } else {
            // Isolated upland/plateau tarn with no feeder or outlet
            calculatedRadius = NoiseUtil.lerp(minR, maxR, 0.35F) * (0.90F + rand.nextFloat() * 0.20F);
        }
        this.baseRadius = Math.max(16.0F, Math.min(calculatedRadius, maxAllowedBaseRadius));

        this.maxCarveRadius = (this.baseRadius * 1.45F) + this.bankFadeWidth + 5.0F;
        this.maxCarveRadiusSq = this.maxCarveRadius * this.maxCarveRadius;

        // Depth profile: deep enough for natural water depth (3 to 6 blocks)
        this.baseBedDepth = Math.max(4.0F, (float) config.bedHeight) * levels.unit;
        this.bankHeightOffset = Math.max(1.5F, (float) (config.maxBankHeight - config.minBankHeight)) * levels.unit;

        // Strictly uniform, single-elevation water surface across the entire lake
        this.targetWaterLevel = ContinentalHydrology.getComplexWaterHeight(this.plateauGradient, scale, 1.0F) + levels.water;
        this.targetBankHeight = this.targetWaterLevel + this.bankHeightOffset;

        // Randomized polar harmonic phase offsets for asymmetric multi-lobed lake boundaries
        this.anglePhase1 = rand.nextFloat() * (float) (Math.PI * 2.0);
        this.anglePhase2 = rand.nextFloat() * (float) (Math.PI * 2.0);
        this.anglePhase3 = rand.nextFloat() * (float) (Math.PI * 2.0);

        this.lakeWarpNoise = Noises.simplex2((int) (nodeSeed ^ 0x9e3779b97f4a7c15L), 50, 1);
        this.lakeBedNoise = Noises.simplex2((int) (nodeSeed ^ 0x85ebca6b), 25, 1);

        // Centered river proxy providing spatial bounds to Network query
        this.river = new River(lakeNode.x, lakeNode.z, lakeNode.x, lakeNode.z, this.maxCarveRadius + 50.0F);
    }

    @Override
    public boolean isMain() {
        return false;
    }

    @Override
    public River getRiver() {
        return this.river;
    }

    @Override
    public RiverWarp getWarp() {
        return RiverWarp.NONE;
    }

    @Override
    public RiverConfig getConfig() {
        return this.config;
    }

    @Override
    public RiverConfig createForkConfig(float t, Levels levels) {
        return this.config;
    }

    @Override
    public void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT) {
        float dx = currX - this.nodeX;
        float dz = currZ - this.nodeZ;
        float distSq = dx * dx + dz * dz;

        if (distSq > this.maxCarveRadiusSq) {
            return;
        }

        // In non-lowland locations, ensure lakes never leave the flatness factor alpha mask
        // (effectively making it control their occurrence and influence strength on the terrain)
        float flatnessAlpha = 1.0F;
        if (this.plateauGradient > 0.01F) {
            flatnessAlpha = (cell.flatnessFactor > 0.0F) ? cell.flatnessFactor : ContinentalHydrology.getFlatnessFactor(cell.waterTable);
            flatnessAlpha = NoiseUtil.clamp(flatnessAlpha, 0.0F, 1.0F);
            if (flatnessAlpha <= 0.001F) {
                return; // Never leave the flatness factor alpha mask in non-lowlands
            }
        }

        // 1. Multi-octave organic shoreline deformation (natural coves, bays, points)
        double angle = Math.atan2(dz, dx);
        float lobe1 = (float) Math.sin(2.0 * angle + this.anglePhase1) * 0.16F;
        float lobe2 = (float) Math.cos(3.0 * angle + this.anglePhase2) * 0.11F;
        float lobe3 = (float) Math.sin(5.0 * angle + this.anglePhase3) * 0.06F;

        // 2. High-frequency 2D Simplex noise for rugged natural shoreline edges
        float warpX = this.lakeWarpNoise.compute(currX, currZ, 7439);
        float warpZ = this.lakeWarpNoise.compute(currX + 131.0F, currZ - 87.0F, 8821);
        float noiseMod = (warpX * 0.15F) + (warpZ * 0.10F);

        float shapeModifier = 1.0F + lobe1 + lobe2 + lobe3 + noiseMod;
        shapeModifier = NoiseUtil.clamp(shapeModifier, 0.65F, 1.45F);

        float effectiveShoreRadius = this.baseRadius * shapeModifier;
        float effectiveBedRadius = effectiveShoreRadius * 0.60F;
        float effectiveFadeRadius = effectiveShoreRadius + this.bankFadeWidth;

        float currentDist = (float) Math.sqrt(distSq);
        if (currentDist >= effectiveFadeRadius) {
            return;
        }

        // 3. Strictly uniform, single-elevation water surface across the entire lake
        float targetWaterLevel = this.targetWaterLevel;
        float targetBankHeight = this.targetBankHeight;

        float finalHeight = cell.height;

        if (currentDist < effectiveBedRadius) {
            // Lake bed: deep water with natural bowl profile and gentle undulating floor
            float t = currentDist / Math.max(1.0F, effectiveBedRadius);
            float bowl = t * t;
            float floorNoise = this.lakeBedNoise.compute(currX, currZ, 3912) * (1.2F * this.levels.unit);
            float bedDepth = this.baseBedDepth * (1.0F - 0.25F * bowl) + floorNoise;
            float bedHeight = targetWaterLevel - bedDepth;
            finalHeight = Math.min(cell.height, bedHeight);
        } else if (currentDist < effectiveShoreRadius) {
            // Lake bank & shallows: smooth incline rising from lake bed to dry bank
            float t = (currentDist - effectiveBedRadius) / Math.max(1.0F, effectiveShoreRadius - effectiveBedRadius);
            float tSmooth = t * t * (3.0F - 2.0F * t);
            float bedHeightAtEdge = targetWaterLevel - (this.baseBedDepth * 0.75F);
            float bankHeight = NoiseUtil.lerp(bedHeightAtEdge, targetBankHeight, tSmooth);
            finalHeight = Math.min(cell.height, bankHeight);
        } else {
            // Valley slope fadeout: smooth transition from bank height to surrounding terrain
            float t = (currentDist - effectiveShoreRadius) / Math.max(1.0F, effectiveFadeRadius - effectiveShoreRadius);
            t = NoiseUtil.clamp(t, 0.0F, 1.0F);
            float u = 1.0F - t;
            float uSmooth = u * u * (3.0F - 2.0F * u);
            float heightDiff = Math.max(0.0F, cell.height - targetBankHeight);
            float carveAmount = heightDiff * uSmooth;
            finalHeight = Math.min(cell.height, cell.height - carveAmount);
        }

        float rawCarve = cell.height - finalHeight;
        if (rawCarve > 0.0F) {
            float carveAmount = rawCarve * flatnessAlpha;
            finalHeight = cell.height - carveAmount;
        }

        boolean carved = finalHeight < cell.height;
        if (carved) {
            cell.height = finalHeight;
        }

        // Submerged lake water surface: strictly flat at targetWaterLevel within the alpha mask
        if (finalHeight <= targetWaterLevel && (this.plateauGradient <= 0.01F || flatnessAlpha > 0.05F)) {
            cell.riverWaterLevel = targetWaterLevel;
            cell.terrain = TerrainType.LAKE;
            cell.erosionMask = true;
            cell.hasFlow = false;
            cell.flowAngle = 0;
            cell.riverZone = RiverCarverSettings.RiverZone.Riverbed;
        } else if (carved && currentDist < effectiveShoreRadius) {
            cell.riverZone = RiverCarverSettings.RiverZone.Banks;
        } else if (carved) {
            cell.riverZone = RiverCarverSettings.RiverZone.ValleyFadeout;
        }
    }
}
