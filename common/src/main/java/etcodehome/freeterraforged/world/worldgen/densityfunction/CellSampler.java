package etcodehome.freeterraforged.world.worldgen.densityfunction;

import java.lang.ref.WeakReference;
import java.util.function.Supplier;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.KeyDispatchCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.HashCommon;
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
	private static final ThreadLocal<LookupFrame> LOOKUP_OVERRIDE = new ThreadLocal<>();

	public static Cache2d threadCache2d() {
		return CELL.get();
	}

	public static Cell getCachedOrLookup(WorldLookup lookup, int blockX, int blockZ, boolean sampleClimate) {
		return CELL.get().getAndUpdate(lookup, blockX, blockZ, sampleClimate);
	}

	/**
	 * Installs a worker-thread-local authoritative cell source for a bounded query.
	 *
	 * <p>The override is consulted before the ordinary 2D and filtered-chunk caches. This is important for locate and
	 * preview queries: a previously cached raw cell must not replace the query's filtered terrain state. Overrides may
	 * be nested and must be closed in stack order on the thread that opened them.
	 */
	public static LookupScope pushLookupOverride(WorldLookup owner, LookupOverride lookup) {
		java.util.Objects.requireNonNull(owner, "world lookup");
		java.util.Objects.requireNonNull(lookup, "cell lookup override");
		LookupFrame previous = LOOKUP_OVERRIDE.get();
		LookupFrame frame = new LookupFrame(owner, lookup, previous);
		LOOKUP_OVERRIDE.set(frame);
		return new LookupScope(frame);
	}

	@FunctionalInterface
	public interface LookupOverride {
		@Nullable Cell lookup(int blockX, int blockZ, boolean sampleClimate);
	}

	public static final class LookupScope implements AutoCloseable {
		private final LookupFrame frame;
		private boolean closed;

		private LookupScope(LookupFrame frame) {
			this.frame = frame;
		}

		@Override
		public void close() {
			if (this.closed) {
				return;
			}
			if (LOOKUP_OVERRIDE.get() != this.frame) {
				throw new IllegalStateException("FTF cell lookup overrides must close in stack order");
			}
			this.closed = true;
			if (this.frame.previous == null) {
				LOOKUP_OVERRIDE.remove();
			} else {
				LOOKUP_OVERRIDE.set(this.frame.previous);
			}
		}
	}

	private record LookupFrame(
		WorldLookup owner,
		LookupOverride lookup,
		@Nullable LookupFrame previous
	) {
	}

	/**
	 * Pre-seeds the current worker thread's 2D cell cache with all quart-aligned cells
	 * ({@code [-3..6] x [-3..6]} around the chunk) and the 1-block chunk perimeter
	 * ({@code relX, relZ in [-1, 16]}) from the freshly initialized 40x40 heightmap grid.
	 */
	public static void seedThreadCacheFromGrid(
		WorldLookup lookup,
		int gridBlockX,
		int gridBlockZ,
		int totalSize,
		int border,
		int chunkSize,
		Cell[] grid
	) {
		Cache2d cache = CELL.get();
		cache.ensureOwner(lookup);
		int minBorder = border - 1;
		int maxBorder = border + chunkSize;
		for (int dz = 0; dz < totalSize; dz++) {
			boolean zQuart = (dz & 3) == 0;
			boolean zPerimeter = dz >= minBorder && dz <= maxBorder;
			if (!zQuart && !zPerimeter) {
				continue;
			}
			int bz = gridBlockZ + dz;
			int rowOffset = dz * totalSize;
			boolean zInChunk = dz >= border && dz < border + chunkSize;
			for (int dx = 0; dx < totalSize; dx++) {
				boolean isQuart = zQuart && ((dx & 3) == 0);
				boolean isPerimeter = zPerimeter && (dx == minBorder || dx == maxBorder || dz == minBorder || dz == maxBorder);
				if (!isQuart && !isPerimeter) {
					continue;
				}
				int bx = gridBlockX + dx;
				boolean hasClimate = isQuart || (zInChunk && dx >= border && dx < border + chunkSize);
				cache.putPrecomputed(bx, bz, hasClimate, grid[rowOffset + dx]);
			}
		}
	}

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
		private static final int TABLE_SIZE = 2048;
		private static final int TABLE_MASK = TABLE_SIZE - 1;

		private long lastPos = Long.MAX_VALUE;
		private WeakReference<WorldLookup> lastLookup = new WeakReference<>(null);
		private boolean lastSampleClimate;
		private boolean valid;
		private Cell mruCell = new Cell();

		private final long[] tableKeys = new long[TABLE_SIZE];
		private final boolean[] tableValid = new boolean[TABLE_SIZE];
		private final boolean[] tableHasClimate = new boolean[TABLE_SIZE];
		private final Cell[] tableCells = new Cell[TABLE_SIZE];

		void ensureOwner(WorldLookup lookup) {
			if (this.lastLookup.get() != lookup) {
				this.lastLookup = new WeakReference<>(lookup);
				this.valid = false;
				java.util.Arrays.fill(this.tableValid, false);
			}
		}

		void putPrecomputed(int blockX, int blockZ, boolean hasClimate, Cell source) {
			long packedPos = PosUtil.pack(blockX, blockZ);
			int slot = (int) HashCommon.mix(packedPos) & TABLE_MASK;
			Cell slotCell = this.tableCells[slot];
			if (slotCell == null) {
				slotCell = new Cell();
				this.tableCells[slot] = slotCell;
			}
			slotCell.copyFrom(source);
			this.tableKeys[slot] = packedPos;
			this.tableHasClimate[slot] = hasClimate;
			this.tableValid[slot] = true;
		}
		
		public Cell getAndUpdate(WorldLookup lookup, int blockX, int blockZ, boolean sampleClimate) {
			for (LookupFrame frame = LOOKUP_OVERRIDE.get(); frame != null; frame = frame.previous) {
				if (frame.owner == lookup) {
					Cell overridden = frame.lookup.lookup(blockX, blockZ, sampleClimate);
					if (overridden != null) {
						return overridden;
					}
				}
			}
			long packedPos = PosUtil.pack(blockX, blockZ);
			boolean sameOwner = this.lastLookup.get() == lookup;
			if (!sameOwner) {
				this.lastLookup = new WeakReference<>(lookup);
				this.valid = false;
				java.util.Arrays.fill(this.tableValid, false);
			}
			if (this.valid && this.lastPos == packedPos && (this.lastSampleClimate || !sampleClimate)) {
				if (WorldgenPerfTracker.ACTIVE) {
					WorldgenPerfTracker.stats().cellSamplerCache2dHits++;
				}
				return this.mruCell;
			}

			int slot = (int) HashCommon.mix(packedPos) & TABLE_MASK;
			if (this.tableValid[slot] && this.tableKeys[slot] == packedPos && (this.tableHasClimate[slot] || !sampleClimate)) {
				Cell hit = this.tableCells[slot];
				this.mruCell = hit;
				this.lastPos = packedPos;
				this.lastSampleClimate = this.tableHasClimate[slot];
				this.valid = true;
				if (WorldgenPerfTracker.ACTIVE) {
					WorldgenPerfTracker.stats().cellSamplerCache2dHits++;
				}
				return hit;
			}

			Cell slotCell = this.tableCells[slot];
			if (slotCell == null) {
				slotCell = new Cell();
				this.tableCells[slot] = slotCell;
			}
			this.valid = false;
			this.tableValid[slot] = false;
			etcodehome.freeterraforged.world.worldgen.GeneratorContext genCtx = lookup.getContext();
			Cell cachedCell = null;
			if (genCtx != null && genCtx.filteredChunkCache != null) {
				cachedCell = genCtx.filteredChunkCache.getExistingFilteredCell(blockX, blockZ);
			}
			if (cachedCell != null) {
				slotCell.copyFrom(cachedCell);
			} else if (!WorldgenPerfTracker.ACTIVE) {
				lookup.applyCell(slotCell.reset(), blockX, blockZ, false, sampleClimate);
			} else {
				WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
				ts.cellSamplerCache2dMisses++;
				WorldgenPerfTracker.ApplyCellCaller prev = ts.currentCaller;
				if (prev == WorldgenPerfTracker.ApplyCellCaller.OTHER) {
					ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.CELL_SAMPLER_MISS;
				}
				try {
					lookup.applyCell(slotCell.reset(), blockX, blockZ, false, sampleClimate);
				} finally {
					ts.currentCaller = prev;
				}
			}
			this.tableKeys[slot] = packedPos;
			this.tableHasClimate[slot] = sampleClimate;
			this.tableValid[slot] = true;
			this.mruCell = slotCell;
			this.lastPos = packedPos;
			this.lastSampleClimate = sampleClimate;
			this.valid = true;
			return slotCell;
		}
	}
	
	public class CacheChunk implements MarkerFunction.Mapped {
		@Nullable
		private final Cell[] chunkCells;
		private final int minBlockX;
		private final int minBlockZ;
		private int lastX = Integer.MIN_VALUE;
		private int lastZ = Integer.MIN_VALUE;
		private double lastValue;
		@Nullable
		private final WorldgenPerfTracker.ThreadStats perfStats;
		
		public CacheChunk(@Nullable Cell[] chunkCells, int minBlockX, int minBlockZ, @Nullable Cache2d cache2d) {
			this.chunkCells = chunkCells;
			this.minBlockX = minBlockX;
			this.minBlockZ = minBlockZ;
			this.perfStats = WorldgenPerfTracker.ACTIVE ? WorldgenPerfTracker.stats() : null;
		}

		public CacheChunk(@Nullable Cache2d cache2d) {
			this(null, 0, 0, cache2d);
		}

		public Field field() {
			return CellSampler.this.field;
		}

		@Override
		public double compute(FunctionContext ctx) {
			int bx = ctx.blockX();
			int bz = ctx.blockZ();
			if (bx == this.lastX && bz == this.lastZ) {
				return this.lastValue;
			}

			WorldLookup worldLookup = CellSampler.this.deferredLookup.get();
			Cell cell;
			int relX = bx - this.minBlockX;
			int relZ = bz - this.minBlockZ;
			if (this.chunkCells != null && relX >= -1 && relX <= 16 && relZ >= -1 && relZ <= 16
					&& (this.chunkCells.length >= etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid.HOLDER_ARRAY_LENGTH
						|| (relX >= 0 && relX < 16 && relZ >= 0 && relZ < 16))) {
				cell = etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid.getPaddedCell(this.chunkCells, relX, relZ);
				if (this.perfStats != null) {
					this.perfStats.cellSamplerChunkHits++;
					this.perfStats.fieldReadCounts[CellSampler.this.field.ordinal()]++;
				}
			} else {
				cell = CELL.get().getAndUpdate(worldLookup, bx, bz, false);
				if (this.perfStats != null) {
					this.perfStats.fieldReadCounts[CellSampler.this.field.ordinal()]++;
				}
			}
			double val = CellSampler.this.field.readFinite(cell, worldLookup.getHeightmap());
			this.lastX = bx;
			this.lastZ = bz;
			this.lastValue = val;
			return val;
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
