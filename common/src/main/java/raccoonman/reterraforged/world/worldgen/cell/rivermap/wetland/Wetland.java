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
    private final Vec2f a;
    private final Vec2f b;
    private final float radius;
    private final float radius2;
    private final Noise moundShape;
    private final Noise moundHeight;
    private final Noise warpNoise;
    private final Levels levels;

    public Wetland(int seed, Vec2f a, Vec2f b, float radius, Levels levels) {

        // river headwater
        this.a = a;

        // river delta
        this.b = b;

        // scale control parameters
        this.radius = radius;
        this.radius2 = radius * radius;

        // global level information (for checking ocean heights etc)
        this.levels = levels;

        // Mound noise - controls the pattern of the land/water mix in wetlands
        this.moundShape = Noises.map(Noises.clamp(Noises.perlin(++seed, 10, 1), 0.3F, 0.6F), 0.0F, 1.0F);
        this.moundHeight = Noises.map(Noises.clamp(Noises.simplex(++seed, 20, 1), 0.0F, 0.3F), 0.0F, 1.0F);

        // Warp noise - Distorts the wetland path so it's not a straight line
        this.warpNoise = Noises.perlin(++seed, 25, 2);
    }

    public void apply(Cell cell, float rx, float rz, float x, float z) {

        // calculate the globally consistent water level at this cell
        float upliftOffset = (ContinentalHydrology.getWeightedWaterHeight(cell.waterTable));
        float oceanHeightOffset = levels.scale(levels.waterLevel);
        float localWaterSurface = oceanHeightOffset + upliftOffset;

        // generate a single block height factor so we can offset heights by single layers easily
        float singleBlock = levels.ground(1) - levels.ground(0);

        // Define the wetland bed. Mound builders will build up above the water level
        // this value roughly equates to 2 block depressions below water level for pools
        float bed = localWaterSurface - (3.5F * singleBlock);

        // early exit guard to prevent filling already carved surfaces
        if (cell.height < bed) return;

        // calculate warp meandering
        float warpStrength = 8.0F;
        float wx = rx + this.warpNoise.compute(x, z, 0) * warpStrength;
        float wz = rz + this.warpNoise.compute(x, z, 1) * warpStrength;
        float t = Line.distanceOnLine(wx, wz, this.a.x(), this.a.y(), this.b.x(), this.b.y());
        float d2 = getDistance2(wx, wz, this.a.x(), this.a.y(), this.b.x(), this.b.y(), t);

        // exit if we're outside the influence of the swamp
        if (d2 > this.radius2) return;

        // We use a fixed range for thresholds, only varying the intensity by noise
        float dist = 1.0F - d2 / this.radius2;
        float banks = cell.height;
        float tStart = 0.4F;
        float tEnd = 0.7F;
        float totalAlpha = NoiseUtil.map(dist, 0.0F, tEnd, tEnd);

        // Add ridges and rivulets to the otherwise featureless walls
        float rivuletNoise = Math.abs(this.warpNoise.compute(x * 0.4F, z * 0.4F, 2));
        float slopeMask = (float) Math.sin(totalAlpha * Math.PI); // Peaks at 1.0 midway down the wall

        if (slopeMask > 0.0F) {
            // Pushing totalAlpha higher carves down into the wall, creating rivulets
            totalAlpha += rivuletNoise * 0.25F * slopeMask;
        }

        totalAlpha = Math.max(0, Math.min(1, totalAlpha));
        totalAlpha = NoiseUtil.interpQuintic(totalAlpha);

        float internalAlpha = NoiseUtil.map(dist, tStart, tEnd, tEnd - tStart);
        internalAlpha = Math.max(0, Math.min(1, internalAlpha));
        internalAlpha = NoiseUtil.interpQuintic(internalAlpha); // Smoothen the transition

        // InternalAlpha to scale the carving so it tapers off
        // entirely at the edge of the influence radius.
        float targetHeight = NoiseUtil.lerp(banks, bed, internalAlpha);
        if (cell.height > targetHeight) {
            cell.height = NoiseUtil.lerp(banks, targetHeight, internalAlpha);
        }

        // Restrict Biome and Water Level to the basin
        if (internalAlpha > 0.1F) {
            cell.riverWaterLevel = upliftOffset;
        }

        if (dist >= tEnd) {
            cell.terrain = TerrainType.WETLAND;
            cell.erosionMask = true;
        }

        // Strict Biome Containment
        // Ensure water level is set for the carved area so pools form correctly against the walls
        if (internalAlpha > 0.0F) {
            cell.riverWaterLevel = upliftOffset;
        }

        // Strictly restrict the Wetland biome to the flat bottom of the bowl.
        // The walls (dist < tEnd) will retain their original biome, meaning the fade-out
        // happens entirely in the surrounding biomes.
        if (dist >= tEnd) {
            cell.terrain = TerrainType.WETLAND;
            cell.erosionMask = true;
        }

        // Dynamic island ranges relative to water level
        float localMoundMin = localWaterSurface + (1.0F * singleBlock);
        float localMoundMax = localWaterSurface + (2.0F * singleBlock);
        float localMoundVariance = localMoundMax - localMoundMin;

        // Hummocks with smooth slope encroachment
        if (cell.height >= bed && cell.height < localMoundMax) {
            float shapeAlpha = this.moundShape.compute(x, z, 0) * totalAlpha;
            float moundHeightNoise = this.moundHeight.compute(x, z, 0);
            float mounds = localMoundMin + (moundHeightNoise * localMoundVariance);

            cell.height = NoiseUtil.lerp(cell.height, mounds, shapeAlpha * 0.8F);
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