package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
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

    public UpliftRiverCarver(River river, RiverWarp warp, RiverConfig config, RiverCarverSettings settings, Levels levels) {
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

        // Initialize seamless noise modules for organic variation
        this.widthNoise = Noises.simplex(8241, 150, 2);
        this.depthNoise = Noises.simplex(3912, 100, 2);
        this.terraceNoise = Noises.simplex(5510, 200, 1);
        this.asymmetryNoise = Noises.simplex(1193, 250, 1);
    }

    @Override
    public void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT) {
        float distSqToCurr = this.getDistance2(currX, currZ, currT);
        float currentLinearDist = (float) Math.sqrt(distSqToCurr);

        float flatnessFactor = NoiseUtil.clamp(ContinentalHydrology.getFlatnessFactor(cell.waterTable), 0.0F, 1.0F);
        float scaleFactor = 1.0F + 0.75F * flatnessFactor;
        float sqScaleFactor = scaleFactor * scaleFactor;

        // --- ENVIRONMENTAL VARIATION SAMPLES ---
        // Range mostly from -1.0 to 1.0
        float widthVar = this.widthNoise.compute(currX, currZ, 8241);
        float depthVar = this.depthNoise.compute(currX, currZ, 3912);
        float terraceMask = this.terraceNoise.compute(currX, currZ, 5510);
        float asymmetry = this.asymmetryNoise.compute(currX, currZ, 1193);

        // Apply variations
        float dynamicWidthMult = 1.0F + (widthVar * 0.35F); // +/- 35% width variation
        float dynamicDepthMult = 1.0F + (depthVar * 0.25F); // +/- 25% depth variation
        float sideBias = 1.0F + (asymmetry * 0.4F); // Shifts width slightly based on which side of the river we are on

        // --- 1. TARGET ELEVATIONS ---
        float oceanHeightOffset = levels.water;
        float targetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanHeightOffset;

        float baseBedDepthOffset = oceanHeightOffset - config.bedHeight;
        float bedDepthOffset = baseBedDepthOffset * dynamicDepthMult;
        float targetBedFloor = targetWaterLevel - bedDepthOffset;

        float bankHeightOffset = (config.maxBankHeight - config.minBankHeight);
        float targetValleyFloor = targetWaterLevel + bankHeightOffset;
        float discrepencyScale = 1.0F + (levels.scale(cell.height - targetWaterLevel)) / 100.0F;

        // --- 2. RADII BOUNDARIES (HORIZONTAL MEASUREMENTS) ---
        float biasedScale = sqScaleFactor * dynamicWidthMult * sideBias;

        float zone1Radius = (float) Math.sqrt(this.getScaledSize(currT, this.bedWidth) * biasedScale);

        float zone2Width = (config.maxBankHeight - config.minBankHeight) / this.levels.unit * biasedScale;
        float zone2Radius = zone1Radius + zone2Width;

        float zone3Width = config.bankWidth * dynamicWidthMult;
        float zone3Radius = zone2Radius + zone3Width;

        float zone4Radius = zone3Radius + (zone3Width * (3 + discrepencyScale));

        if (currentLinearDist >= zone4Radius) return;

        // --- 3. PROFILE SELECTION ---
        float finalHeight = cell.height;

        if (currentLinearDist < zone1Radius) {
            finalHeight = carveZone1Riverbed(cell, currT, distSqToCurr, targetBedFloor, bedDepthOffset, oceanHeightOffset, sqScaleFactor, targetWaterLevel);
            cell.riverZone = RiverCarverSettings.RiverZone.Riverbed;
        } else if (currentLinearDist < zone2Radius) {
            finalHeight = carveZone2BankStep(currentLinearDist, zone1Radius, zone2Radius, targetWaterLevel, targetValleyFloor, terraceMask);
            if (cell.riverZone != RiverCarverSettings.RiverZone.Riverbed) {
                cell.riverZone = RiverCarverSettings.RiverZone.Banks;
            }
        } else if (currentLinearDist < zone3Radius) {
            finalHeight = carveZone3ValleyFloor(targetValleyFloor, terraceMask, currentLinearDist, zone2Radius, zone3Radius);
            if (cell.riverZone != RiverCarverSettings.RiverZone.Riverbed && cell.riverZone != RiverCarverSettings.RiverZone.Banks) {
                cell.riverZone = RiverCarverSettings.RiverZone.ValleyFloor;
            }
        } else {
            finalHeight = carveZone4Fadeout(cell.height, currentLinearDist, zone3Radius, zone4Radius, targetValleyFloor, currX, currZ, currT, terraceMask);
            if (cell.riverZone != RiverCarverSettings.RiverZone.Riverbed && cell.riverZone != RiverCarverSettings.RiverZone.Banks && cell.riverZone != RiverCarverSettings.RiverZone.ValleyFloor) {
                cell.riverZone = RiverCarverSettings.RiverZone.ValleyFadeout;
            }
        }

        if (finalHeight < cell.height) {
            cell.height = finalHeight;
        }

        updateValleyMask(prevX, prevZ, prevT, currX, currZ, currT, distSqToCurr, sqScaleFactor, targetBedFloor, cell);
    }

    private float carveZone1Riverbed(Cell cell, float currT, float distSqToCurr, float targetBedFloor, float bedDepthOffset, float oceanHeightOffset, float sqScaleFactor, float targetWaterLevel) {
        float bedInfluence = this.getDistanceAlpha(currT, distSqToCurr, this.bedWidth, sqScaleFactor);

        // Deepen the center of the channel slightly more than the edges
        bedInfluence = bedInfluence * bedInfluence * (3.0F - 2.0F * bedInfluence);

        float bedHeight = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) - (bedDepthOffset * bedInfluence) + oceanHeightOffset;

        cell.moisture = 1.0F;
        this.tag(cell, targetWaterLevel);
        return bedHeight;
    }

    private float carveZone2BankStep(float distance, float zone1Radius, float zone2Radius, float targetWaterLevel, float targetValleyFloor, float terraceMask) {
        float progress = (distance - zone1Radius) / (zone2Radius - zone1Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        // Apply terracing if the noise mask is high
        progress = applyTerracing(progress, terraceMask, 3.0F);

        float smoothProgress = progress * progress * (3.0F - 2.0F * progress);
        return NoiseUtil.lerp(targetWaterLevel, targetValleyFloor, smoothProgress);
    }

    private float carveZone3ValleyFloor(float targetValleyFloor, float terraceMask, float distance, float zone2Radius, float zone3Radius) {
        // Add very slight bumps and dips to the valley floor
        float bumpiness = (terraceMask * 0.5F) * this.levels.unit;
        return targetValleyFloor + bumpiness;
    }

    private float carveZone4Fadeout(float originalTerrainHeight, float distance, float zone3Radius, float zone4Radius, float targetValleyFloor, float currX, float currZ, float currT, float terraceMask) {
        float progress = (distance - zone3Radius) / (zone4Radius - zone3Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        float riverLength = (float) Math.sqrt(this.river.dx * this.river.dx + this.river.dz * this.river.dz);
        float distanceAlongRiver = currT * riverLength;
        float warpOffset = (float) (Math.cos(currX * 0.12F + currZ * 0.05F) * Math.sin(currZ * 0.12F - currX * 0.05F) * 3.5F);

        float macroErosionNoise = (float) (Math.sin(distanceAlongRiver * 0.03F + currX * 0.01F) * Math.cos(currZ * 0.01F));
        macroErosionNoise = NoiseUtil.clamp((macroErosionNoise + 1.0F) * 0.5F, 0.0F, 1.0F);

        float maxAllowedDepth = 0.15F;
        float dynamicGullyDepth = maxAllowedDepth * macroErosionNoise;

        float primaryFreq = 0.35F;
        float primaryWave = (float) Math.sin((distanceAlongRiver * primaryFreq) + warpOffset);
        primaryWave = (primaryWave + 1.0F) * 0.5F;
        primaryWave = primaryWave * primaryWave;

        float detailFreq = 1.15F;
        float detailWave = (float) Math.sin((distanceAlongRiver * detailFreq) - (warpOffset * 0.7F));
        detailWave = (detailWave + 1.0F) * 0.5F;

        float combinedGullyNoise = (primaryWave * 0.7F) + (detailWave * 0.3F * primaryWave);
        float slopeMask = progress * (1.0F - progress) * 4.0F;

        float detailNoise = (float) (Math.sin(currX * 0.9F + currZ * 0.4F) * Math.cos(currZ * 0.9F - currX * 0.4F));
        detailNoise = (detailNoise + 1.0F) * 0.5F;

        float baseAccumulation = (1.0F - progress);
        float rubbleMask = (combinedGullyNoise * 0.5F) + (baseAccumulation * 0.5F) * macroErosionNoise;
        float rubbleAmplitude = 0.035F;
        float roughness = detailNoise * rubbleMask * rubbleAmplitude;

        float modifiedProgress = progress - (combinedGullyNoise * slopeMask * dynamicGullyDepth) + roughness;
        modifiedProgress = NoiseUtil.clamp(modifiedProgress, 0.0F, 1.0F);

        // Apply terracing to the fadeout slope as well
        modifiedProgress = applyTerracing(modifiedProgress, terraceMask, 5.0F);

        float smoothProgress = modifiedProgress * modifiedProgress * (3.0F - 2.0F * modifiedProgress);
        return NoiseUtil.lerp(targetValleyFloor, originalTerrainHeight, smoothProgress);
    }

    /**
     * Helper to create stepped strata in the terrain.
     */
    private float applyTerracing(float progress, float terraceMask, float steps) {
        float terraceStrength = NoiseUtil.clamp(terraceMask * 1.5F, 0.0F, 1.0F);

        if (terraceStrength > 0.0F) {
            float steppedProgress = (float) Math.round(progress * steps) / steps;
            // Soften the step edge slightly so it's not a perfectly vertical block wall
            return NoiseUtil.lerp(progress, steppedProgress, terraceStrength * 0.85F);
        }
        return progress;
    }

    private void updateValleyMask(float prevX, float prevZ, float prevT, float currX, float currZ, float currT, float distSqToCurr, float sqScaleFactor, float targetBedFloor, Cell cell) {
        float distSqToPrev = this.getDistance2(prevX, prevZ, prevT);
        float valleyInfluence = this.getDistanceAlpha(currT, Math.min(distSqToCurr, distSqToPrev), this.valleyWidth, sqScaleFactor);
        if (valleyInfluence > 0.0F) {
            valleyInfluence = this.valleyCurve.apply(valleyInfluence);
            cell.riverMask = Math.min(cell.riverMask, 1.0F - valleyInfluence);
        }
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