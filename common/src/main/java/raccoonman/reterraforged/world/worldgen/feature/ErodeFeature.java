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

            int worldSeed = heightmap.climate().randomSeed();
            Noise rand = Noises.white(worldSeed, 1);
            // Coherent Perlin Noise with low octaves (2) to handle smooth structural block clustering
            Noise clusterNoise = Noises.perlin((worldSeed + 7777), 2, 1);

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
                        erodeColumn(config, rand, clusterNoise, generator, chunk, cell, pos, surfaceY);
                        //remove any foliage that may have generated above
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

    @Deprecated(forRemoval = true)
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

    private static void erodeColumn(Config config, Noise rand, Noise clusterNoise, ChunkGenerator generator, ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY) {
        if (cell.terrain.isRiver() || cell.terrain.isWetland()) {
            return;
        }

        if (cell.terrain == TerrainType.VOLCANO_PIPE) {
            return;
        }

        BlockState top = chunk.getBlockState(pos);
        if(top.is(RTFBlockTags.ERODIBLE)) {
            BlockState material = getMaterial(config, rand, clusterNoise, cell, pos, top, generator instanceof NoiseBasedChunkGenerator noiseChunkGenerator ? noiseChunkGenerator.generatorSettings().value().defaultBlock() : Blocks.STONE.defaultBlockState());
            if (material != top) {
                if (material.is(RTFBlockTags.ROCK)) {
                    erodeRock(chunk, cell, pos, surfaceY);
                    return;
                } else {
                    ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - 4, material);
                }
            }
            placeScree(config, rand, clusterNoise, chunk, cell, pos, surfaceY);
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

    private static void placeScree(Config config, Noise rand, Noise clusterNoise, ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY) {
        int x = pos.getX();
        int z = pos.getZ();
        float steepness = cell.gradient + rand.compute(x, z, 1) * config.slopeModifier();
        if (steepness < config.screeSteepness()) {
            return;
        }

        float sediment = cell.sediment * config.sedimentNoise();
        float noise = rand.compute(x, z, 2) * config.sedimentNoise();
        if (sediment + noise > config.screeValue()) {
            // Apply a low-frequency scale to coordinates to cluster materials together smoothly
            float sample = clusterNoise.compute(x * config.screeClusterScale(), z * config.screeClusterScale(), 3);
            float materialNoise = Math.max(0.0f, Math.min(1.0f, (sample + 1.0f) / 2.0f));

            BlockState chosenScree = config.screeMaterials().sample(materialNoise, Blocks.GRAVEL.defaultBlockState());
            ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - 2, chosenScree);
        }
    }

    private static BlockState getMaterial(Config config, Noise rand, Noise clusterNoise, Cell cell, BlockPos.MutableBlockPos pos, BlockState top, BlockState middle) {
        int x = pos.getX();
        int z = pos.getZ();
        float height = cell.height + rand.compute(x, z, 0) * config.heightModifier();
        float steepness = cell.gradient + rand.compute(x, z, 1) * config.slopeModifier();

        if (steepness > config.rockSteepness() || height > ColumnDecorator.sampleNoise(x, z, config.rockVar(), config.rockMin())) {
            return rock(middle);
        }

        if (steepness > config.screeSteepness() || height > ColumnDecorator.sampleNoise(x, z, config.rockVar(), config.rockMin())) {
            float sample = clusterNoise.compute(x * config.screeClusterScale(), z * config.screeClusterScale(), 4);
            float materialNoise = Math.max(0.0f, Math.min(1.0f, (sample + 1.0f) / 2.0f));
            return config.screeMaterials().sample(materialNoise, Blocks.COARSE_DIRT.defaultBlockState());
        }

        if (steepness > config.dirtSteepness() && height > ColumnDecorator.sampleNoise(x, z, config.dirtVar(), config.dirtMin())) {
            return ground(config, clusterNoise, pos, top);
        }

        return top;
    }

    private static BlockState rock(BlockState state) {
        if (state.is(RTFBlockTags.ROCK)) {
            return state;
        }
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState ground(Config config, Noise clusterNoise, BlockPos.MutableBlockPos pos, BlockState state) {
        int x = pos.getX();
        int z = pos.getZ();

        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM)) {
            // Cluster the surface apron materials smoothly using low-frequency noise
            float sample = clusterNoise.compute(x * config.dirtClusterScale(), z * config.dirtClusterScale(), 4);
            float materialNoise = Math.max(0.0f, Math.min(1.0f, (sample + 1.0f) / 2.0f));

            return config.dirtMaterials().sample(materialNoise, Blocks.COARSE_DIRT.defaultBlockState());
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

        float sample = clusterNoise.compute(x * config.dirtClusterScale(), z * config.dirtClusterScale(), 4);
        float materialNoise = Math.max(0.0f, Math.min(1.0f, (sample + 1.0f) / 2.0f));
        return config.dirtMaterials().sample(materialNoise, Blocks.COARSE_DIRT.defaultBlockState());
    }

    // --- Weighted Selector Framework ---

    public record WeightedBlockEntry(BlockState state, int weight) {
        public static final Codec<WeightedBlockEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockState.CODEC.fieldOf("state").forGetter(WeightedBlockEntry::state),
                Codec.INT.fieldOf("weight").forGetter(WeightedBlockEntry::weight)
        ).apply(instance, WeightedBlockEntry::new));
    }

    public static class WeightedBlockSelector {
        private final NavigableMap<Float, BlockState> cumulativeMap = new TreeMap<>();
        private final float totalWeight;

        public WeightedBlockSelector(List<WeightedBlockEntry> entries) {
            float sum = 0.0f;
            for (WeightedBlockEntry entry : entries) {
                if (entry.weight() > 0) {
                    sum += entry.weight();
                    cumulativeMap.put(sum, entry.state());
                }
            }
            this.totalWeight = sum;
        }

        public BlockState sample(float noiseSample, BlockState fallback) {
            if (cumulativeMap.isEmpty()) {
                return fallback;
            }
            float target = noiseSample * totalWeight;
            Map.Entry<Float, BlockState> entry = cumulativeMap.ceilingEntry(target);
            return entry != null ? entry.getValue() : fallback;
        }

        public static final Codec<WeightedBlockSelector> CODEC = WeightedBlockEntry.CODEC.listOf().xmap(
                WeightedBlockSelector::new,
                selector -> selector.cumulativeMap.entrySet().stream()
                        .map(e -> new WeightedBlockEntry(e.getValue(), 1))
                        .toList()
        );
    }

    public record Config(
            int rockVar, int rockMin, int dirtVar, int dirtMin,
            float rockSteepness, float dirtSteepness, float screeSteepness,
            float heightModifier, float slopeModifier, float sedimentModifier,
            float sedimentNoise, float screeValue,
            float screeClusterScale, float dirtClusterScale,
            WeightedBlockSelector screeMaterials,
            WeightedBlockSelector dirtMaterials
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
                Codec.FLOAT.fieldOf("slope_modifier").forGetter(Config::slopeModifier),
                Codec.FLOAT.fieldOf("sediment_modifier").forGetter(Config::sedimentModifier),
                Codec.FLOAT.fieldOf("sediment_noise").forGetter(Config::sedimentNoise),
                Codec.FLOAT.fieldOf("screeValue").forGetter(Config::screeValue),
                Codec.FLOAT.fieldOf("scree_cluster_scale").forGetter(Config::screeClusterScale),
                Codec.FLOAT.fieldOf("dirt_cluster_scale").forGetter(Config::dirtClusterScale),
                WeightedBlockSelector.CODEC.fieldOf("scree_materials").forGetter(Config::screeMaterials),
                WeightedBlockSelector.CODEC.fieldOf("dirt_materials").forGetter(Config::dirtMaterials)
        ).apply(instance, Config::new));
    }
}