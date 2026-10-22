package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import com.ishland.c2me.opts.accel.opencl.common.compiler.GeneratedCLSource;
import com.ishland.c2me.opts.accel.opencl.common.gen.CLDataUtil;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.TileCache;
import it.unimi.dsi.fastutil.objects.Reference2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;

public final class FTFGpuTerrain {
	public static final String ENABLE_PROPERTY = "freeterraforged.gpuTerrain";
	public static final int AREA_CHUNKS = 16;
	public static final int AREA_BORDER_BLOCKS = 8;

	private static final int DIAGNOSTIC_AREAS = 4;
	private static final int BLOB_CACHE_ENTRIES = 8;

	private static final Map<Long, CLDataUtil.ConstantBlob> BLOB_CACHE = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CLDataUtil.ConstantBlob> eldest) {
			return this.size() > BLOB_CACHE_ENTRIES;
		}
	};

	private static volatile GeneratorContext cacheOwner;

	private static final AtomicLong CACHE_HITS = new AtomicLong();
	private static final AtomicLong CACHE_MISSES = new AtomicLong();

	private static final ThreadLocal<Reference2IntLinkedOpenHashMap<Object>> SUBSTITUTED = new ThreadLocal<>();
	private static final AtomicInteger DIAGNOSTICS_LOGGED = new AtomicInteger();
	private static volatile boolean warnedMarkerMissing;
	private static volatile boolean warnedBuildFailure;

	private FTFGpuTerrain() {
	}

	public static boolean isEnabled() {
		try {
			return Boolean.getBoolean(ENABLE_PROPERTY);
		} catch (Throwable t) {
			return false;
		}
	}

	@Nullable
	public static Reference2IntLinkedOpenHashMap<Object> substituted() {
		return SUBSTITUTED.get();
	}

	public static void clear() {
		SUBSTITUTED.remove();
	}

	public static void reset() {
		synchronized(BLOB_CACHE) {
			BLOB_CACHE.clear();
		}
		CACHE_HITS.set(0L);
		CACHE_MISSES.set(0L);
		DIAGNOSTICS_LOGGED.set(0);
	}

	public static void prepare(GeneratedCLSource source, @Nullable GeneratorContext context, int areaChunkX, int areaChunkZ) {
		SUBSTITUTED.remove();

		if(source == null || context == null) {
			return;
		}

		WorldLookup lookup = context.lookup;
		if(lookup == null) {
			return;
		}

		Reference2IntLinkedOpenHashMap<Object> original = source.getGlobalDynamicDataOffsets();
		if(original == null || !original.containsKey(FTFCellData.MARKER)) {
			if(!warnedMarkerMissing) {
				warnedMarkerMissing = true;
				FTFCommon.LOGGER.warn("FreeTerraForged cell data was never allocated during OpenCL codegen, so terrain values cannot be uploaded. The cell emitter did not run for this dimension");
			}
			return;
		}

		int startBlockX = (areaChunkX << 4) - AREA_BORDER_BLOCKS;
		int startBlockZ = (areaChunkZ << 4) - AREA_BORDER_BLOCKS;
		int size = (AREA_CHUNKS << 4) + (AREA_BORDER_BLOCKS << 1);

		try {
			long started = System.nanoTime();
			long areaKey = (((long) areaChunkX) << 32) | (areaChunkZ & 0xFFFFFFFFL);

			CLDataUtil.ConstantBlob cached;
			synchronized(BLOB_CACHE) {
				if(cacheOwner != context) {
					BLOB_CACHE.clear();
					CACHE_HITS.set(0L);
					CACHE_MISSES.set(0L);
					cacheOwner = context;
				}
				cached = BLOB_CACHE.get(areaKey);
			}

			long tilesReady = 0L;
			CLDataUtil.ConstantBlob blob;
			if(cached != null) {
				CACHE_HITS.incrementAndGet();
				blob = cached;
			} else {
				CACHE_MISSES.incrementAndGet();
				tilesReady = primeTiles(context.cache, startBlockX, startBlockZ, size);
				byte[] data = FTFCellData.build(lookup, startBlockX, startBlockZ, size, size, false);
				blob = new CLDataUtil.ConstantBlob(data, FTFCellData.ALIGNMENT);
				synchronized(BLOB_CACHE) {
					BLOB_CACHE.put(areaKey, blob);
				}
			}

			Reference2IntLinkedOpenHashMap<Object> copy = new Reference2IntLinkedOpenHashMap<>(original.size());
			for(Reference2IntMap.Entry<Object> entry : original.reference2IntEntrySet()) {
				Object key = entry.getKey() == FTFCellData.MARKER ? blob : entry.getKey();
				copy.put(key, entry.getIntValue());
			}

			SUBSTITUTED.set(copy);
			logDiagnostics(lookup, startBlockX, startBlockZ, size, blob.data().length, started, tilesReady);
		} catch (Throwable t) {
			if(!warnedBuildFailure) {
				warnedBuildFailure = true;
				FTFCommon.LOGGER.error("Failed to build FreeTerraForged cell data for OpenCL, affected areas fall back to default terrain values", t);
			}
		}
	}

	private static long primeTiles(@Nullable TileCache cache, int startBlockX, int startBlockZ, int size) {
		if(cache == null) {
			return 0L;
		}

		long started = System.nanoTime();
		int minTileX = cache.chunkToTile(startBlockX >> 4);
		int maxTileX = cache.chunkToTile((startBlockX + size - 1) >> 4);
		int minTileZ = cache.chunkToTile(startBlockZ >> 4);
		int maxTileZ = cache.chunkToTile((startBlockZ + size - 1) >> 4);

		for(int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
			for(int tileX = minTileX; tileX <= maxTileX; tileX++) {
				cache.provide(tileX, tileZ);
			}
		}

		return System.nanoTime() - started;
	}

	private static void logDiagnostics(WorldLookup lookup, int startBlockX, int startBlockZ, int size, int bytes, long started, long tileNanos) {
		if(DIAGNOSTICS_LOGGED.getAndIncrement() >= DIAGNOSTIC_AREAS) {
			return;
		}

		Cell probe = new Cell();
		lookup.applyCell(probe.reset(), startBlockX + size / 2, startBlockZ + size / 2, false, true);

		FTFCommon.LOGGER.info(
			"FreeTerraForged OpenCL cell data: origin=({}, {}) size={} bytes={} tilePrime={}ms total={}ms blobCache={}hit/{}miss centreHeight={} centreTerrain={}",
			startBlockX,
			startBlockZ,
			size,
			bytes,
			tileNanos / 1_000_000L,
			(System.nanoTime() - started) / 1_000_000L,
			CACHE_HITS.get(),
			CACHE_MISSES.get(),
			probe.height,
			probe.terrain
		);
	}
}
