package raccoonman.reterraforged.world.worldgen.cell.rivermap.lake;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.util.Boundsf;

public class Lake {
    protected float valley;
    protected float valley2;
    protected float lakeDistance2;
    protected float valleyDistance2;
    protected float bankAlphaMin;
    protected float bankAlphaMax;
    protected float bankAlphaRange;
    private float depth;
    private float bankMin;
    private float bankMax;
    private float oceanLevel;
    protected Vec2f center;

    public Lake(Vec2f center, float radius, float multiplier, LakeConfig config) {
        float lake = radius * multiplier;
        float valley = 275.0F * multiplier;
        this.valley = valley;
        this.valley2 = valley * valley;
        this.center = center;
        this.depth = config.depth;
        this.bankMin = config.bankMin;
        this.bankMax = config.bankMax;
        this.bankAlphaMin = config.bankMin;
        this.bankAlphaMax = Math.min(1.0F, this.bankAlphaMin + 0.275F);
        this.bankAlphaRange = this.bankAlphaMax - this.bankAlphaMin;
        this.lakeDistance2 = lake * lake;
        this.valleyDistance2 = this.valley2 - this.lakeDistance2;
        this.oceanLevel = config.oceanLevel;
    }

    public void apply(Cell cell, float x, float z) {
        float distance2 = this.getDistance2(x, z);

        // Rough up the lake surroundings
        float hash = (float) (Math.sin(x * 0.123F + z * 0.456F) * Math.cos(z * 0.123F - x * 0.456F));
        float wallRuggednessFactor = 2.0F;
        distance2 += hash * wallRuggednessFactor * this.valley;

        if (distance2 > this.valley2) {
            return;
        }

        // 1. Core baseline flatness check
        float rawFlatness = ContinentalHydrology.getFlatnessFactor(cell.waterTable);
        rawFlatness = NoiseUtil.clamp(rawFlatness, 0.0F, 1.0F);

        // 2. Continuous Domain Noise Perturbation
        float noiseFreq = 0.02F;
        float noiseSample = (float) Math.sin(x * noiseFreq + Math.cos(z * noiseFreq)) * (float) Math.cos(z * noiseFreq - Math.sin(x * noiseFreq));

        // 3. Modulate variation by raw flatness to protect step cliffs
        float flatnessVariance = noiseSample * 0.18F * rawFlatness;
        float flatnessFactor = NoiseUtil.clamp(rawFlatness + flatnessVariance, 0.0F, 1.0F);

        // 4. CONTINUOUS PER-CELL HEIGHT EVALUATION
        float localWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + this.oceanLevel;
        float localFloorLevel = localWaterLevel - this.depth;

        // Compute the dynamic shore line limits for this specific spatial point
        float bankHeightAlpha = NoiseUtil.map(cell.height, this.bankAlphaMin, this.bankAlphaMax, this.bankAlphaRange);
        float bias = cell.continentEdge * 0.45F;
        float bankHeight = NoiseUtil.lerp(this.bankMin, this.bankMax, bankHeightAlpha) + bias;

        // HARD CONTAINER RULE: The shoreline height must always sit above the local water plane
        float minShorelineHeight = localWaterLevel + 0.015F;
        if (bankHeight < minShorelineHeight) {
            bankHeight = minShorelineHeight;
        }

        float carvedHeight = cell.height;

        // Run continuous layout pass using localized spatial parameters
        if (distance2 <= this.lakeDistance2 && flatnessFactor > 0.0F) {
            carvedHeight = this.calculateLakeBasinHeight(cell, distance2, bankHeight, localFloorLevel, flatnessFactor);
        } else {
            carvedHeight = this.calculateValleyDepressionHeight(cell, distance2, bankHeight, minShorelineHeight);
        }

        // --- ADJACENT LAKE PROTECTION (MIN BLEND) ---
        if (carvedHeight < cell.height) {
            cell.height = carvedHeight;
        }

        // Apply metadata indicators if inside valid basin boundaries
        if (distance2 <= this.lakeDistance2 && flatnessFactor > 0.0F && cell.height <= bankHeight + 0.001F) {
            float depthAlpha = NoiseUtil.clamp(1.0F - distance2 / this.lakeDistance2, 0.0F, 1.0F);
            cell.terrain = TerrainType.LAKE;
            cell.riverMask = Math.min(cell.riverMask, 1.0F - (depthAlpha * flatnessFactor));
        }

        // --- SOLID LAND SHORE RING RE-ENFORCEMENT ---
        // EXCLUSION ENFORCED: If this is an active river channel or river feature, do not push its height
        // up to the shoreline level. This allows pre-existing or adjacent river networks to pass seamlessly
        // into the lake basin without creating horizontal soil blockages/dams.
        if (cell.terrain != TerrainType.LAKE && cell.terrain != TerrainType.RIVER && cell.riverMask >= 1.0F) {
            if (cell.height < minShorelineHeight) {
                cell.height = minShorelineHeight;
            }
        }

        // Share water level context across the active footprint
        if (cell.riverWaterLevel <= 0.0F || localWaterLevel < cell.riverWaterLevel) {
            cell.riverWaterLevel = localWaterLevel;
        }
    }

    /**
     * Computes the basin floor profile using fully continuous spatial context parameterizations.
     */
    private float calculateLakeBasinHeight(Cell cell, float distance2, float bankHeight, float localFloorLevel, float flatnessFactor) {
        float targetBase = Math.min(bankHeight, cell.height);

        if (distance2 < this.lakeDistance2) {
            float depthAlpha = 1.0F - distance2 / this.lakeDistance2;
            depthAlpha = NoiseUtil.clamp(depthAlpha, 0.0F, 1.0F);

            // Modulate depth seamlessly towards the local floor profile
            float activeFloor = NoiseUtil.lerp(targetBase, localFloorLevel, flatnessFactor);
            return NoiseUtil.lerp(targetBase, activeFloor, depthAlpha);
        }
        return targetBase;
    }

    /**
     * Calculates a continuous valley slope container that locks perfectly to the local minimum threshold.
     */
    private float calculateValleyDepressionHeight(Cell cell, float distance2, float bankHeight, float minShorelineHeight) {
        float valleyAlpha = 1.0F - (distance2 - this.lakeDistance2) / this.valleyDistance2;
        valleyAlpha = NoiseUtil.clamp(valleyAlpha, 0.0F, 1.0F);

        float smoothAlpha = valleyAlpha * valleyAlpha * (3.0F - 2.0F * valleyAlpha);

        if (cell.height > bankHeight) {
            float blendedHeight = NoiseUtil.lerp(cell.height, bankHeight, smoothAlpha);
            return Math.max(minShorelineHeight, blendedHeight);
        }

        return cell.height;
    }

    public void recordBounds(Boundsf.Builder builder) {
        builder.record(this.center.x() - this.valley * 1.2F, this.center.y() - this.valley * 1.2F);
        builder.record(this.center.x() + this.valley * 1.2F, this.center.y() + this.valley * 1.2F);
    }

    public boolean overlaps(float x, float z, float radius2) {
        float dist2 = this.getDistance2(x, z);
        return dist2 < this.lakeDistance2 + radius2;
    }

    protected float getDistance2(float x, float z) {
        float dx = this.center.x() - x;
        float dz = this.center.y() - z;
        return dx * dx + dz * dz;
    }
}