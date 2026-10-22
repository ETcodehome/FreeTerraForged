package etcodehome.freeterraforged.world.worldgen.densityfunction;

import java.lang.ref.WeakReference;
import java.util.function.Supplier;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.KeyDispatchCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainCategory;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

public record CellSampler(Supplier<WorldLookup> deferredLookup, Field field) implements MarkerFunction.Mapped {
	private static final float DEEP_OCEAN_MAX = Climate.unquantizeCoord(Climate.quantizeCoord(Continentalness.DEEP_OCEAN.max()) - 1);
	private static final float SHALLOW_OCEAN_MIN = Climate.unquantizeCoord(Climate.quantizeCoord(Continentalness.OCEAN.min()) + 1);
	private static final ThreadLocal<Cache2d> CELL = ThreadLocal.withInitial(Cache2d::new);

	@Override
	public double compute(DensityFunction.FunctionContext ctx) {
		WorldLookup worldLookup = this.deferredLookup.get();
		if (worldLookup == null) {
			throw new IllegalStateException("FTF cell sampler used before its world lookup was initialized");
		}
		Cell cell = CELL.get().getAndUpdate(worldLookup, ctx.blockX(), ctx.blockZ(), true);
		if (WorldgenPerfTracker.ACTIVE) {
			WorldgenPerfTracker.stats().fieldReadCounts[this.field.ordinal()]++;
		}
		return this.field.readFinite(cell, worldLookup.getHeightmap());
	}

	@Override
	public double minValue() {
		// Fields exceed unit-noise bounds; finite float bounds avoid infinity * zero in vanilla.
		return -Float.MAX_VALUE;
	}

	@Override
	public double maxValue() {
		return Float.MAX_VALUE;
	}

	public static class Cache2d {
		private long lastPos = Long.MAX_VALUE;
		private WeakReference<WorldLookup> lastLookup = new WeakReference<>(null);
		private boolean lastSampleClimate;
		private boolean valid;
		private Cell cell = new Cell();
		
		public Cell getAndUpdate(WorldLookup lookup, int blockX, int blockZ, boolean sampleClimate) {
			long packedPos = PosUtil.pack(blockX, blockZ);
			boolean sameOwner = this.lastLookup.get() == lookup;
			if(!this.valid || !sameOwner || this.lastPos != packedPos || this.lastSampleClimate != sampleClimate) {
				this.valid = false;
				if (!WorldgenPerfTracker.ACTIVE) {
					lookup.applyCell(this.cell.reset(), blockX, blockZ, false, sampleClimate);
				} else {
					WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
					ts.cellSamplerCache2dMisses++;
					WorldgenPerfTracker.ApplyCellCaller prev = ts.currentCaller;
					ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.CELL_SAMPLER_MISS;
					try {
						lookup.applyCell(this.cell.reset(), blockX, blockZ, false, sampleClimate);
					} finally {
						ts.currentCaller = prev;
					}
				}
				this.lastPos = packedPos;
				if (!sameOwner) {
					this.lastLookup = new WeakReference<>(lookup);
				}
				this.lastSampleClimate = sampleClimate;
				this.valid = true;
			} else if (WorldgenPerfTracker.ACTIVE) {
				WorldgenPerfTracker.stats().cellSamplerCache2dHits++;
			}
			return this.cell;
		}
	}
	
	public class CacheChunk implements MarkerFunction.Mapped {
		@Nullable
		private final Cell[] chunkCells;
		private final int minBlockX;
		private final int minBlockZ;
		private final Cache2d cache2d;
		
		public CacheChunk(@Nullable Cell[] chunkCells, int minBlockX, int minBlockZ, @Nullable Cache2d cache2d) {
			this.chunkCells = chunkCells;
			this.minBlockX = minBlockX;
			this.minBlockZ = minBlockZ;
			this.cache2d = cache2d != null ? cache2d : new Cache2d();
		}

		public CacheChunk(@Nullable Cache2d cache2d) {
			this(null, 0, 0, cache2d);
		}

		@Override
		public double compute(FunctionContext ctx) {
			int bx = ctx.blockX();
			int bz = ctx.blockZ();
			WorldLookup worldLookup = CellSampler.this.deferredLookup.get();
			Cell cell;
			int relX = bx - this.minBlockX;
			int relZ = bz - this.minBlockZ;
			if (this.chunkCells != null && relX >= 0 && relX < 16 && relZ >= 0 && relZ < 16) {
				cell = this.chunkCells[relZ * 16 + relX];
				if (WorldgenPerfTracker.ACTIVE) {
					WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
					ts.cellSamplerChunkHits++;
					ts.fieldReadCounts[CellSampler.this.field.ordinal()]++;
				}
			} else {
				cell = this.cache2d.getAndUpdate(worldLookup, bx, bz, false);
				if (WorldgenPerfTracker.ACTIVE) {
					WorldgenPerfTracker.stats().fieldReadCounts[CellSampler.this.field.ordinal()]++;
				}
			}
			return CellSampler.this.field.readFinite(cell, worldLookup.getHeightmap());
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
				boolean submergedOffshore = cell.height <= levels.water && cell.continentEdge < beach
					&& !cell.terrain.isRiver() && !cell.terrain.isLake() && !cell.terrain.isWetland();

				if(cell.terrain.isDeepOcean() || submergedOffshore && cell.continentEdge <= deepOcean) {
					if(deepOcean <= 0.0F) {
						return Continentalness.DEEP_OCEAN.mid();
					}
					float alpha = NoiseUtil.clamp(cell.continentEdge, 0.0F, deepOcean);
					alpha = NoiseUtil.lerp(alpha, 0.0F, deepOcean, 0.0F, 1.0F);
					return Math.min(DEEP_OCEAN_MAX, NoiseUtil.lerp(Continentalness.DEEP_OCEAN.min() + 0.05F, Continentalness.DEEP_OCEAN.max(), alpha));
				}
				
				if(cell.terrain.isShallowOcean()) {
					if(shallowOcean <= deepOcean) {
						return Continentalness.OCEAN.mid();
					}
					float alpha = NoiseUtil.clamp(cell.continentEdge, deepOcean, shallowOcean);
					alpha = NoiseUtil.lerp(alpha, deepOcean, shallowOcean, 0.0F, 0.98F);
					return Math.max(SHALLOW_OCEAN_MIN, NoiseUtil.lerp(Continentalness.OCEAN.min(), Continentalness.OCEAN.max(), alpha));
				}

				if (submergedOffshore) {
					if (beach <= deepOcean) {
						return Continentalness.OCEAN.mid();
					}
					float alpha = NoiseUtil.lerp(
						NoiseUtil.clamp(cell.continentEdge, deepOcean, beach),
						deepOcean, beach, 0.0F, 0.98F
					);
					return Math.max(SHALLOW_OCEAN_MIN,
						NoiseUtil.lerp(Continentalness.OCEAN.min(), Continentalness.OCEAN.max(), alpha));
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

		public float readFinite(Cell cell, Heightmap heightmap) {
			float value = this.read(cell, heightmap);
			if (!Float.isFinite(value)) {
				throw new IllegalStateException("Non-finite FTF cell density field " + this.name + ": " + value);
			}
			return value;
		}
	}
}
