package etcodehome.freeterraforged.world.worldgen.densityfunction;

import java.util.function.Supplier;

import com.mojang.serialization.MapCodec;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainCategory;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

public record CellSampler(Supplier<WorldLookup> deferredLookup, Field field) implements MarkerFunction.Mapped, FTFCellFunction {
	private static final ThreadLocal<Cache2d> CELL = ThreadLocal.withInitial(Cache2d::new);
	private static final ThreadLocal<Cell> SHARED_FAST_CELL = ThreadLocal.withInitial(Cell::new);

	@Override
	public CellSampler rtf$unwrap() { return this; }

	@Override
	public double compute(DensityFunction.FunctionContext ctx) {
		try {
			WorldLookup lookup = this.deferredLookup.get();
			if (lookup != null) {
				// Grab the reusable cell for this specific worker thread
				Cell cell = SHARED_FAST_CELL.get();

				// Populate it via the zero-allocation fast path
				PointCellCache.fill(lookup, ctx.blockX(), ctx.blockZ(), cell);

				// Read and return the data
				return this.field.read(cell, lookup.getHeightmap());
			}
		} catch (Throwable t) {
			// Intentionally swallowed to fall through to original logic on failure
		}

		// Fallback to original single-slot Cache2d path if the cache fails/is uninitialized
		WorldLookup worldLookup = this.deferredLookup.get();
		Cell cell = CELL.get().getAndUpdate(worldLookup, ctx.blockX(), ctx.blockZ(), true);
		return this.field.read(cell, worldLookup.getHeightmap());
	}

	@Override
	public double minValue() {
		return 0.0F;
	}

	@Override
	public double maxValue() {
		return 1.0F;
	}

	public static class Cache2d {
		private long lastPos = Long.MAX_VALUE;
		private Cell cell = new Cell();
		
		public Cell getAndUpdate(WorldLookup lookup, int blockX, int blockZ, boolean sampleClimate) {
			blockX = QuartPos.toBlock(QuartPos.fromBlock(blockX));
			blockZ = QuartPos.toBlock(QuartPos.fromBlock(blockZ));
			
			long packedPos = PosUtil.pack(blockX, blockZ);
			if(this.lastPos != packedPos) {
				lookup.applyCell(this.cell.reset(), blockX, blockZ, false, sampleClimate);
				this.lastPos = packedPos;
			}
			return this.cell;
		}
	}

	public class CacheChunk implements MarkerFunction.Mapped {
		@Nullable
		private final Tile.Chunk chunk;
		private final Cache2d cache2d;
		private final int chunkX, chunkZ;

		public CacheChunk(@Nullable Tile.Chunk chunk, @Nullable Cache2d cache2d, int chunkX, int chunkZ) {
			this.chunk = chunk;
			this.cache2d = cache2d != null ? cache2d : new Cache2d();
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}

		@Override
		public double compute(FunctionContext ctx) {
			int blockX = ctx.blockX();
			int blockZ = ctx.blockZ();
			int currentChunkX = SectionPos.blockToSectionCoord(blockX);
			int currentChunkZ = SectionPos.blockToSectionCoord(blockZ);

			WorldLookup worldLookup = CellSampler.this.deferredLookup.get();
			Cell cell = (this.chunk != null && this.chunkX == currentChunkX && this.chunkZ == currentChunkZ) ?
					this.chunk.getCell(blockX, blockZ) :
					this.cache2d.getAndUpdate(worldLookup, blockX, blockZ, true);
			return CellSampler.this.field.read(cell, worldLookup.getHeightmap());
		}

		@Override
		public double minValue() {
			return CellSampler.this.minValue();
		}

		@Override
		public double maxValue() {
			return CellSampler.this.maxValue();
		}
	}
	
	public record Marker(Field field) implements MarkerFunction {
		public static final MapCodec<Marker> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Field.CODEC.fieldOf("field").forGetter(Marker::field)
		).apply(instance, Marker::new));
		
		@Override
		public KeyDispatchDataCodec<Marker> codec() {
			return new KeyDispatchDataCodec<>(CODEC);
		}
	}
	
	public enum Field implements StringRepresentable {
		HEIGHT("height") {
			
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.height;
			}
		},
		CONTINENT("continent") {
			
			//TODO move this somewhere else
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				Levels levels = heightmap.levels();
				ControlPoints controlPoints = heightmap.controlPoints();
				
				float deepOcean = controlPoints.deepOcean;
				float shallowOcean = controlPoints.shallowOcean;
				float beach = controlPoints.beach;
				float inland = controlPoints.inland;
				
				if(cell.terrain == TerrainType.MUSHROOM_FIELDS) {
					return Continentalness.MUSHROOM_FIELDS.mid();
				}

				if(cell.terrain.isDeepOcean()) {
					float alpha = NoiseUtil.clamp(cell.continentEdge, 0.0F, deepOcean);
					alpha = NoiseUtil.lerp(alpha, 0.0F, deepOcean, 0.0F, 1.0F);
					return NoiseUtil.lerp(Continentalness.DEEP_OCEAN.min() + 0.05F, Continentalness.DEEP_OCEAN.max(), alpha);					
				}
				
				if(cell.terrain.isShallowOcean()) {
					if(shallowOcean <= deepOcean) {
						return Continentalness.OCEAN.mid();
					}
					float alpha = NoiseUtil.clamp(cell.continentEdge, deepOcean, shallowOcean);
					alpha = NoiseUtil.lerp(alpha, deepOcean, shallowOcean, 0.0F, 0.98F);
					return NoiseUtil.lerp(Continentalness.OCEAN.min(), Continentalness.OCEAN.max(), alpha);
				}
				
				if(cell.terrain.getDelegate() == TerrainCategory.BEACH && cell.height + cell.beachNoise < levels.water(5)) {
					float alpha = NoiseUtil.clamp(cell.continentEdge, shallowOcean, beach);
					alpha = NoiseUtil.lerp(alpha, shallowOcean, beach, 0.0F, 1.0F);
					return NoiseUtil.lerp(Continentalness.COAST.min(), Continentalness.COAST.max(), alpha);
				}

				if(cell.terrain == TerrainType.ISLAND_BEACH) {
					return Continentalness.COAST.mid();
				}

				float alpha = NoiseUtil.clamp(cell.continentEdge, beach, inland);
				alpha = NoiseUtil.lerp(alpha, beach, inland, 0.0F, 1.0F);
				return NoiseUtil.lerp(Continentalness.NEAR_INLAND.mid(), Continentalness.FAR_INLAND.max(), alpha);

			}
		},
		CONTINENT_EDGE("continent_edge") {

			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.continentEdge;
			}
		},
		EROSION("erosion") {
			
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.erosion;
			}
		},
		TERRAIN_EROSION("terrain_erosion") {

			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.terrainErosion;
			}
		},
		WEIRDNESS("weirdness") {
			
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.weirdness;
			}
		},
		BIOME_REGION("biome_region") {
			
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.biomeRegionId;
			}
		},
		TEMPERATURE("temperature") {
			
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.temperature;
			}
		},
		MOISTURE("moisture") {
			
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.moisture;
			}
		},
		GRADIENT("gradient") {
			
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.gradient;
			}
		},
		HEIGHT_EROSION("height_erosion") {
			
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.heightErosion;
			}
		},
		SEDIMENT("sediment") {
			
			@Override
			public float read(Cell cell, Heightmap heightmap) {
				return cell.sediment;
			}
		};

		public static final Codec<Field> CODEC = StringRepresentable.fromEnum(Field::values);
		
		private String name;
		
		private Field(String name) {
			this.name = name;
		}
		
		@Override
		public String getSerializedName() {
			return this.name;
		}
		
		public abstract float read(Cell cell, Heightmap heightmap);
	}
	@Override
	public boolean equals(Object o) {
		return o instanceof CellSampler other && this.field == other.field;
	}
	@Override
	public int hashCode() {
		return field.hashCode();
	}

}
