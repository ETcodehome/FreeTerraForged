package etcodehome.freeterraforged.world.worldgen.cell.terrain.populator;

import etcodehome.freeterraforged.world.worldgen.biome.Erosion;
import etcodehome.freeterraforged.world.worldgen.biome.Weirdness;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.RegionConfig;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.function.CellFunction;
import etcodehome.freeterraforged.world.worldgen.noise.function.DistanceFunction;
import etcodehome.freeterraforged.world.worldgen.noise.function.EdgeFunction;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.CellPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

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
        this.inversionPoint = 0.94F; // Restored original rim inversion threshold
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

        // Smooth Hermite ramp for gradual erosion and weirdness transition up the volcano flanks
        float influence = value > maxHeight
                ? 1.0F
                : NoiseUtil.interpHermite(NoiseUtil.clamp((value - this.blendLower) / this.blendRange, 0.0F, 1.0F));

        // High erosion and targeted biome parameters create scarred slopes leading up to the rim
        cell.erosion = NoiseUtil.lerp(this.baseErosion.compute(x, z, 0), Erosion.LEVEL_4.mid(), influence);
        cell.weirdness = NoiseUtil.lerp(this.baseWeirdness.compute(x, z, 0), Weirdness.LOW_SLICE_NORMAL_DESCENDING.mid(), influence);

        if (value > maxHeight) {
            float delta = value - maxHeight;
            float range = Math.max(0.001F, limit - maxHeight);
            float alpha = NoiseUtil.clamp(delta / range, 0.0F, 1.0F);

            // STRICT PIPE BOUNDARY: Keeps the conduit footprint identical to original size (alpha > 0.925F)
            if (alpha > 0.925F) {
                cell.terrain = this.inner; // VOLCANO_PIPE (triggers subterranean lava carving)
            } else {
                cell.terrain = this.outer; // VOLCANO (crater rim and inner bowl walls)
            }

            // Caldera bowl profile: Peaks at rim crest (alpha = 0.0), drops smoothly inward
            float bowlCurve = NoiseUtil.interpHermite(alpha);
            float dropDepth = maxHeight * 0.35F * bowlCurve; // 35% drop from rim to crater floor

            // Sub-block roughness along inner crater walls for a scarred, eroded rock look
            float wallScarring = (float) (Math.sin(x * 0.12F + z * 0.08F) * Math.cos(z * 0.12F - x * 0.08F)) * 0.02F * bowlCurve;

            value = maxHeight - dropDepth + wallScarring;
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