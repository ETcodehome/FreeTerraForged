package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunction;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;

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

    public UpliftRiverCarver(River river, RiverWarp warp, RiverConfig config, RiverCarverSettings settings, Levels levels) {
        this.fade = settings.fadeIn;
        this.fadeInv = 1.0F / settings.fadeIn;

        this.bedWidth = new Range(0.25F, (float)(config.bedWidth * config.bedWidth));

        float erosionScale = 3.5F;
        float sqErosionScale = erosionScale * erosionScale;

        // FIX: Reduced the outer erosion scale for the maximum bound from 3.5F to 1.15F.
        // This stops high bankwidth values from exponentially ballooning the fadeout radius and area of influence.
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
    }

    @Override
    public void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT) {
        float distSqToCurr = this.getDistance2(currX, currZ, currT);
        float currentLinearDist = (float) Math.sqrt(distSqToCurr);

        float flatnessFactor = NoiseUtil.clamp(ContinentalHydrology.getFlatnessFactor(cell.waterTable), 0.0F, 1.0F);
        float scaleFactor = 1.0F + 0.75F * flatnessFactor;
        float sqScaleFactor = scaleFactor * scaleFactor;

        // --- 1. TARGET ELEVATIONS ---
        float oceanHeightOffset = levels.water;
        float targetWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanHeightOffset;
        float bedDepthOffset = oceanHeightOffset - config.bedHeight;
        float targetBedFloor = targetWaterLevel - bedDepthOffset;
        float bankHeightOffset = (config.maxBankHeight - config.minBankHeight);
        float targetValleyFloor = targetWaterLevel + bankHeightOffset;
        float discrepencyScale = 1.0F + (levels.scale(cell.height - targetWaterLevel)) / 100.0F; // every 100 blocks double footprint size

        // --- 2. RADII BOUNDARIES (HORIZONTAL MEASUREMENTS) ---

        // ZONE 1: Riverbed Channel limit
        float zone1Radius = (float) Math.sqrt(this.getScaledSize(currT, this.bedWidth) * sqScaleFactor);

        // ZONE 2: River Bank limit
        float zone2Width = (config.maxBankHeight - config.minBankHeight) / this.levels.unit * sqScaleFactor;
        float zone2Radius = zone1Radius + zone2Width;

        // ZONE 3: Flat Valley Floor limit
        float zone3Width = config.bankWidth;
        float zone3Radius = zone2Radius + zone3Width;

        // ZONE 4: Fadeout buffer
        // Driven by the tamed banksWidth range.
        float zone4Radius = zone3Radius + (zone3Width * (3 + discrepencyScale));

        if (currentLinearDist >= zone4Radius) return;

        // --- 3. PROFILE SELECTION ---
        float finalHeight = cell.height;

        if (currentLinearDist < zone1Radius) {
            // ZONE 1: The Riverbed Channel
            finalHeight = carveZone1Riverbed(cell, currT, distSqToCurr, targetBedFloor, bedDepthOffset, oceanHeightOffset, sqScaleFactor, targetWaterLevel);
            cell.riverZone = RiverCarverSettings.RiverZone.Riverbed;
        } else if (currentLinearDist < zone2Radius) {
            // ZONE 2: The Bank Step
            finalHeight = carveZone2BankStep(currentLinearDist, zone1Radius, zone2Radius, targetWaterLevel, targetValleyFloor);
            if (cell.riverZone != RiverCarverSettings.RiverZone.Riverbed) {
                cell.riverZone = RiverCarverSettings.RiverZone.Banks;
            }
        } else if (currentLinearDist < zone3Radius) {
            // ZONE 3: The Flat Valley Shelf
            finalHeight = carveZone3ValleyFloor(targetValleyFloor);
            if (cell.riverZone != RiverCarverSettings.RiverZone.Riverbed && cell.riverZone != RiverCarverSettings.RiverZone.Banks) {
                cell.riverZone = RiverCarverSettings.RiverZone.ValleyFloor;
            }
        } else {
            // ZONE 4: Fadeout Into Surrounding Terrain
            finalHeight = carveZone4Fadeout(cell.height, currentLinearDist, zone3Radius, zone4Radius, targetValleyFloor, currX, currZ, currT);
            if (cell.riverZone != RiverCarverSettings.RiverZone.Riverbed && cell.riverZone != RiverCarverSettings.RiverZone.Banks && cell.riverZone != RiverCarverSettings.RiverZone.ValleyFloor) {
                cell.riverZone = RiverCarverSettings.RiverZone.ValleyFadeout;
            }
        }

        // if the finalHeight calculated is less than the current cell height, carve it down
        if (finalHeight < cell.height) {
            cell.height = finalHeight;
        }

        updateValleyMask(prevX, prevZ, prevT, currX, currZ, currT, distSqToCurr, sqScaleFactor, targetBedFloor, cell);
    }

    /**
     * ZONE 1: Riverbed Channel
     */
    private float carveZone1Riverbed(Cell cell, float currT, float distSqToCurr, float targetBedFloor, float bedDepthOffset, float oceanHeightOffset, float sqScaleFactor, float targetWaterLevel) {
        float bedInfluence = this.getDistanceAlpha(currT, distSqToCurr, this.bedWidth, sqScaleFactor);
        float bedHeight = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) - (bedDepthOffset * bedInfluence) + oceanHeightOffset;

        cell.moisture = 1.0F;
        this.tag(cell, targetWaterLevel);
        return bedHeight;
    }

    /**
     * ZONE 2: Bank Step
     */
    private float carveZone2BankStep(float distance, float zone1Radius, float zone2Radius, float targetWaterLevel, float targetValleyFloor) {
        float progress = (distance - zone1Radius) / (zone2Radius - zone1Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        float smoothProgress = progress * progress * (3.0F - 2.0F * progress);
        return NoiseUtil.lerp(targetWaterLevel, targetValleyFloor, smoothProgress);
    }

    /**
     * ZONE 3: Flat Valley Shelf
     */
    private float carveZone3ValleyFloor(float targetValleyFloor) {
        return targetValleyFloor;
    }

    /**
     * ZONE 4: Fadeout (With Shallower Gullies, Rivulets, and Base Debris)
     */
    private float carveZone4Fadeout(float originalTerrainHeight, float distance, float zone3Radius, float zone4Radius, float targetValleyFloor, float currX, float currZ, float currT) {
        // Base linear progression from 0.0 (valley floor) to 1.0 (surrounding terrain)
        float progress = (distance - zone3Radius) / (zone4Radius - zone3Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        // --- 1. COORDINATE TRACKING & DOMAIN WARP ---
        float riverLength = (float) Math.sqrt(this.river.dx * this.river.dx + this.river.dz * this.river.dz);
        float distanceAlongRiver = currT * riverLength;

        // Complex warp offset using varying frequencies to twist the erosion paths organically
        float warpOffset = (float) (Math.cos(currX * 0.12F + currZ * 0.05F) * Math.sin(currZ * 0.12F - currX * 0.05F) * 3.5F);

        // --- 2. MACRO VARIATION (Gully Intensity & Presence) ---
        // A very slow-moving noise wave that dictates whether this section of the river wall
        // has heavy erosion, shallow rivulets, or is completely smooth/untouched.
        float macroErosionNoise = (float) (Math.sin(distanceAlongRiver * 0.03F + currX * 0.01F) * Math.cos(currZ * 0.01F));
        macroErosionNoise = NoiseUtil.clamp((macroErosionNoise + 1.0F) * 0.5F, 0.0F, 1.0F);

        // Scale our target depth by this macro noise (0% to 100% of maximum allowed depth)
        float maxAllowedDepth = 0.15F;
        float dynamicGullyDepth = maxAllowedDepth * macroErosionNoise;

        // --- 3. MULTI-OCTAVE GULLY BLENDING ---
        // Octave A: Large Primary Ravines (Low Frequency)
        float primaryFreq = 0.35F;
        float primaryWave = (float) Math.sin((distanceAlongRiver * primaryFreq) + warpOffset);
        primaryWave = (primaryWave + 1.0F) * 0.5F;
        primaryWave = primaryWave * primaryWave; // Squaring gives distinct troughs and wide ridges

        // Octave B: Small Nested Rivulets (High Frequency)
        // We reverse the warp offset sign to make these mini-channels split away and cross the main ones
        float detailFreq = 1.15F;
        float detailWave = (float) Math.sin((distanceAlongRiver * detailFreq) - (warpOffset * 0.7F));
        detailWave = (detailWave + 1.0F) * 0.5F;

        // Fractal Blend: Smaller rivulets are multiplied by the primary wave.
        // This forces secondary erosion channels to naturally concentrate *inside* or near the larger valleys.
        float combinedGullyNoise = (primaryWave * 0.7F) + (detailWave * 0.3F * primaryWave);

        // Smooth slope mask keeps modifications zeroed out at the boundaries to prevent chunk tearing
        float slopeMask = progress * (1.0F - progress) * 4.0F;

        // --- 4. DEBRIS & RUBBLE VARIATION ---
        float detailNoise = (float) (Math.sin(currX * 0.9F + currZ * 0.4F) * Math.cos(currZ * 0.9F - currX * 0.4F));
        detailNoise = (detailNoise + 1.0F) * 0.5F;

        float baseAccumulation = (1.0F - progress);
        // Rubble accumulates heavier where macro erosion has swept debris down to the base
        float rubbleMask = (combinedGullyNoise * 0.5F) + (baseAccumulation * 0.5F) * macroErosionNoise;

        float rubbleAmplitude = 0.035F;
        float roughness = detailNoise * rubbleMask * rubbleAmplitude;

        // --- 5. APPLY MODIFICATIONS ---
        float modifiedProgress = progress - (combinedGullyNoise * slopeMask * dynamicGullyDepth) + roughness;
        modifiedProgress = NoiseUtil.clamp(modifiedProgress, 0.0F, 1.0F);

        // Apply standard S-curve smoothing
        float smoothProgress = modifiedProgress * modifiedProgress * (3.0F - 2.0F * modifiedProgress);

        return NoiseUtil.lerp(targetValleyFloor, originalTerrainHeight, smoothProgress);
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

        // FIX: Because banksWidth is no longer multiplying by an overblown 3.5x scale factor,
        // child forks will now correctly scale down (~85% width of parent) instead of widening exponentially.
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



