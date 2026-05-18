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
    private float mutableLakeLevel;
    private boolean isLevelLocked;
    protected Vec2f center;

    // Persistent fields to lock the lake's geometry across all cells in this instance
    private float flatWaterLevel = -1.0F;
    private float flatFloorLevel = -1.0F;
    private float flatBankBias = -1.0F;
    private float flatBankHeight = -1.0F;

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

        // Default fallback height until locked by the center evaluation context
        this.mutableLakeLevel = 62.0F / 255.0F;
        this.isLevelLocked = false;
    }

    public void apply(Cell cell, float x, float z) {
        float distance2 = this.getDistance2(x, z);

        // rough up the lake surroundings
        float hash = (float) (Math.sin(x * 0.123F + z * 0.456F) * Math.cos(z * 0.123F - x * 0.456F));
        float wallRuggednessFactor = 2.0F;
        distance2 += hash * wallRuggednessFactor * this.valley;

        if (distance2 > this.valley2) {
            return;
        }

        // Initialize and lock the master vertical calculations
        this.updateWaterLevels(cell);

        float bankHeight = this.getBankHeight(cell);

        // --- CENTER-POINT SELECTION LATCH ---
        if (!this.isLevelLocked && distance2 < 4.0F) {
            this.lockCenterLevel(bankHeight);
        }

        if (distance2 <= this.lakeDistance2) {
            this.applyLakeBasin(cell, distance2, bankHeight);
        } else {
            this.applyValleyDepression(cell, distance2, bankHeight);
        }

        if (!this.isLevelLocked) {
            this.lockCenterLevel(bankHeight);
        }

        // Any cell that falls within the active footprint of this lake's
        // bounding math must share the center-locked riverWaterLevel metadata.
        // This prevents the pipeline from seeing a modified riverMask with an unassigned water height.
        cell.riverWaterLevel = this.mutableLakeLevel;
    }

    /**
     * Synchronized lock to cleanly capture the center height across processing worker threads.
     */
    private synchronized void lockCenterLevel(float centerBankHeight) {
        if (!this.isLevelLocked) {
            this.mutableLakeLevel = centerBankHeight;
            this.isLevelLocked = true;
        }
    }

    /**
     * Initializes the static vertical levels for this lake instance to maintain uniform geometry.
     */
    private void updateWaterLevels(Cell cell) {
        if (this.flatWaterLevel >= 0.0F) {
            return; // Already initialized
        }

        this.flatBankBias = ContinentalHydrology.getWeightedWaterHeight(cell.continentUplift);
        this.flatWaterLevel = ContinentalHydrology.getWeightedWaterHeight(cell.continentUplift) + this.oceanLevel;
        this.flatFloorLevel = this.flatWaterLevel - this.depth;

        float bankHeightAlpha = NoiseUtil.map(cell.height, this.bankAlphaMin, this.bankAlphaMax, this.bankAlphaRange);
        float bankVariance = NoiseUtil.lerp(this.bankMin, this.bankMax, bankHeightAlpha);
        float minimumClearance = 0.01F;

        this.flatBankHeight = this.flatWaterLevel + minimumClearance + Math.max(0.0F, bankVariance) - this.oceanLevel;
    }

    /**
     * Handles the water-filled center of the lake and shapes the underlying basin floor.
     */
    private void applyLakeBasin(Cell cell, float distance2, float bankHeight) {
        cell.height = Math.min(bankHeight, cell.height);

        if (distance2 < this.lakeDistance2) {
            float depthAlpha = 1.0F - distance2 / this.lakeDistance2;
            depthAlpha = NoiseUtil.clamp(depthAlpha, 0.0F, 1.0F);

            // Carve the height down to the flat floor
            cell.height = NoiseUtil.lerp(cell.height, this.flatFloorLevel, depthAlpha);

            // Set metadata for StrataRule water injection and Biome selection
            cell.terrain = TerrainType.LAKE;
            cell.riverMask = Math.min(cell.riverMask, 1.0F - depthAlpha);
        }
    }

    /**
     * Handles the wider land depression, creating the slopes down to the shore.
     */
    private void applyValleyDepression(Cell cell, float distance2, float bankHeight) {

        float valleyAlpha = 1.0F - (distance2 - this.lakeDistance2) / this.valleyDistance2;
        valleyAlpha = NoiseUtil.clamp(valleyAlpha, 0.0F, 1.0F);

        // Standard smoothstep easing (3t^2 - 2t^3) to ensure a perfectly clean transition slope
        float smoothAlpha = valleyAlpha * valleyAlpha * (3.0F - 2.0F * valleyAlpha);

        // Blend the terrain smoothly down to the master center-based bank height
        cell.height = NoiseUtil.lerp(cell.height, bankHeight, smoothAlpha);

        // tiny offset to force terrain one block higher.
        if (cell.height < bankHeight){
            cell.height = bankHeight;
        }

        // Update the mask cleanly so it scales back up to 1.0 at the absolute outer edge
        cell.riverMask = Math.min(cell.riverMask, 1.0F - smoothAlpha);
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

    protected float getBankHeight(Cell cell) {
        if (this.flatBankHeight >= 0) {
            return this.flatBankHeight + 0.01F;
        }

        float bias = (this.flatBankBias < 0) ? (cell.continentEdge * 0.45F) : this.flatBankBias;
        float bankHeightAlpha = NoiseUtil.map(cell.height, this.bankAlphaMin, this.bankAlphaMax, this.bankAlphaRange);
        return NoiseUtil.lerp(this.bankMin, this.bankMax, bankHeightAlpha) + bias;
    }
}