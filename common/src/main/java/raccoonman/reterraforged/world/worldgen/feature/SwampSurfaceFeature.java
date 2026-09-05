package raccoonman.reterraforged.world.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
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
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import raccoonman.reterraforged.world.worldgen.feature.SwampSurfaceFeature.Config;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;

public class SwampSurfaceFeature extends Feature<Config> {
	private static final Noise MATERIAL_NOISE = makeMaterialNoise();
	
	public SwampSurfaceFeature(Codec<Config> codec) {
		super(codec);
	}

	@Override
	public boolean place(FeaturePlaceContext<Config> ctx) {
		Config config = ctx.config();
		BlockPos origin = ctx.origin();
		ChunkPos chunkPos = new ChunkPos(origin);
		// Operate on the WorldGenRegion, not the raw ChunkAccess
		WorldGenLevel level = ctx.level();
		ChunkGenerator generator = ctx.chunkGenerator();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int waterY = generator.getSeaLevel() - 1;

		for(int x = 0; x < 16; x++) {
			for(int z = 0; z < 16; z++) {
				// Calculate absolute world coordinates
				int worldX = chunkPos.getBlockX(x);
				int worldZ = chunkPos.getBlockZ(z);

				// Level.getHeight uses absolute coordinates safely
				int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);

				@SuppressWarnings({"deprecation", "removal"})
				double noise = Biome.BIOME_INFO_NOISE.getValue(worldX * 0.25D, worldZ * 0.25D, false);
				BlockState filler = getMaterial(worldX, waterY, worldZ, waterY, config);

				// Set our mutable pos to the absolute world coordinate before checking biome
				pos.set(worldX, surfaceY, worldZ);

				if(level.getBiome(pos).is(Biomes.SWAMP)) {
					if (noise > 0.0D) {
						for (int y = surfaceY; y >= surfaceY - 10; --y) {
							pos.set(worldX, y, worldZ); // Absolute positions
							if (level.getBlockState(pos).isAir()) {
								continue;
							}

							if (y == waterY && !level.getFluidState(pos).isEmpty()) {
								// Flag '2' prevents block updates during generation to avoid cascading lag
								level.setBlock(pos, filler, 2);
							}
							break;
						}
					}

					int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, worldX, worldZ);
					if (oceanFloorY <= waterY) {
						pos.set(worldX, oceanFloorY, worldZ);
						level.setBlock(pos, getMaterial(worldX, oceanFloorY, worldZ, waterY, config), 2);
					}
				}
			}
		}
		return true; // Return true if the feature actually modified the world
	}

    private static BlockState getMaterial(int x, int y, int z, int waterY, Config config) {
        float value = MATERIAL_NOISE.compute(x, z, 0);
        if (value > 0.6) {
            if (value < 0.75 && y < waterY) {
                return config.clayMaterial();
            }
            return config.gravelMaterial();
        }
        return config.dirtMaterial();
    }
    
    private static Noise makeMaterialNoise() {
    	Noise base = Noises.simplex(23, 40, 2);
    	return Noises.warpWhite(base, 213, 2, 4);    	
    }
    
    public record Config(BlockState clayMaterial, BlockState gravelMaterial, BlockState dirtMaterial) implements FeatureConfiguration {
    	public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
    		BlockState.CODEC.fieldOf("clay_material").forGetter(Config::clayMaterial),
    		BlockState.CODEC.fieldOf("gravel_material").forGetter(Config::gravelMaterial),
    		BlockState.CODEC.fieldOf("dirt_material").forGetter(Config::dirtMaterial)
    	).apply(instance, Config::new));
    }
}
