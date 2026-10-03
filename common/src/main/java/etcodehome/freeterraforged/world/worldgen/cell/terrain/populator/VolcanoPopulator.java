package etcodehome.freeterraforged.world.worldgen.cell.terrain.populator;

import etcodehome.freeterraforged.world.worldgen.biome.Erosion;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.CellPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.RegionConfig;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.function.CellFunction;
import etcodehome.freeterraforged.world.worldgen.noise.function.DistanceFunction;
import etcodehome.freeterraforged.world.worldgen.noise.function.EdgeFunction;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.util.Seed;

public class VolcanoPopulator implements CellPopulator, WeightedPopulator {
    private Noise cone;
    private Noise height;
    private Noise lowlands;
    private float inversionPoint;
    private float blendLower;
    private float blendUpper;
    private float blendRange;
    private float bias;
    private Terrain inner;
    private Terrain outer;
    private float weight;
    private final Noise baseErosion;
    private final Noise baseWeirdness;

    public VolcanoPopulator(Seed seed, RegionConfig region, Levels levels, Noise baseErosion, Noise baseWeirdness, float weight) {
        this.baseErosion = baseErosion;
        this.baseWeirdness = baseWeirdness;
        float midpoint = 0.3F;
        float range = 0.3F;
        Noise heightLookup = Noises.perlin(seed.next(), 2, 1);
        heightLookup = Noises.map(heightLookup, 0.45F, 0.65F);

        Noise heightNoise = Noises.worley(region.seed(), region.scale(), CellFunction.NOISE_LOOKUP, DistanceFunction.EUCLIDEAN, heightLookup);
        heightNoise = Noises.warp(heightNoise, region.warpX(), region.warpZ(), region.warpStrength());
        this.height = heightNoise;

        Noise cone = Noises.worleyEdge(region.seed(), region.scale(), EdgeFunction.DISTANCE_2_DIV, DistanceFunction.EUCLIDEAN);
        cone = Noises.invert(cone);
        cone = Noises.warp(cone, region.warpX(), region.warpZ(), region.warpStrength());
        cone = Noises.powCurve(cone, 11.0F);
        cone = Noises.clamp(cone, 0.475F, 1.0F);
        cone = Noises.map(cone, 0.0F, 1.0F);
        cone = Noises.gradient(cone, 0.0F, 0.5F, 0.5F);
        cone = Noises.warpPerlin(cone, seed.next(), 15, 2, 10.0F);
        cone = Noises.mul(cone, this.height);

        this.cone = cone;

        Noise lowlands = Noises.perlinRidge(seed.next(), 150, 3);
        lowlands = Noises.warpPerlin(lowlands, seed.next(), 30, 1, 30.0F);
        lowlands = Noises.mul(lowlands, 0.1F);

        this.lowlands = lowlands;
        this.inversionPoint = 0.94F;
        this.blendLower = midpoint - range / 2.0F;
        this.blendUpper = this.blendLower + range;
        this.blendRange = this.blendUpper - this.blendLower;
        this.outer = TerrainType.VOLCANO;
        this.inner = TerrainType.VOLCANO_PIPE;
        this.bias = levels.ground;

        this.weight = weight;
    }

    public float weight() {
        return this.weight;
    }

    @Override
    public void apply(Cell cell, float x, float z) {
        float value = this.cone.compute(x, z, 0);
        float limit = this.height.compute(x, z, 0);
        float maxHeight = limit * this.inversionPoint;

        // 0 on the lowlands, 1 on the cone: smooth Hermite ramp for volcano influence
        float influence = value > maxHeight
                ? 1.0F
                : NoiseUtil.interpHermite(NoiseUtil.clamp((value - this.blendLower) / this.blendRange, 0.0F, 1.0F));

        float baseErosionVal = this.baseErosion.compute(x, z, 0);
        float baseWeirdnessVal = this.baseWeirdness.compute(x, z, 0);

        cell.erosion = NoiseUtil.lerp(baseErosionVal, Erosion.LEVEL_4.mid(), influence);

        // Dynamically compute target weirdness matching volcano elevation profile
        float targetWeirdness;
        if (value > maxHeight) {
            // Inside crater pipe: Transitions from Peak (>0.7) at rim down to Valley Floor (<-0.2) in pipe center
            float range = limit - maxHeight;
            float alpha = range > 0.0F ? (value - maxHeight) / range : 0.0F;
            targetWeirdness = NoiseUtil.lerp(0.85F, -0.50F, NoiseUtil.clamp(alpha, 0.0F, 1.0F));
        } else {
            // Outer cone: Progresses from lowland apron (-0.1 Low Ridge) up to high rim (+0.85 High Ridge / Peak)
            float coneProgress = maxHeight > 0.0F ? NoiseUtil.clamp(value / maxHeight, 0.0F, 1.0F) : 0.0F;
            targetWeirdness = NoiseUtil.lerp(-0.10F, 0.85F, coneProgress);
        }

        cell.weirdness = NoiseUtil.lerp(baseWeirdnessVal, targetWeirdness, influence);

        if (value > maxHeight) {
            float steepnessModifier = 1.0F;
            float delta = (value - maxHeight) * steepnessModifier;
            float range = limit - maxHeight;
            float alpha = range > 0.0F ? delta / range : 0.0F;
            if (alpha > 0.925F) {
                cell.terrain = this.inner;
            }
            value = maxHeight - (maxHeight / 5.0F) * alpha;
        } else if (value < this.blendLower) {
            value += this.lowlands.compute(x, z, 0);
            cell.terrain = this.outer;
        } else if (value < this.blendUpper) {
            float alpha2 = 1.0F - (value - this.blendLower) / this.blendRange;
            value += this.lowlands.compute(x, z, 0) * alpha2;
            cell.terrain = this.outer;
        }
        cell.height = this.bias + value;
    }

    public static void modifyVolcanoType(Cell cell, Levels levels) {
        if (cell.terrain == TerrainType.VOLCANO_PIPE && (cell.height < levels.water || cell.riverMask < 0.85F)) {
            cell.terrain = TerrainType.VOLCANO;
        }
    }
}