package raccoonman.reterraforged.world.worldgen.feature;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import raccoonman.reterraforged.tags.RTFBlockTags;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.feature.ErodeFeature.Config;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ErodeFeature extends Feature<Config> {

    // Internal fixed modifiers
    private static final float SEDIMENT_NOISE = 3F / 255F;
    private static final float SCREE_VALUE = 0.55F;

    // Noise sampling parameters
    private static final float CLUSTER_SCALE = 0.05F;
    private static final float WARP_SCALE = 0.04F;
    private static final float WARP_STRENGTH = 22.0F;

    // Internal Mixture Matrices
    private static final WeightedBlockSelector SCREE_MATERIALS = new WeightedBlockSelector(List.of(
            new WeightedBlockEntry(Blocks.GRAVEL.defaultBlockState(), 1),
            new WeightedBlockEntry(Blocks.COARSE_DIRT.defaultBlockState(), 1),
            new WeightedBlockEntry(Blocks.ANDESITE.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.TUFF.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.MOSS_BLOCK.defaultBlockState(), 1)
    ));

    private static final WeightedBlockSelector DIRT_MATERIALS = new WeightedBlockSelector(List.of(
            new WeightedBlockEntry(Blocks.COARSE_DIRT.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.MOSS_BLOCK.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.GRAVEL.defaultBlockState(), 1)
    ));

    public ErodeFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> placeContext) {
        WorldGenLevel level = placeContext.level();
        RandomState randomState = level.getLevel().getChunkSource().randomState();

        @Nullable
        GeneratorContext generatorContext;
        if((Object) randomState instanceof RTFRandomState rtfRandomState && (generatorContext = rtfRandomState.generatorContext()) != null) {
            ChunkPos chunkPos = new ChunkPos(placeContext.origin());
            int chunkX = chunkPos.x;
            int chunkZ = chunkPos.z;
            ChunkGenerator generator = placeContext.chunkGenerator();
            ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
            Tile.Chunk tileChunk = generatorContext.cache.provideAtChunk(chunkX, chunkZ).getChunkReader(chunkX, chunkZ);
            raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap heightmap = generatorContext.generator.getHeightmap();
            Levels levels = heightmap.levels();

            long worldSeed = heightmap.climate().randomSeed();
            Noise rand = Noises.white((int) worldSeed, 1);

            Noise clusterNoise = Noises.perlin((int) (worldSeed + 7777), 2, 1);
            Noise warpX = Noises.perlin((int) (worldSeed + 1001), 2, 1);
            Noise warpZ = Noises.perlin((int) (worldSeed + 2002), 2, 1);

            Noise desertErosionVariance = makeDesertErosionVariance(levels);
            BlockPos.MutableBlockPos pos = new MutableBlockPos();
            Config config = placeContext.config();
            for(int x = 0; x < 16; x++) {
                for(int z = 0; z < 16; z++) {
                    int worldX = chunkPos.getBlockX(x);
                    int worldZ = chunkPos.getBlockZ(z);

                    Cell cell = tileChunk.getCell(x, z);
                    int scaledY = levels.scale(cell.height);
                    int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                    Holder<Biome> biome = level.getBiome(pos.set(worldX, surfaceY, worldZ));

                    pos.set(worldX, surfaceY, worldZ);

                    if(biome.is(Biomes.DESERT)) {
                        erodeDesert(desertErosionVariance, levels, chunk, cell, pos, surfaceY);
                        continue;
                    }

                    if(surfaceY <= scaledY && surfaceY >= generator.getSeaLevel() - 1 && !biome.is(Biomes.WOODED_BADLANDS) && !biome.is(Biomes.BADLANDS)) {
                        erodeColumn(config, levels, rand, clusterNoise, warpX, warpZ, generator, chunk, cell, pos, surfaceY);
                        // remove any foliage that may have generated above
                        pos.setY(surfaceY);
                        while(!level.getBlockState(pos.setY(pos.getY() + 1)).canSurvive(level, pos)) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    private static Noise makeDesertErosionVariance(Levels levels) {
        Noise noise = Noises.perlin(435, 8, 1);
        return Noises.mul(noise, levels.scale(16));
    }

    private static void erodeDesert(Noise variance, Levels levels, ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY) {
        float min = levels.ground(10);
        float threshold = levels.ground(40);

        if (cell.gradient < 0.15F) {
            return;
        }

        if (cell.height < min) {
            return;
        }

        float value = cell.height + variance.compute(pos.getX(), pos.getZ(), 0);
        if (cell.gradient > 0.3F || value > threshold) {
            BlockState state = Blocks.SMOOTH_SANDSTONE.defaultBlockState();

            if (value > threshold) {
                if (cell.gradient > 0.975) {
                    state = Blocks.TERRACOTTA.defaultBlockState();
                } else if (cell.gradient > 0.85) {
                    state = Blocks.BROWN_TERRACOTTA.defaultBlockState();
                } else if (cell.gradient > 0.75) {
                    state = Blocks.ORANGE_TERRACOTTA.defaultBlockState();
                } else if (cell.gradient > 0.65) {
                    state = Blocks.TERRACOTTA.defaultBlockState();
                }
            }

            for (int dy = 0; dy < 4; dy++) {
                chunk.setBlockState(pos.setY(surfaceY - dy), state, false);
            }
        }
    }

    private static void erodeColumn(Config config, Levels levels, Noise rand, Noise clusterNoise, Noise warpX, Noise warpZ, ChunkGenerator generator, ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY) {
        if (cell.terrain.isRiver() || cell.terrain.isWetland()) {
            return;
        }

        if (cell.terrain == TerrainType.VOLCANO_PIPE) {
            return;
        }

        BlockState top = chunk.getBlockState(pos);
        if(top.is(RTFBlockTags.ERODIBLE)) {
            BlockState material = getMaterial(config, levels, rand, clusterNoise, warpX, warpZ, generator, cell, pos, surfaceY, top, generator instanceof NoiseBasedChunkGenerator noiseChunkGenerator ? noiseChunkGenerator.generatorSettings().value().defaultBlock() : Blocks.STONE.defaultBlockState());
            if (material != top) {
                // Treat TUFF as a decorative band (like Mossy Cobble) rather than running erodeRock on it
                if (material.is(RTFBlockTags.ROCK) && !material.is(Blocks.TUFF)) {
                    erodeRock(chunk, cell, pos, surfaceY);
                    return;
                } else {
                    ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - 4, material);
                }
            }
            placeScree(config, rand, clusterNoise, warpX, warpZ, chunk, cell, pos, surfaceY);
        }
    }

    private static void erodeRock(ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int y) {
        int depth = 32;
        BlockState material = Blocks.GRAVEL.defaultBlockState();
        for (int dy = 3; dy < 32; dy++) {
            pos.setY(y - dy);
            BlockState state = chunk.getBlockState(pos);
            if (state.is(RTFBlockTags.ROCK)) {
                material = state;
                depth = dy + 1;
                break;
            }
        }

        for (int dy = 0; dy < depth; dy++) {
            ColumnDecorator.replaceSolid(chunk, pos.setY(y - dy), material);
        }
    }

    private static void placeScree(Config config, Noise rand, Noise clusterNoise, Noise warpX, Noise warpZ, ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY) {
        int x = pos.getX();
        int z = pos.getZ();
        float steepness = cell.gradient + rand.compute(x, z, 1) * config.slopeModifier();
        if (steepness < config.screeSteepness()) {
            return;
        }

        float sediment = cell.sediment * SEDIMENT_NOISE;
        float noise = rand.compute(x, z, 2) * SEDIMENT_NOISE;
        if (sediment + noise > SCREE_VALUE) {
            BlockState chosenScree = sampleMaterial(SCREE_MATERIALS, x, z, clusterNoise, warpX, warpZ, rand, 3, Blocks.GRAVEL.defaultBlockState());
            ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - 2, chosenScree);
        }
    }

    private static BlockState getMaterial(Config config, Levels levels, Noise rand, Noise clusterNoise, Noise warpX, Noise warpZ, ChunkGenerator generator, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY, BlockState top, BlockState middle) {
        int x = pos.getX();
        int z = pos.getZ();
        float height = cell.height + rand.compute(x, z, 0) * config.heightModifier();
        float steepness = cell.gradient + rand.compute(x, z, 1) * config.slopeModifier();

        // Continental uplift offset in normalized (0.0 - 1.0) float space
        float upliftNormalized = ContinentalHydrology.getComplexWaterHeight(
                cell.waterTable,
                cell.globalContinentScale,
                cell.continentSizeModifier
        );

        // Uplift baseline in normalized float space (sea level + uplift)
        float upliftBaselineNormalized = levels.water + upliftNormalized;

        // Add config relative offsets (converted from 0..255 scale to 0.0..1.0 float scale)
        float effectiveRockBias = upliftBaselineNormalized + (config.rockMin() / 255.0f);
        float effectiveDirtBias = upliftBaselineNormalized + (config.dirtMin() / 255.0f);

        float rockVarFloat = config.rockVar() / 255.0f;
        float dirtVarFloat = config.dirtVar() / 255.0f;

        // Sample noise using the float overload (scale, bias)
        float rockThreshold = ColumnDecorator.sampleNoise(x, z, rockVarFloat, effectiveRockBias);
        float dirtThreshold = ColumnDecorator.sampleNoise(x, z, dirtVarFloat, effectiveDirtBias);

        // Compare normalized 'height' (0.0 - 1.0) against normalized thresholds
        if (steepness > config.rockSteepness() || height > rockThreshold) {
            return rock(middle);
        }

        if (steepness > config.screeSteepness() || height > rockThreshold) {
            return Blocks.TUFF.defaultBlockState();
        }

        if (steepness > config.dirtSteepness() && height > dirtThreshold) {
            return ground(config, clusterNoise, warpX, warpZ, rand, pos, top);
        }

        return top;
    }

    private static BlockState rock(BlockState state) {
        if (state.is(RTFBlockTags.ROCK)) {
            return state;
        }
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState ground(Config config, Noise clusterNoise, Noise warpX, Noise warpZ, Noise rand, BlockPos.MutableBlockPos pos, BlockState state) {
        int x = pos.getX();
        int z = pos.getZ();

        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM)) {
            return sampleMaterial(DIRT_MATERIALS, x, z, clusterNoise, warpX, warpZ, rand, 4, Blocks.COARSE_DIRT.defaultBlockState());
        }
        if (state.is(BlockTags.BASE_STONE_OVERWORLD)) {
            return Blocks.MOSS_BLOCK.defaultBlockState();
        }
        if (state.is(BlockTags.DIRT)) {
            return state;
        }
        if (state.is(Blocks.SAND)) {
            return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        }
        if (state.is(Blocks.RED_SAND)) {
            return Blocks.SMOOTH_RED_SANDSTONE.defaultBlockState();
        }

        return sampleMaterial(DIRT_MATERIALS, x, z, clusterNoise, warpX, warpZ, rand, 4, Blocks.COARSE_DIRT.defaultBlockState());
    }

    private static BlockState sampleMaterial(WeightedBlockSelector selector, int x, int z, Noise clusterNoise, Noise warpX, Noise warpZ, Noise rand, int seedOffset, BlockState fallback) {
        // Standard unwarped noise pass for non-moss blocks
        float rawNoise = clusterNoise.compute(x * CLUSTER_SCALE, z * CLUSTER_SCALE, seedOffset);
        float oldSample = Math.max(0.0f, Math.min(1.0f, (rawNoise + 1.0f) / 2.0f));

        // Domain-warped noise pass for moss
        float warpedSample = sampleWarpedNoise(x, z, CLUSTER_SCALE, WARP_SCALE, WARP_STRENGTH, clusterNoise, warpX, warpZ, rand, seedOffset);

        BlockState warpedState = selector.sample(warpedSample, fallback);

        // If domain-warped noise selects moss, place moss with organic creep boundaries
        if (warpedState.is(Blocks.MOSS_BLOCK)) {
            return warpedState;
        }

        // For all non-moss blocks, use the old unwarped noise
        BlockState oldState = selector.sample(oldSample, fallback);
        if (oldState.is(Blocks.MOSS_BLOCK)) {
            return selector.sampleNonMoss(oldSample, fallback);
        }

        return oldState;
    }

    private static float sampleWarpedNoise(int x, int z, float scale, float warpScale, float warpStrength, Noise clusterNoise, Noise warpX, Noise warpZ, Noise rand, int seedOffset) {
        float wx = warpX.compute(x * warpScale, z * warpScale, seedOffset) * warpStrength;
        float wz = warpZ.compute(x * warpScale, z * warpScale, seedOffset + 10) * warpStrength;

        float warpedX = (x + wx) * scale;
        float warpedZ = (z + wz) * scale;
        float baseSample = clusterNoise.compute(warpedX, warpedZ, seedOffset);

        float dither = (rand.compute(x, z, seedOffset) - 0.5f) * 0.12f;

        float normalized = (baseSample + 1.0f) / 2.0f + dither;
        return Math.max(0.0f, Math.min(1.0f, normalized));
    }

    public record WeightedBlockEntry(BlockState state, int weight) {}

    public static class WeightedBlockSelector {
        private final NavigableMap<Float, BlockState> cumulativeMap = new TreeMap<>();
        private final NavigableMap<Float, BlockState> nonMossCumulativeMap = new TreeMap<>();
        private final float totalWeight;
        private final float nonMossTotalWeight;

        public WeightedBlockSelector(List<WeightedBlockEntry> entries) {
            float sum = 0.0f;
            float nonMossSum = 0.0f;
            for (WeightedBlockEntry entry : entries) {
                if (entry.weight() > 0) {
                    sum += entry.weight();
                    cumulativeMap.put(sum, entry.state());

                    if (!entry.state().is(Blocks.MOSS_BLOCK)) {
                        nonMossSum += entry.weight();
                        nonMossCumulativeMap.put(nonMossSum, entry.state());
                    }
                }
            }
            this.totalWeight = sum;
            this.nonMossTotalWeight = nonMossSum;
        }

        public BlockState sample(float noiseSample, BlockState fallback) {
            if (cumulativeMap.isEmpty()) {
                return fallback;
            }
            float target = noiseSample * totalWeight;
            Map.Entry<Float, BlockState> entry = cumulativeMap.ceilingEntry(target);
            return entry != null ? entry.getValue() : fallback;
        }

        public BlockState sampleNonMoss(float noiseSample, BlockState fallback) {
            if (nonMossCumulativeMap.isEmpty()) {
                return fallback;
            }
            float target = noiseSample * nonMossTotalWeight;
            Map.Entry<Float, BlockState> entry = nonMossCumulativeMap.ceilingEntry(target);
            return entry != null ? entry.getValue() : fallback;
        }
    }

    public record Config(
            int rockVar, int rockMin, int dirtVar, int dirtMin,
            float rockSteepness, float dirtSteepness, float screeSteepness,
            float heightModifier, float slopeModifier
    ) implements FeatureConfiguration {

        public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("rock_var").forGetter(Config::rockVar),
                Codec.INT.fieldOf("rock_min").forGetter(Config::rockMin),
                Codec.INT.fieldOf("dirt_var").forGetter(Config::dirtVar),
                Codec.INT.fieldOf("dirt_min").forGetter(Config::dirtMin),
                Codec.FLOAT.fieldOf("rock_steepness").forGetter(Config::rockSteepness),
                Codec.FLOAT.fieldOf("dirt_steepness").forGetter(Config::dirtSteepness),
                Codec.FLOAT.fieldOf("scree_steepness").forGetter(Config::screeSteepness),
                Codec.FLOAT.fieldOf("height_modifier").forGetter(Config::heightModifier),
                Codec.FLOAT.fieldOf("slope_modifier").forGetter(Config::slopeModifier)
        ).apply(instance, Config::new));
    }
}