package raccoonman.reterraforged.world.worldgen.cell.rivermap.wetland;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.util.Boundsf;

public class Wetland {
    private Vec2f a;
    private Vec2f b;
    private float radius;
    private float radius2;
    private float bed;
    private float banks;
    private float moundMin;
    private float moundMax;
    private float moundVariance;
    private Noise moundShape;
    private Noise moundHeight;
    private Noise terrainEdge;
    private Noise rivuletNoise; // Sharp carving for gulleys
    private Noise warpNoise;    // Meandering distortion
    private Levels levels;
    private int seed;

    public Wetland(int seed, Vec2f a, Vec2f b, float radius, Levels levels) {
        this.seed = seed;
        this.a = a;
        this.b = b;
        this.radius = radius;
        this.radius2 = radius * radius;
        this.levels = levels;

        // Mound/terrain logic
        this.moundShape = Noises.map(Noises.clamp(Noises.perlin(++seed, 10, 1), 0.3F, 0.6F), 0.0F, 1.0F);
        this.moundHeight = Noises.map(Noises.clamp(Noises.simplex(++seed, 20, 1), 0.0F, 0.3F), 0.0F, 1.0F);
        this.terrainEdge = Noises.map(Noises.clamp(Noises.perlin(++seed, 8, 1), 0.2F, 0.8F), 0.0F, 0.9F);

        // Rivulet noise - Ridge is perfect for sharp, eroded gulley "veins"
        this.rivuletNoise = Noises.perlinRidge(++seed, 12, 3);

        // Warp noise - Distorts the wetland path so it's not a straight line
        this.warpNoise = Noises.perlin(++seed, 25, 2);
    }

    public void apply(Cell cell, float rx, float rz, float x, float z) {

        float upliftOffset = (ContinentalHydrology.getWeightedWaterHeight(cell.waterTable));
        float wetlandDepthOffset = levels.scale(3);
        float oceanHeightOffset = levels.scale(levels.waterLevel);

        this.bed = oceanHeightOffset + upliftOffset - wetlandDepthOffset;

        if (cell.height < this.bed) return;

        float t = Line.distanceOnLine(rx, rz, this.a.x(), this.a.y(), this.b.x(), this.b.y());
        float d2 = getDistance2(rx, rz, this.a.x(), this.a.y(), this.b.x(), this.b.y(), t);
        if (d2 > this.radius2) return;

        float dist = 1.0F - d2 / this.radius2;
        float singleBlock = levels.ground(1) - levels.ground(0);
        this.banks = this.bed;

        // We use a fixed range for thresholds, only varying the intensity by noise
        float tStart = 0.4F; // Start eroding here
        float tEnd = 0.7F;   // Hit the swamp floor here

        // Create a smooth 0.0 -> 1.0 alpha across the whole transition zone
        float totalAlpha = NoiseUtil.map(dist, 0.0F, tEnd, tEnd);
        totalAlpha = NoiseUtil.interpQuintic(totalAlpha);
        totalAlpha = Math.max(0, Math.min(1, totalAlpha));

        // We calculate a target height that moves from Banks -> Bed based on how deep into the swamp we are.
        float internalAlpha = NoiseUtil.map(dist, tStart, tEnd, tEnd - tStart);
        internalAlpha = Math.max(0, Math.min(1, internalAlpha));
        float targetHeight = NoiseUtil.lerp(this.banks, this.bed, internalAlpha);

        // Apply the height change smoothly
        if (cell.height > targetHeight) {
            cell.height = NoiseUtil.lerp(cell.height, targetHeight, totalAlpha);
        }

        // Use internalAlpha to fade these in
        if (internalAlpha > 0.1F) {
            cell.terrain = TerrainType.WETLAND;
            cell.riverWaterLevel = upliftOffset;
            if (internalAlpha > 0.8F) cell.erosionMask = true;
        }

        // We only apply mounds where internalAlpha is significant (the flat area)
        if (internalAlpha > 0.5F) {
            float moundArea = NoiseUtil.map(dist, tEnd, 1.0F, 1.0F - tEnd);
            float shapeAlpha = this.moundShape.compute(x, z, 0) * moundArea;
            float moundHeightNoise = this.moundHeight.compute(x, z, 0);
            float moundElev = this.bed + (moundHeightNoise * 2 * singleBlock);

            // Use a softer lerp for mounds to keep them organic
            cell.height = NoiseUtil.lerp(cell.height, moundElev, shapeAlpha * 0.8F);
        }

        cell.riverMask = Math.min(cell.riverMask, 1.0F - totalAlpha);
    }
    
    public void recordBounds(Boundsf.Builder builder) {
        builder.record(Math.min(this.a.x(), this.b.x()) - this.radius, Math.min(this.a.y(), this.b.y()) - this.radius);
        builder.record(Math.max(this.a.x(), this.b.x()) + this.radius, Math.max(this.a.y(), this.b.y()) + this.radius);
    }
    
    private static float getDistance2(float x, float y, float ax, float ay, float bx, float by, float t) {
        if (t <= 0.0f) {
            return Line.distSq(x, y, ax, ay);
        }
        if (t >= 1.0f) {
            return Line.distSq(x, y, bx, by);
        }
        float px = ax + t * (bx - ax);
        float py = ay + t * (by - ay);
        return Line.distSq(x, y, px, py);
    }
}
