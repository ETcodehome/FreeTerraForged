package raccoonman.reterraforged.world.worldgen.surface.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceRules.Context;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.surface.RTFSurfaceSystem;

public record StrataRule(ResourceLocation name, Holder<Noise> selector, List<Strata> strata, int iterations) implements SurfaceRules.RuleSource {
	public static final MapCodec<StrataRule> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		ResourceLocation.CODEC.fieldOf("name").forGetter(StrataRule::name),
		Noise.CODEC.fieldOf("selector").forGetter(StrataRule::selector),
		Strata.CODEC.listOf().fieldOf("strata").forGetter(StrataRule::strata),
		Codec.INT.fieldOf("iterations").forGetter(StrataRule::iterations)
	).apply(instance, StrataRule::new));
	
	public StrataRule {
		strata = ImmutableList.copyOf(strata);
	}
	
	@Override
	public Source apply(Context ctx) {
		if(ctx.system instanceof RTFSurfaceSystem rtfSurfaceSystem && (Object) ctx.randomState instanceof RTFRandomState rtfRandomState) {
			return new Source(ctx, rtfRandomState.seed(this.selector.value()), rtfSurfaceSystem.getOrCreateStrata(this.name, this::generateStrata));
		} else {
			throw new IllegalStateException();
		}
	}

	@Override
	public KeyDispatchDataCodec<StrataRule> codec() {
		return new KeyDispatchDataCodec<>(CODEC);
	}
	
	private List<List<Layer>> generateStrata(RandomSource random) {
        List<List<Layer>> layers = new ArrayList<>();
		for(int i = 0; i < this.iterations; i++) {
			List<Layer> layer = new ArrayList<>();
			for(Strata strata : this.strata) {
				layer.addAll(strata.generateLayers(random));
			}
			layers.add(layer);
		}
        return layers;
	}
	
	public record Strata(TagKey<Block> materials, Holder<Noise> noise, int attempts, int minLayers, int maxLayers, float minDepth, float maxDepth) {
		public static final Codec<Strata> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			TagKey.hashedCodec(Registries.BLOCK).fieldOf("materials").forGetter(Strata::materials),
			Noise.CODEC.fieldOf("noise").forGetter(Strata::noise),
			Codec.INT.fieldOf("attempts").forGetter(Strata::attempts),
			Codec.INT.fieldOf("min_layers").forGetter(Strata::minLayers),
			Codec.INT.fieldOf("max_layers").forGetter(Strata::maxLayers),
			Codec.FLOAT.fieldOf("min_depth").forGetter(Strata::minDepth),
			Codec.FLOAT.fieldOf("max_depth").forGetter(Strata::maxDepth)			
		).apply(instance, Strata::new));
		
		public List<Layer> generateLayers(RandomSource random) {
			int lastIndex = -1;
	        int layers = this.minLayers + NoiseUtil.round(random.nextFloat() * (this.maxLayers - this.minLayers));
	        List<Layer> result = new ArrayList<>();
	        List<Holder<Block>> materials = ImmutableList.copyOf(BuiltInRegistries.BLOCK.getTagOrEmpty(this.materials));
	        
	        int seed = random.nextInt();
	        for (int i = 0; i < layers; i++) {
	            int attempts = this.attempts;
	            int index = random.nextInt(materials.size());
	            while (--attempts >= 0 && index == lastIndex) {
	                index = random.nextInt(materials.size());
	            }
	            if (index != lastIndex) {
	                lastIndex = index;
	                BlockState material = materials.get(index).value().defaultBlockState();
	                float depth = this.minDepth + random.nextFloat() * (this.maxDepth - this.minDepth);
	                result.add(new Layer(material, Noises.shiftSeed(Noises.mul(this.noise.value(), depth), random.nextInt()), seed));
	            }
	        }
	        return result;
		}
	}
	
	// this has to be public so that SurfaceSystemExtension can access it
	// should be private otherwise
	public record Layer(BlockState material, Noise depth, int seed) {
	
		public float computeDepth(float x, float z) {
			return this.depth.compute(x, z, this.seed);
		}
	}
	
	private class Source implements SurfaceRules.SurfaceRule {
		private Context surfaceContext;
		private Noise selector;
		private List<List<Layer>> strata;
		private List<Layer> layers;
		private float[] depthBuffer;
		private long lastUpdateXZ;
		
		public Source(Context surfaceContext, Noise selector, List<List<Layer>> strata) {
			this.surfaceContext = surfaceContext;
			this.selector = selector;
			this.strata = strata;
			this.lastUpdateXZ = Long.MIN_VALUE;
		}

        @Nullable
		@Override
		public BlockState tryApply(int x, int y, int z) {
        	if(this.lastUpdateXZ != this.surfaceContext.lastUpdateXZ) {
        		this.initBuffer(x, z);
        		this.lastUpdateXZ = this.surfaceContext.lastUpdateXZ;
        	}

			if ((Object) this.surfaceContext.randomState instanceof RTFRandomState rtfRandomState) {
				var genCtx = rtfRandomState.generatorContext();

				if (genCtx != null) {
					// 2. Get the Cell for this specific block column
					// We use the cache to provide the tile for the current chunk
					var chunkPos = this.surfaceContext.chunk.getPos();
					var tile = genCtx.cache.provideAtChunk(chunkPos.x, chunkPos.z);
					var reader = tile.getChunkReader(chunkPos.x, chunkPos.z);

					int localX = x & 0xF;
					int localZ = z & 0xF;
					raccoonman.reterraforged.world.worldgen.cell.Cell cell = reader.getCell(localX, localZ);

					// 3. Apply the water logic
					if ((cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland()) && cell.riverWaterLevel > 0) {
						var levels = genCtx.generator.getHeightmap().levels();
						int scaledY = levels.scale(cell.height);

						// Calculate OUR integer water height
						float oceanLevel = levels.water;
						int waterY = levels.scale(ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) + oceanLevel);

						if (waterY < levels.scale(levels.water)) waterY = levels.scale(levels.water);

						if (waterY > scaledY) {
							boolean isTransitionColumn = false;
							boolean multiBlockDrop = false;
							int[] dx = {0, 0, 1, -1};
							int[] dz = {-1, 1, 0, 0};

							for (int i = 0; i < 4; i++) {
								int nx = x + dx[i];
								int nz = z + dz[i];

								// Bridge to neighbor
								var neighborTile = genCtx.cache.provideAtChunk(nx >> 4, nz >> 4);
								var neighborReader = neighborTile.getChunkReader(nx >> 4, nz >> 4);
								var neighborCell = neighborReader.getCell(nx & 0xF, nz & 0xF);

								// Calculate the NEIGHBOR'S integer water height using their specific data
								int nWaterY = levels.scale(ContinentalHydrology.getWeightedWaterHeight(neighborCell.waterTable) + levels.water);

								if (nWaterY < levels.scale(levels.water)) nWaterY = levels.scale(levels.water);

								// Only flag if the neighbor's actual water surface is lower than ours
								if (nWaterY < waterY) {

									if (waterY - nWaterY > 1){
										multiBlockDrop = true;
									}

									isTransitionColumn = true;
									if (multiBlockDrop) {

										// make water fall onto the lower neighbour
										for (int wy = nWaterY + 1; wy <= waterY; wy++) {
											BlockState state = wy == waterY
													? Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 1)
													: Blocks.WATER.defaultBlockState();
											BlockPos neighbour = new BlockPos(nx, wy, nz);
											this.surfaceContext.chunk.setBlockState(neighbour, state, true);
										}
									}
								}
							}

							net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
							BlockState water = Blocks.WATER.defaultBlockState();
							BlockState stone = Blocks.STONE.defaultBlockState();

							for (int wy = scaledY + 1; wy <= waterY; wy++) {

								// top row of blocks should be flowing water
								boolean isTopBlock = wy == waterY;

								// top of waterfalls on top of stone
								if (isTopBlock && isTransitionColumn && multiBlockDrop){
									this.surfaceContext.chunk.setBlockState(pos.set(x, wy, z), water, false);
								}

								// standard single tile step down
								else if (isTopBlock && isTransitionColumn && !multiBlockDrop){
									this.surfaceContext.chunk.setBlockState(pos.set(x, wy, z), water.setValue(BlockStateProperties.LEVEL, 1), false);
								}

								// waterfall "walls"
								// TODO - this needs to be better
								else if (!isTopBlock && multiBlockDrop)
								{
									this.surfaceContext.chunk.setBlockState(pos.set(x, wy, z), stone, false);
								}

								// standard water blocks
								else
								{
									this.surfaceContext.chunk.setBlockState(pos.set(x, wy, z), water, false);
								}
							}
						}
					}
				}
			}

        	Layer last = null;
        	for(int i = 0; i < this.layers.size(); i++) {
        		Layer layer = last = this.layers.get(i);
        		if(y > this.depthBuffer[i]) {
        			return layer.material();
        		}
        	}

        	return last != null ? last.material() : null;
		}
		
		private void initBuffer(int x, int z) {
        	this.layers = this.selectLayers(x, z);
			int layerCount = this.layers.size();
			
	        if (this.depthBuffer == null || this.depthBuffer.length < layerCount) {
	            this.depthBuffer = new float[layerCount];
	        }
	        
            int localX = this.surfaceContext.blockX & 0xF;
            int localZ = this.surfaceContext.blockZ & 0xF;
            int height = this.surfaceContext.chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);

            float sum = 0.0F;
            for(int i = 0; i < layerCount; i++) {
            	Layer layer = this.layers.get(i);
            	float depth = layer.computeDepth(x, z);
            	sum += depth;
            	this.depthBuffer[i] = depth;
            }
            
            int y = height;
            for(int i = 0; i < layerCount; i++) {
            	this.depthBuffer[i] = y -= Math.round((this.depthBuffer[i] / sum) * height);
            }
		}
		
		private List<Layer> selectLayers(int x, int z) {
			float selector = this.selector.compute(x, z, 0);
	        int index = (int) (selector * this.strata.size());
	        index = Math.min(this.strata.size() - 1, index);
	        return this.strata.get(index);
	    }
	}
}