package etcodehome.freeterraforged.world.worldgen.perf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

/**
 * Low-overhead, thread-safe performance profiler for FreeTerraForged world generation.
 * Uses per-thread accumulators to avoid lock/atomic contention on hot worldgen paths.
 */
public final class WorldgenPerfTracker {
	public static volatile boolean ACTIVE = false;

	public enum Stage {
		STRUCTURES("Structures"),
		BIOMES("Biomes (doCreateBiomes)"),
		NOISE("Noise & Terrain (doFill)"),
		SURFACE("Surface & Rivers (buildSurface)"),
		CARVERS("Carvers (applyCarvers)"),
		FEATURES("Features (applyBiomeDecoration)");

		public final String label;

		Stage(String label) {
			this.label = label;
		}
	}

	public enum ApplyCellCaller {
		OTHER,
		CELL_SAMPLER_MISS,
		BIOME_ENVELOPE,
		TERRAIN_ROLE_RIVER,
		AQUIFER,
		SURFACE_RIVER,
		STRUCTURES
	}

	public static final class ThreadStats {
		public final String threadName;
		public final long threadId;

		// Stage timings per thread (nanos & counts)
		public final long[] stageNanos = new long[Stage.values().length];
		public final long[] stageCounts = new long[Stage.values().length];

		// ChunkFilterGrid / RegionFilterGrid
		public long gridGenerateCount;
		public long gridGenerateNanos;
		public long gridInitNanos;
		public long filterCliff1Nanos;
		public long filterErosionNanos; // cell.sediment, cell.heightErosion, eroded cell.height
		public long filterCliff2Nanos;
		public long filterBeachNanos;
		public long filterSteepnessNanos; // cell.gradient
		public long filterCeilingNanos;

		// Heightmap.apply internal breakdown
		public boolean insideGridInit;
		public ApplyCellCaller currentCaller = ApplyCellCaller.OTHER;
		public long heightmapApplyCount;
		public long heightmapApplyNanos;
		public long cellTerrainNanos; // base cell.height (continent + region + terrain)
		public long cellRiverNanos;   // rivermap lookup + river carving
		public long cellClimateNanos; // climate & biome region Voronoi
		public long rivermapGenerateCount;
		public long rivermapGenerateNanos;

		// Surface & Rivers sub-systems
		public long surfaceRiverWaterNanos;
		public long surfaceRulesNanos;
		public long surfaceColumnsNanos;
		public long strataFastPathHits;
		public long strataBaseCalls;
		public long doFillCellsTotal;
		public long doFillCellsSkipped;

		// RawChunkCache & FilteredChunkCache
		public long rawChunkCacheHits;
		public long rawChunkCacheMisses;
		public long filteredChunkCacheHits;
		public long filteredChunkCacheMisses;

		// Standalone applyCell (outside RegionFilterGrid.init)
		public long standaloneApplyCellCount;
		public long standaloneApplyCellNanos;
		public final long[] callerCounts = new long[ApplyCellCaller.values().length];
		public final long[] callerNanos = new long[ApplyCellCaller.values().length];

		// Biome sampling & resolution
		public long applyBiomeRegionCount;
		public long applyBiomeRegionNanos;
		public long resolveBiomeCount;
		public long resolveBiomeNanos;
		public int resolveBiomeDepth;
		public long climateSamplerCount;
		public long climateSamplerNanos;
		public int climateSamplerDepth;
		public long biomeEnvelopeCalls;
		public long biomeEnvelopeHits;
		public long biomeEnvelopeMisses;
		public long biomeEnvelopeNanos;

		// CellSampler reads
		public long cellSamplerChunkHits;
		public long cellSamplerCache2dHits;
		public long cellSamplerCache2dMisses;
		public final long[] fieldReadCounts = new long[CellSampler.Field.values().length];

		ThreadStats() {
			Thread t = Thread.currentThread();
			this.threadName = t.getName();
			this.threadId = t.threadId();
		}

		void reset() {
			Arrays.fill(this.stageNanos, 0L);
			Arrays.fill(this.stageCounts, 0L);
			this.gridGenerateCount = 0L;
			this.gridGenerateNanos = 0L;
			this.gridInitNanos = 0L;
			this.filterCliff1Nanos = 0L;
			this.filterErosionNanos = 0L;
			this.filterCliff2Nanos = 0L;
			this.filterBeachNanos = 0L;
			this.filterSteepnessNanos = 0L;
			this.filterCeilingNanos = 0L;
			this.insideGridInit = false;
			this.currentCaller = ApplyCellCaller.OTHER;
			this.heightmapApplyCount = 0L;
			this.heightmapApplyNanos = 0L;
			this.cellTerrainNanos = 0L;
			this.cellRiverNanos = 0L;
			this.cellClimateNanos = 0L;
			this.rivermapGenerateCount = 0L;
			this.rivermapGenerateNanos = 0L;
			this.surfaceRiverWaterNanos = 0L;
			this.surfaceRulesNanos = 0L;
			this.surfaceColumnsNanos = 0L;
			this.strataFastPathHits = 0L;
			this.strataBaseCalls = 0L;
			this.doFillCellsTotal = 0L;
			this.doFillCellsSkipped = 0L;
			this.rawChunkCacheHits = 0L;
			this.rawChunkCacheMisses = 0L;
			this.filteredChunkCacheHits = 0L;
			this.filteredChunkCacheMisses = 0L;
			this.standaloneApplyCellCount = 0L;
			this.standaloneApplyCellNanos = 0L;
			Arrays.fill(this.callerCounts, 0L);
			Arrays.fill(this.callerNanos, 0L);
			this.applyBiomeRegionCount = 0L;
			this.applyBiomeRegionNanos = 0L;
			this.resolveBiomeCount = 0L;
			this.resolveBiomeNanos = 0L;
			this.resolveBiomeDepth = 0;
			this.climateSamplerCount = 0L;
			this.climateSamplerNanos = 0L;
			this.climateSamplerDepth = 0;
			this.biomeEnvelopeCalls = 0L;
			this.biomeEnvelopeHits = 0L;
			this.biomeEnvelopeMisses = 0L;
			this.biomeEnvelopeNanos = 0L;
			this.cellSamplerChunkHits = 0L;
			this.cellSamplerCache2dHits = 0L;
			this.cellSamplerCache2dMisses = 0L;
			Arrays.fill(this.fieldReadCounts, 0L);
		}

		public long totalStageWorkNanos() {
			long sum = 0L;
			for (long n : this.stageNanos) {
				sum += n;
			}
			return sum;
		}
	}

	public static final class ChunkStageTimings {
		public final AtomicLong[] stageNanos = new AtomicLong[Stage.values().length];
		public volatile boolean reachedFullFeatures = false;

		public ChunkStageTimings() {
			for (int i = 0; i < this.stageNanos.length; i++) {
				this.stageNanos[i] = new AtomicLong(0L);
			}
		}

		public long totalNanos() {
			long sum = 0L;
			for (AtomicLong a : this.stageNanos) {
				sum += a.get();
			}
			return sum;
		}
	}

	private static final CopyOnWriteArrayList<ThreadStats> ALL_STATS = new CopyOnWriteArrayList<>();
	private static final ThreadLocal<ThreadStats> THREAD_STATS = ThreadLocal.withInitial(() -> {
		ThreadStats stats = new ThreadStats();
		ALL_STATS.add(stats);
		return stats;
	});
	private static final ConcurrentHashMap<Long, ChunkStageTimings> CHUNK_TIMINGS = new ConcurrentHashMap<>();

	private static volatile long startWallNanos = 0L;
	private static volatile long stopWallNanos = 0L;
	private static volatile String activeContextLabel = "manual";

	private WorldgenPerfTracker() {
	}

	public static boolean isActive() {
		return ACTIVE;
	}

	public static ThreadStats stats() {
		return THREAD_STATS.get();
	}

	public static ThreadStats threadStats() {
		return THREAD_STATS.get();
	}

	public static synchronized void start() {
		start("manual");
	}

	public static synchronized void start(String contextLabel) {
		ACTIVE = false;
		for (ThreadStats s : ALL_STATS) {
			s.reset();
		}
		CHUNK_TIMINGS.clear();
		activeContextLabel = contextLabel != null ? contextLabel : "manual";
		startWallNanos = System.nanoTime();
		stopWallNanos = 0L;
		ACTIVE = true;
	}

	public static synchronized void stop() {
		if (ACTIVE) {
			stopWallNanos = System.nanoTime();
			ACTIVE = false;
		}
	}

	public static synchronized void reset() {
		ACTIVE = false;
		for (ThreadStats s : ALL_STATS) {
			s.reset();
		}
		CHUNK_TIMINGS.clear();
		startWallNanos = 0L;
		stopWallNanos = 0L;
	}

	public static void recordStage(int chunkX, int chunkZ, Stage stage, long elapsedNanos) {
		recordStage(stage, chunkX, chunkZ, elapsedNanos);
	}

	public static void recordStage(Stage stage, int chunkX, int chunkZ, long elapsedNanos) {
		if (!ACTIVE) {
			return;
		}
		ThreadStats ts = THREAD_STATS.get();
		int idx = stage.ordinal();
		ts.stageNanos[idx] += elapsedNanos;
		ts.stageCounts[idx]++;

		long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
		ChunkStageTimings ct = CHUNK_TIMINGS.computeIfAbsent(key, k -> new ChunkStageTimings());
		ct.stageNanos[idx].addAndGet(elapsedNanos);
		if (stage == Stage.FEATURES) {
			ct.reachedFullFeatures = true;
		}
	}

	public static String buildReport(String contextHeader) {
		return buildReport(contextHeader, 0, 0);
	}

	public static List<String> buildHighlightLines(int targetChunksRequested, int targetChunksCompleted) {
		long endNanos = stopWallNanos != 0L ? stopWallNanos : System.nanoTime();
		long wallNanos = Math.max(1L, endNanos - startWallNanos);
		double wallSec = wallNanos / 1_000_000_000.0;

		List<Long> completedChunkTotals = new ArrayList<>();
		for (ChunkStageTimings ct : CHUNK_TIMINGS.values()) {
			if (ct.reachedFullFeatures) {
				completedChunkTotals.add(ct.totalNanos());
			}
		}
		completedChunkTotals.sort(Long::compareTo);
		int fullChunksCount = completedChunkTotals.size();
		int effectiveChunks = targetChunksCompleted > 0 ? targetChunksCompleted : Math.max(1, fullChunksCount);
		double chunksPerSec = effectiveChunks / wallSec;
		double wallMsPerChunk = (wallNanos / 1_000_000.0) / effectiveChunks;

		long surfaceRulesNanos = 0;
		long totalSurfaceCount = 0;
		long gridGenerateCount = 0;
		long gridGenerateNanos = 0;
		long filterErosionNanos = 0;
		for (ThreadStats s : ALL_STATS) {
			surfaceRulesNanos += s.surfaceRulesNanos;
			totalSurfaceCount += s.stageCounts[Stage.SURFACE.ordinal()];
			gridGenerateCount += s.gridGenerateCount;
			gridGenerateNanos += s.gridGenerateNanos;
			filterErosionNanos += s.filterErosionNanos;
		}

		List<String> highlights = new ArrayList<>();
		highlights.add(String.format(Locale.ROOT,
			"[FTF-Perf] Benchmark Complete: %d FULL chunks in %.2f s (%.2f chunks/s | %.2f ms/chunk wall)",
			effectiveChunks, wallSec, chunksPerSec, wallMsPerChunk));

		if (!completedChunkTotals.isEmpty()) {
			double sumNanos = 0;
			for (long n : completedChunkTotals) sumNanos += n;
			double avgMs = (sumNanos / completedChunkTotals.size()) / 1_000_000.0;
			double p50Ms = percentile(completedChunkTotals, 0.50) / 1_000_000.0;
			double p99Ms = percentile(completedChunkTotals, 0.99) / 1_000_000.0;
			highlights.add(String.format(Locale.ROOT,
				"[FTF-Perf] CPU Cost: avg %.1f ms | p50: %.1f ms | p99: %.1f ms",
				avgMs, p50Ms, p99Ms));
		}

		StringBuilder stageSummary = new StringBuilder("[FTF-Perf] Highlights: ");
		boolean hasStage = false;
		if (totalSurfaceCount > 0) {
			double srMsPerChunk = (surfaceRulesNanos / 1_000_000.0) / totalSurfaceCount;
			stageSummary.append(String.format(Locale.ROOT, "Surface Rules: %.1f ms/ch", srMsPerChunk));
			hasStage = true;
		}
		if (gridGenerateCount > 0) {
			double gridMs = (gridGenerateNanos / 1_000_000.0) / gridGenerateCount;
			double erosionMs = (filterErosionNanos / 1_000_000.0) / gridGenerateCount;
			if (hasStage) stageSummary.append(" | ");
			stageSummary.append(String.format(Locale.ROOT, "Region Grids: %.1f ms (Erosion: %.1f ms)", gridMs, erosionMs));
			hasStage = true;
		}
		if (hasStage) {
			highlights.add(stageSummary.toString());
		}

		return highlights;
	}

	public static String buildReport(String contextHeader, int targetChunksRequested, int targetChunksCompleted) {
		if (contextHeader != null && !contextHeader.isBlank()) {
			activeContextLabel = contextHeader;
		}
		return String.join("\n", buildReportLines(targetChunksRequested, targetChunksCompleted));
	}

	public static List<String> buildReportLines(int targetChunksRequested, int targetChunksCompleted) {
		long endNanos = stopWallNanos != 0L ? stopWallNanos : System.nanoTime();
		long wallNanos = Math.max(1L, endNanos - startWallNanos);
		double wallMs = wallNanos / 1_000_000.0;
		double wallSec = wallNanos / 1_000_000_000.0;

		// Aggregate across threads
		long[] totalStageNanos = new long[Stage.values().length];
		long[] totalStageCounts = new long[Stage.values().length];
		long gridGenerateCount = 0, gridGenerateNanos = 0, gridInitNanos = 0;
		long filterCliff1Nanos = 0, filterErosionNanos = 0, filterCliff2Nanos = 0;
		long filterBeachNanos = 0, filterSteepnessNanos = 0, filterCeilingNanos = 0;
		long heightmapApplyCount = 0, heightmapApplyNanos = 0;
		long cellTerrainNanos = 0, cellRiverNanos = 0, cellClimateNanos = 0;
		long rivermapGenCount = 0, rivermapGenNanos = 0;
		long surfaceRiverWaterNanos = 0, surfaceRulesNanos = 0, surfaceColumnsNanos = 0;
		long strataFastPathHits = 0, strataBaseCalls = 0;
		long doFillCellsTotal = 0, doFillCellsSkipped = 0;
		long rawChunkCacheHits = 0, rawChunkCacheMisses = 0;
		long filteredChunkCacheHits = 0, filteredChunkCacheMisses = 0;
		long standaloneApplyCount = 0, standaloneApplyNanos = 0;
		long[] callerCounts = new long[ApplyCellCaller.values().length];
		long[] callerNanos = new long[ApplyCellCaller.values().length];
		long applyBiomeRegionCount = 0, applyBiomeRegionNanos = 0;
		long resolveBiomeCount = 0, resolveBiomeNanos = 0;
		long climateSamplerCount = 0, climateSamplerNanos = 0;
		long biomeEnvelopeCalls = 0, biomeEnvelopeHits = 0, biomeEnvelopeMisses = 0, biomeEnvelopeNanos = 0;
		long cellSamplerChunkHits = 0, cellSamplerCache2dHits = 0, cellSamplerCache2dMisses = 0;
		long[] fieldReadCounts = new long[CellSampler.Field.values().length];

		List<ThreadStats> activeThreads = new ArrayList<>();
		for (ThreadStats s : ALL_STATS) {
			boolean hadActivity = s.totalStageWorkNanos() > 0 || s.heightmapApplyCount > 0 || s.resolveBiomeCount > 0;
			if (!hadActivity) {
				continue;
			}
			activeThreads.add(s);
			for (int i = 0; i < Stage.values().length; i++) {
				totalStageNanos[i] += s.stageNanos[i];
				totalStageCounts[i] += s.stageCounts[i];
			}
			gridGenerateCount += s.gridGenerateCount;
			gridGenerateNanos += s.gridGenerateNanos;
			gridInitNanos += s.gridInitNanos;
			filterCliff1Nanos += s.filterCliff1Nanos;
			filterErosionNanos += s.filterErosionNanos;
			filterCliff2Nanos += s.filterCliff2Nanos;
			filterBeachNanos += s.filterBeachNanos;
			filterSteepnessNanos += s.filterSteepnessNanos;
			filterCeilingNanos += s.filterCeilingNanos;
			heightmapApplyCount += s.heightmapApplyCount;
			heightmapApplyNanos += s.heightmapApplyNanos;
			cellTerrainNanos += s.cellTerrainNanos;
			cellRiverNanos += s.cellRiverNanos;
			cellClimateNanos += s.cellClimateNanos;
			rivermapGenCount += s.rivermapGenerateCount;
			rivermapGenNanos += s.rivermapGenerateNanos;
			surfaceRiverWaterNanos += s.surfaceRiverWaterNanos;
			surfaceRulesNanos += s.surfaceRulesNanos;
			surfaceColumnsNanos += s.surfaceColumnsNanos;
			strataFastPathHits += s.strataFastPathHits;
			strataBaseCalls += s.strataBaseCalls;
			doFillCellsTotal += s.doFillCellsTotal;
			doFillCellsSkipped += s.doFillCellsSkipped;
			rawChunkCacheHits += s.rawChunkCacheHits;
			rawChunkCacheMisses += s.rawChunkCacheMisses;
			filteredChunkCacheHits += s.filteredChunkCacheHits;
			filteredChunkCacheMisses += s.filteredChunkCacheMisses;
			standaloneApplyCount += s.standaloneApplyCellCount;
			standaloneApplyNanos += s.standaloneApplyCellNanos;
			for (int i = 0; i < ApplyCellCaller.values().length; i++) {
				callerCounts[i] += s.callerCounts[i];
				callerNanos[i] += s.callerNanos[i];
			}
			applyBiomeRegionCount += s.applyBiomeRegionCount;
			applyBiomeRegionNanos += s.applyBiomeRegionNanos;
			resolveBiomeCount += s.resolveBiomeCount;
			resolveBiomeNanos += s.resolveBiomeNanos;
			climateSamplerCount += s.climateSamplerCount;
			climateSamplerNanos += s.climateSamplerNanos;
			biomeEnvelopeCalls += s.biomeEnvelopeCalls;
			biomeEnvelopeHits += s.biomeEnvelopeHits;
			biomeEnvelopeMisses += s.biomeEnvelopeMisses;
			biomeEnvelopeNanos += s.biomeEnvelopeNanos;
			cellSamplerChunkHits += s.cellSamplerChunkHits;
			cellSamplerCache2dHits += s.cellSamplerCache2dHits;
			cellSamplerCache2dMisses += s.cellSamplerCache2dMisses;
			for (int i = 0; i < fieldReadCounts.length; i++) {
				fieldReadCounts[i] += s.fieldReadCounts[i];
			}
		}

		long totalStageWorkNanos = 0L;
		for (long n : totalStageNanos) {
			totalStageWorkNanos += n;
		}
		double totalStageWorkMs = totalStageWorkNanos / 1_000_000.0;
		double effectiveParallelism = totalStageWorkNanos / (double) wallNanos;

		// Compute per-chunk total cost percentiles for chunks that completed FEATURES
		List<Long> completedChunkTotals = new ArrayList<>();
		for (ChunkStageTimings ct : CHUNK_TIMINGS.values()) {
			if (ct.reachedFullFeatures) {
				completedChunkTotals.add(ct.totalNanos());
			}
		}
		completedChunkTotals.sort(Long::compareTo);
		int fullChunksCount = completedChunkTotals.size();
		int effectiveChunks = targetChunksCompleted > 0 ? targetChunksCompleted : Math.max(1, fullChunksCount);
		double chunksPerSec = effectiveChunks / wallSec;
		double wallMsPerChunk = wallMs / effectiveChunks;

		List<String> lines = new ArrayList<>();
		lines.add("================================================================================");
		lines.add(String.format(Locale.ROOT, "[FTF Perf Report] Context: %s", activeContextLabel));
		lines.add("================================================================================");
		lines.add(String.format(Locale.ROOT,
			"1. OVERALL THROUGHPUT & PARALLELISM:"));
		lines.add(String.format(Locale.ROOT,
			"   Wall-Clock Time       : %8.1f ms (%.2f s)", wallMs, wallSec));
		lines.add(String.format(Locale.ROOT,
			"   Target Chunks         : %d completed / %d requested (Total Full Chunks Touched: %d)",
			targetChunksCompleted, targetChunksRequested, fullChunksCount));
		lines.add(String.format(Locale.ROOT,
			"   Throughput            : %8.2f chunks/sec | %.2f ms/chunk (wall)",
			chunksPerSec, wallMsPerChunk));
		lines.add(String.format(Locale.ROOT,
			"   Cumulative Thread Work: %8.1f ms across %d active threads",
			totalStageWorkMs, activeThreads.size()));
		lines.add(String.format(Locale.ROOT,
			"   Effective Parallelism : %8.2fx (Available Processors: %d)",
			effectiveParallelism, Runtime.getRuntime().availableProcessors()));

		if (!completedChunkTotals.isEmpty()) {
			double minMs = completedChunkTotals.getFirst() / 1_000_000.0;
			double p50Ms = percentile(completedChunkTotals, 0.50) / 1_000_000.0;
			double p90Ms = percentile(completedChunkTotals, 0.90) / 1_000_000.0;
			double p95Ms = percentile(completedChunkTotals, 0.95) / 1_000_000.0;
			double p99Ms = percentile(completedChunkTotals, 0.99) / 1_000_000.0;
			double maxMs = completedChunkTotals.getLast() / 1_000_000.0;
			double avgMs = completedChunkTotals.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;
			lines.add(String.format(Locale.ROOT,
				"   True Per-Chunk CPU Cost (sum of all stages per chunk, n=%d):", fullChunksCount));
			lines.add(String.format(Locale.ROOT,
				"     Avg: %.2f ms | Min: %.2f ms | p50: %.2f ms | p90: %.2f ms | p95: %.2f ms | p99: %.2f ms | Max: %.2f ms",
				avgMs, minMs, p50Ms, p90Ms, p95Ms, p99Ms, maxMs));
		}

		lines.add("--------------------------------------------------------------------------------");
		lines.add("2. CHUNK STAGE BREAKDOWN (Worker Thread Execution Time):");
		for (Stage stage : Stage.values()) {
			int i = stage.ordinal();
			long count = totalStageCounts[i];
			double totalMs = totalStageNanos[i] / 1_000_000.0;
			double avgMs = count > 0 ? totalMs / count : 0.0;
			double pct = totalStageWorkNanos > 0 ? (totalStageNanos[i] * 100.0 / totalStageWorkNanos) : 0.0;
			lines.add(String.format(Locale.ROOT,
				"   %-34s : %8.1f ms (%5.1f%%) | %4d chunks | %6.2f ms/chunk",
				stage.label, totalMs, pct, count, avgMs));
			if (stage == Stage.NOISE && totalMs > 0 && doFillCellsTotal > 0) {
				lines.add(String.format(Locale.ROOT,
					"     - Sky Density Cells Skipped            : %d / %d cells (%5.1f%% skipped above terrain)",
					doFillCellsSkipped, doFillCellsTotal,
					pct(doFillCellsSkipped, doFillCellsTotal)));
			}
			if (stage == Stage.SURFACE && totalMs > 0) {
				lines.add(String.format(Locale.ROOT,
					"     - Surface Rules (super.buildSurface)   : %8.1f ms (%5.1f%% of surface)",
					surfaceRulesNanos / 1_000_000.0, pct(surfaceRulesNanos, totalStageNanos[i])));
				if (strataFastPathHits + strataBaseCalls > 0) {
					lines.add(String.format(Locale.ROOT,
					"       * Underground Fast-Path (Rock)   : %d hits, %d base calls (%5.1f%% bypassed)",
						strataFastPathHits, strataBaseCalls,
						pct(strataFastPathHits, strataFastPathHits + strataBaseCalls)));
				}
				lines.add(String.format(Locale.ROOT,
					"     - River & Lake (placeRiverWater)       : %8.1f ms (%5.1f%% of surface)",
					surfaceRiverWaterNanos / 1_000_000.0, pct(surfaceRiverWaterNanos, totalStageNanos[i])));
				lines.add(String.format(Locale.ROOT,
					"     - Surface Columns (ScreeFeature)       : %8.1f ms (%5.1f%% of surface)",
					surfaceColumnsNanos / 1_000_000.0, pct(surfaceColumnsNanos, totalStageNanos[i])));
			}
		}

		lines.add("--------------------------------------------------------------------------------");
		lines.add("3. REGION FILTER GRID (4x4 Chunks, 88x88 = 7,744 Cells) & FILTER PIPELINE:");
		double gridTotalMs = gridGenerateNanos / 1_000_000.0;
		double gridAvgMs = gridGenerateCount > 0 ? gridTotalMs / gridGenerateCount : 0.0;
		lines.add(String.format(Locale.ROOT,
			"   RegionFilterGrid.generateRegion    : %8.1f ms | %4d grids  | %6.2f ms/grid",
			gridTotalMs, gridGenerateCount, gridAvgMs));
		if (gridGenerateCount > 0) {
			lines.add(String.format(Locale.ROOT,
				"     - Grid Init (Raw Chunk Extraction): %8.1f ms (%5.1f%%) | %6.2f ms/grid",
				gridInitNanos / 1_000_000.0, pct(gridInitNanos, gridGenerateNanos),
				(gridInitNanos / 1_000_000.0) / gridGenerateCount));
			lines.add(String.format(Locale.ROOT,
				"       * FilteredChunkCache             : %d hits, %d misses (%5.1f%% hit rate)",
				filteredChunkCacheHits, filteredChunkCacheMisses,
				pct(filteredChunkCacheHits, filteredChunkCacheHits + filteredChunkCacheMisses)));
			lines.add(String.format(Locale.ROOT,
				"       * RawChunkCache                  : %d hits, %d misses (%5.1f%% hit rate)",
				rawChunkCacheHits, rawChunkCacheMisses,
				pct(rawChunkCacheHits, rawChunkCacheHits + rawChunkCacheMisses)));
			lines.add(String.format(Locale.ROOT,
				"     - Filter 1: CliffRelaxation (Pre): %8.1f ms (%5.1f%%) | %6.2f ms/grid",
				filterCliff1Nanos / 1_000_000.0, pct(filterCliff1Nanos, gridGenerateNanos),
				(filterCliff1Nanos / 1_000_000.0) / gridGenerateCount));
			lines.add(String.format(Locale.ROOT,
				"     - Filter 2: Erosion & Sediment   : %8.1f ms (%5.1f%%) | %6.2f ms/grid",
				filterErosionNanos / 1_000_000.0, pct(filterErosionNanos, gridGenerateNanos),
				(filterErosionNanos / 1_000_000.0) / gridGenerateCount));
			lines.add(String.format(Locale.ROOT,
				"     - Filter 3: CliffRelaxation(Post): %8.1f ms (%5.1f%%) | %6.2f ms/grid",
				filterCliff2Nanos / 1_000_000.0, pct(filterCliff2Nanos, gridGenerateNanos),
				(filterCliff2Nanos / 1_000_000.0) / gridGenerateCount));
			lines.add(String.format(Locale.ROOT,
				"     - Filter 4: BeachDetect          : %8.1f ms (%5.1f%%) | %6.2f ms/grid",
				filterBeachNanos / 1_000_000.0, pct(filterBeachNanos, gridGenerateNanos),
				(filterBeachNanos / 1_000_000.0) / gridGenerateCount));
			lines.add(String.format(Locale.ROOT,
				"     - Filter 5: Steepness (Gradient) : %8.1f ms (%5.1f%%) | %6.2f ms/grid",
				filterSteepnessNanos / 1_000_000.0, pct(filterSteepnessNanos, gridGenerateNanos),
				(filterSteepnessNanos / 1_000_000.0) / gridGenerateCount));
		}

		lines.add("--------------------------------------------------------------------------------");
		lines.add("4. CELL HEIGHTMAP & SUB-COMPONENT CALCULATIONS (All Heightmap.apply calls):");
		double hmTotalMs = heightmapApplyNanos / 1_000_000.0;
		double hmUsPerCall = heightmapApplyCount > 0 ? (heightmapApplyNanos / 1_000.0) / heightmapApplyCount : 0.0;
		long gridCellsEvaluated = gridGenerateCount * 7744L;
		lines.add(String.format(Locale.ROOT,
			"   Total Heightmap.apply Calls        : %,10d calls (%8.1f ms | %.2f us/call)",
			heightmapApplyCount, hmTotalMs, hmUsPerCall));
		lines.add(String.format(Locale.ROOT,
			"     - From RegionFilterGrid (484/ch) : %,10d calls", gridCellsEvaluated));
		lines.add(String.format(Locale.ROOT,
			"     - Standalone (Outside Grid!)     : %,10d calls (%8.1f ms)",
			standaloneApplyCount, standaloneApplyNanos / 1_000_000.0));
		lines.add(String.format(Locale.ROOT,
			"   Heightmap Component Breakdown:"));
		lines.add(String.format(Locale.ROOT,
			"     - cell.height (Continent+Terrain): %8.1f ms (%5.1f%% of Heightmap.apply)",
			cellTerrainNanos / 1_000_000.0, pct(cellTerrainNanos, heightmapApplyNanos)));
		lines.add(String.format(Locale.ROOT,
			"     - Rivers (Rivermap+Carve)        : %8.1f ms (%5.1f%% of Heightmap.apply) [Rivermap gen: %d calls, %.1f ms]",
			cellRiverNanos / 1_000_000.0, pct(cellRiverNanos, heightmapApplyNanos),
			rivermapGenCount, rivermapGenNanos / 1_000_000.0));
		lines.add(String.format(Locale.ROOT,
			"     - Climate (Temp+Moist+Region)    : %8.1f ms (%5.1f%% of Heightmap.apply)",
			cellClimateNanos / 1_000_000.0, pct(cellClimateNanos, heightmapApplyNanos)));

		if (standaloneApplyCount > 0) {
			lines.add("   Standalone applyCell Call-Site Breakdown:");
			for (ApplyCellCaller caller : ApplyCellCaller.values()) {
				int idx = caller.ordinal();
				if (callerCounts[idx] > 0) {
					lines.add(String.format(Locale.ROOT,
						"     - %-30s : %,10d calls | %8.1f ms (%5.1f%%)",
						caller.name(), callerCounts[idx],
						callerNanos[idx] / 1_000_000.0, pct(callerNanos[idx], standaloneApplyNanos)));
				}
			}
		}

		lines.add("--------------------------------------------------------------------------------");
		lines.add("5. BIOME SAMPLING & CELL SAMPLER CACHE EFFICIENCY:");
		lines.add(String.format(Locale.ROOT,
			"   resolveBiome / resolveInCell       : %,10d calls | %8.1f ms (%.2f us/call)",
			resolveBiomeCount, resolveBiomeNanos / 1_000_000.0,
			resolveBiomeCount > 0 ? (resolveBiomeNanos / 1_000.0) / resolveBiomeCount : 0.0));
		lines.add(String.format(Locale.ROOT,
			"   WorldLookup.applyBiomeRegion       : %,10d calls | %8.1f ms",
			applyBiomeRegionCount, applyBiomeRegionNanos / 1_000_000.0));
		lines.add(String.format(Locale.ROOT,
			"   Climate.Sampler.sample             : %,10d calls | %8.1f ms",
			climateSamplerCount, climateSamplerNanos / 1_000_000.0));
		lines.add(String.format(Locale.ROOT,
			"   BiomeSurfaceEnvelope (10x10 min Y) : %,10d calls | %8.1f ms (Hits: %,d, Misses: %,d)",
			biomeEnvelopeCalls, biomeEnvelopeNanos / 1_000_000.0, biomeEnvelopeHits, biomeEnvelopeMisses));

		long totalSamplerReads = cellSamplerChunkHits + cellSamplerCache2dHits + cellSamplerCache2dMisses;
		lines.add(String.format(Locale.ROOT,
			"   CellSampler Reads                  : %,10d total", totalSamplerReads));
		if (totalSamplerReads > 0) {
			lines.add(String.format(Locale.ROOT,
				"     - ChunkCells Fast-Path Hits      : %,10d (%5.1f%%)",
				cellSamplerChunkHits, pct(cellSamplerChunkHits, totalSamplerReads)));
			lines.add(String.format(Locale.ROOT,
				"     - Cache2d Hits                   : %,10d (%5.1f%%)",
				cellSamplerCache2dHits, pct(cellSamplerCache2dHits, totalSamplerReads)));
			lines.add(String.format(Locale.ROOT,
				"     - Cache2d Misses (re-evaluates!) : %,10d (%5.1f%%)",
				cellSamplerCache2dMisses, pct(cellSamplerCache2dMisses, totalSamplerReads)));
			StringBuilder fieldSummary = new StringBuilder("     - Field Reads                    : ");
			for (CellSampler.Field f : CellSampler.Field.values()) {
				long c = fieldReadCounts[f.ordinal()];
				if (c > 0) {
					fieldSummary.append(String.format(Locale.ROOT, "%s=%,d ", f.name(), c));
				}
			}
			lines.add(fieldSummary.toString().stripTrailing());
		}

		lines.add("--------------------------------------------------------------------------------");
		lines.add("6. PARALLEL THREAD UTILIZATION (Sorted by Work Time):");
		activeThreads.sort(Comparator.comparingLong(ThreadStats::totalStageWorkNanos).reversed());
		int shown = 0;
		for (ThreadStats ts : activeThreads) {
			long workNanos = ts.totalStageWorkNanos();
			double workMs = workNanos / 1_000_000.0;
			double busyPct = (workNanos * 100.0) / wallNanos;
			lines.add(String.format(Locale.ROOT,
				"   %-26s : %8.1f ms work (%5.1f%% wall utilization) | grids=%d, hmCalls=%,d, biomes=%,d",
				ts.threadName, workMs, busyPct, ts.gridGenerateCount, ts.heightmapApplyCount, ts.resolveBiomeCount));
			if (++shown >= 16 && activeThreads.size() > 16) {
				lines.add(String.format(Locale.ROOT, "   ... and %d more threads", activeThreads.size() - shown));
				break;
			}
		}
		lines.add("================================================================================");
		return lines;
	}

	public static void logAndSaveReport(List<String> reportLines, Path outputFile) {
		for (String line : reportLines) {
			FTFCommon.LOGGER.info(line);
		}
		if (outputFile != null) {
			try {
				if (outputFile.getParent() != null) {
					Files.createDirectories(outputFile.getParent());
				}
				Files.write(outputFile, reportLines, StandardCharsets.UTF_8);
				FTFCommon.LOGGER.info("[FTF Perf] Saved performance report to {}", outputFile.toAbsolutePath());
			} catch (IOException e) {
				FTFCommon.LOGGER.warn("[FTF Perf] Failed to write performance report file", e);
			}
		}
	}

	private static double pct(long num, long den) {
		return den > 0 ? (num * 100.0) / den : 0.0;
	}

	private static long percentile(List<Long> sorted, double p) {
		if (sorted.isEmpty()) {
			return 0L;
		}
		int idx = (int) Math.clamp(Math.ceil(p * sorted.size()) - 1, 0, sorted.size() - 1);
		return sorted.get(idx);
	}
}
