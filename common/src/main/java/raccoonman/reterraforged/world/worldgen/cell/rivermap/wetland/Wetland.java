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
    private final Noise bankRoughness;
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
        this.bankRoughness = Noises.simplex(++seed, 45, 2);
    }

    public void apply(Cell cell, float rx, float rz, float x, float z) {

        float upliftOffset = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable);
        float oceanHeightOffset = levels.scale(levels.waterLevel);
        float localWaterSurface = oceanHeightOffset + upliftOffset;

        float singleBlock = levels.ground(1) - levels.ground(0);

        float bed = localWaterSurface - (3.5F * singleBlock);

        if (cell.height < bed) return;

        float warpStrength = 8.0F;
        float wx = rx + this.warpNoise.compute(x, z, 0) * warpStrength;
        float wz = rz + this.warpNoise.compute(x, z, 1) * warpStrength;
        float t = Line.distanceOnLine(wx, wz, this.a.x(), this.a.y(), this.b.x(), this.b.y());
        float d2 = getDistance2(wx, wz, this.a.x(), this.a.y(), this.b.x(), this.b.y(), t);

        if (d2 > this.radius2) return;

        float dist = 1.0F - d2 / this.radius2;
        float banks = cell.height;

        float edgeWarp = this.warpNoise.compute(x * 0.2F, z * 0.2F, 3) * 0.15F;
        float warpedDist = dist + edgeWarp;
        warpedDist = Math.clamp(warpedDist, 0.0F, 1.0F);

        float tEnd = 0.7F;
        float rawAlpha = NoiseUtil.map(warpedDist, 0.0F, tEnd, tEnd);
        rawAlpha = Math.clamp(rawAlpha, 0.0F, 1.0F);

        float internalAlpha = rawAlpha * rawAlpha * (3.0F - 2.0F * rawAlpha);

        float rivuletNoise = Math.abs(this.warpNoise.compute(x * 0.4F, z * 0.4F, 2));
        float slopeMask = (float) Math.sin(internalAlpha * Math.PI);
        if (slopeMask > 0.0F) {
            internalAlpha += rivuletNoise * 0.25F * slopeMask;
        }
        internalAlpha = Math.clamp(internalAlpha, 0.0F, 1.0F);

        float heightDiscrepancy = banks - bed;
        float maxDiscrepancy = 30.0F * singleBlock;
        float discrepancyFactor = NoiseUtil.clamp(heightDiscrepancy / maxDiscrepancy, 0.0F, 1.0F);
        float blendedBed = NoiseUtil.lerp(bed, banks, 0.4F * discrepancyFactor);

        float edgeDistance = (1.0F - warpedDist) * this.radius;
        float maxWetlandSlope = 0.325f; //(float) Math.tan(Math.toRadians(18.0F));
        float maxRise = edgeDistance * maxWetlandSlope;
        float thresholdHeight = blendedBed + maxRise;

        float smoothHeight = NoiseUtil.lerp(banks, blendedBed, internalAlpha);
        float targetHeight = Math.min(smoothHeight, thresholdHeight);

        float roughness = this.bankRoughness.compute(x, z, 0);
        float wallMask = (1.0F - internalAlpha) * internalAlpha * 4.0F;
        targetHeight += roughness * wallMask * 2.0F * singleBlock;

        if (cell.height > targetHeight) {
            cell.height = targetHeight;
        }

        if (internalAlpha > 0.0F) {
            cell.riverWaterLevel = upliftOffset;
        }

        float featureEdge = Math.min(dist, warpedDist);

        float localMoundMin = localWaterSurface + (1.0F * singleBlock);
        float localMoundMax = localWaterSurface + (2.0F * singleBlock);
        float localMoundVariance = localMoundMax - localMoundMin;

        if (cell.height >= bed && cell.height < localMoundMax) {
            float moundMask = NoiseUtil.clamp((internalAlpha - 0.7F) / 0.3F, 0.0F, 1.0F);
            float shapeAlpha = this.moundShape.compute(x, z, 0) * moundMask;
            float moundHeightNoise = this.moundHeight.compute(x, z, 0);
            float mounds = localMoundMin + (moundHeightNoise * localMoundVariance);

            float moundEdgeEnd = tEnd - 0.1F;
            float moundEdgeFade = NoiseUtil.clamp((featureEdge - moundEdgeEnd)/(tEnd - moundEdgeEnd), 0.0F, 1.0F);

            cell.height = NoiseUtil.lerp(cell.height, mounds, shapeAlpha * 0.8F * moundEdgeFade);
        }

        if (featureEdge > tEnd && cell.height < localWaterSurface + (5.0F * singleBlock)) {
            cell.terrain = TerrainType.WETLAND;
            cell.erosionMask = true;
        }

        cell.riverMask = Math.min(cell.riverMask, 1.0F - internalAlpha);
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