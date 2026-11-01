package etcodehome.freeterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import com.mojang.datafixers.util.Pair;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ClimateSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;

public final class UndergroundBiomeBanding {
	public static final int DEFAULT_BIOME_SIZE = 225;

	private static final float VANILLA_UNDERGROUND_DEPTH_START = 0.2F;
	private static final float VANILLA_UNDERGROUND_DEPTH_END = 0.9F;
	private static final float VANILLA_BOTTOM_DEPTH = 1.1F;
	private static final float DEPTH_UNITS_PER_BLOCK = 1.0F / 128.0F;
	private static final float SURFACE_DEPTH = NoiseRouterData.GLOBAL_OFFSET + 0.5F;
	// Kept below 0.464 so a point in the current lattice cell is provably nearer than every
	// point two cells away; the 3x3x3 neighborhood is therefore complete, not an approximation.
	private static final float CELL_JITTER = 0.4F;
	// A value of 2 preserves the calibrated default sharpness of 6 at 75% influence while the
	// influence / (1 - influence) curve converges continuously on nearest-only selection at 100%.
	private static final double CLIMATE_INFLUENCE_SCALE = 2.0D;
	private static final long OCCUPANCY_CELL_SALT = 0x3CB2F11B1A5D39E7L;
	private static final long OCCUPANCY_SCORE_SALT = 0x75A28C4D91E637BFL;
	private static final long IDENTITY_CELL_SALT = 0x61F0CE12D47A958BL;
	private static final long IDENTITY_SCORE_SALT = 0x2AD4B83598FC1E67L;

	private static final Climate.Parameter VANILLA_UNDERGROUND_DEPTH = Climate.Parameter.span(
		VANILLA_UNDERGROUND_DEPTH_START,
		VANILLA_UNDERGROUND_DEPTH_END
	);
	private static final Climate.Parameter VANILLA_BOTTOM = Climate.Parameter.point(VANILLA_BOTTOM_DEPTH);

	private UndergroundBiomeBanding() {
	}

	public static <T> Layout<T> apply(Preset preset, List<Pair<Climate.ParameterPoint, T>> entries) {
		return apply(preset, entries, entries, 0L, (point, value) -> classify(point, false), null);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> entries,
		long seed
	) {
		return apply(preset, entries, entries, seed, (point, value) -> classify(point, false), null);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		Climate.ParameterList<T> source,
		long seed
	) {
		return apply(
			preset, source, source.values(), seed,
			(point, value) -> classify(point, false),
			null
		);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		Climate.ParameterList<T> source,
		long seed,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier
	) {
		return apply(preset, source, source.values(), seed, classifier, null);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		Climate.ParameterList<T> source,
		long seed,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier,
		Predicate<T> isCave
	) {
		return apply(preset, source, source.values(), seed, classifier, isCave);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> entries,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier
	) {
		return apply(preset, entries, entries, 0L, classifier, null);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> entries,
		long seed,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier
	) {
		return apply(preset, entries, entries, seed, classifier, null);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> entries,
		long seed,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier,
		Predicate<T> isCave
	) {
		return apply(preset, entries, entries, seed, classifier, isCave);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> sourceEntries,
		List<Pair<Climate.ParameterPoint, T>> candidateEntries,
		long seed,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier
	) {
		return apply(
			preset,
			new Climate.ParameterList<>(sourceEntries),
			candidateEntries,
			seed,
			classifier,
			null
		);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> sourceEntries,
		List<Pair<Climate.ParameterPoint, T>> candidateEntries,
		long seed,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier,
		Predicate<T> isCave
	) {
		return apply(
			preset,
			new Climate.ParameterList<>(sourceEntries),
			candidateEntries,
			seed,
			classifier,
			isCave
		);
	}

	private static <T> Layout<T> apply(
		Preset preset,
		Climate.ParameterList<T> original,
		List<Pair<Climate.ParameterPoint, T>> candidateEntries,
		long seed,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier,
		Predicate<T> isCave
	) {
		Predicate<T> effectiveIsCave = isCave != null ? isCave : UndergroundBiomeTags::isCave;
		List<Pair<Climate.ParameterPoint, T>> sourceEntries = original.values();
		Map<T, Candidate<T>> candidates = new LinkedHashMap<>();
		Set<T> surfaceCandidates = new LinkedHashSet<>();
		int unknownEntryCount = 0;
		int classificationFailureCount = 0;
		for (Pair<Climate.ParameterPoint, T> entry : candidateEntries) {
			if (entry == null || entry.getFirst() == null || entry.getSecond() == null) {
				unknownEntryCount++;
				continue;
			}
			CandidateRole role;
			try {
				role = classifier.apply(entry.getFirst(), entry.getSecond());
			} catch (RuntimeException exception) {
				classificationFailureCount++;
				continue;
			}
			if (role == CandidateRole.SURFACE || entry.getFirst().depth().max() <= Climate.quantizeCoord(0.0F)) {
				surfaceCandidates.add(entry.getSecond());
			}
			if (role != CandidateRole.SHALLOW_CAVE && role != CandidateRole.DEEP_CAVE) {
				if (role == CandidateRole.UNKNOWN) {
					unknownEntryCount++;
				}
				continue;
			}
			Candidate<T> candidate = candidates.computeIfAbsent(entry.getSecond(), Candidate::new);
			if (role == CandidateRole.SHALLOW_CAVE) {
				candidate.addShallow(entry.getFirst());
			} else {
				candidate.addBottom(entry.getFirst());
			}
		}

		candidates.keySet().removeIf(candidate -> surfaceCandidates.contains(candidate) && !effectiveIsCave.test(candidate));

		List<Pair<Climate.ParameterPoint, T>> backgroundEntries = new ArrayList<>();
		for (Pair<Climate.ParameterPoint, T> entry : sourceEntries) {
			if (entry == null || entry.getFirst() == null || entry.getSecond() == null) {
				continue;
			}
			if (!candidates.containsKey(entry.getSecond())) {
				backgroundEntries.add(entry);
			}
		}

		if (candidates.isEmpty() || backgroundEntries.isEmpty()) {
			return Layout.unmodified(original, candidates, unknownEntryCount, classificationFailureCount);
		}

		List<StageCandidate<T>> shallowCandidates = candidates.values().stream()
			.filter(Candidate::hasShallow)
			.map(candidate -> candidate.forStage(false))
			.toList();
		List<StageCandidate<T>> deepCandidates = candidates.values().stream()
			.map(candidate -> candidate.forStage(true))
			.toList();
		long bottomCandidateCount = candidates.values().stream().filter(Candidate::hasBottom).count();
		float endDepth = endDepth(preset);
		Stage<T> shallowStage = !shallowCandidates.isEmpty() && endDepth > SURFACE_DEPTH
			? new Stage<>(shallowCandidates)
			: null;
		Stage<T> deepStage = !deepCandidates.isEmpty() && endDepth > VANILLA_BOTTOM_DEPTH
			? new Stage<>(deepCandidates)
			: null;
		if (shallowStage == null && deepStage == null) {
			return Layout.unmodified(original, candidates, unknownEntryCount, classificationFailureCount);
		}

		float selectionStart = shallowStage == null ? VANILLA_BOTTOM_DEPTH : SURFACE_DEPTH;
		float fullDensityStart = selectionStart + (
			UndergroundBiomeSurfaceProtection.HARD_SHELL_BLOCKS
				+ UndergroundBiomeSurfaceProtection.TRANSITION_BLOCKS
		) * DEPTH_UNITS_PER_BLOCK;
		ClimateSettings.BiomeShape settings = preset.climate().biomeShape;
		int horizontalSize = settings.undergroundBiomeSize();
		int verticalSize = settings.undergroundBiomeVerticalSize(
			preset.world().properties.worldHeight,
			preset.world().properties.worldDepth
		);
		float coverage = settings.undergroundBiomeCoverage();
		float climateInfluence = settings.undergroundBiomeClimateInfluence();

		FTFCommon.LOGGER.info(
			"Applied underground biome regions: {} shallow candidates, {} total deep-stage candidates ({} bottom-role), coverage {}, horizontal size {}, vertical size {}, climate influence {}, vertical banding {}, surface shell {} blocks, transition {} blocks, {} source parameter points",
			shallowCandidates.size(),
			deepCandidates.size(),
			bottomCandidateCount,
			coverage,
			horizontalSize,
			verticalSize,
			climateInfluence,
			settings.undergroundBiomeBanding,
			UndergroundBiomeSurfaceProtection.HARD_SHELL_BLOCKS,
			Math.max(
				0.0F,
				(fullDensityStart - selectionStart) / DEPTH_UNITS_PER_BLOCK
					- UndergroundBiomeSurfaceProtection.HARD_SHELL_BLOCKS
			),
			sourceEntries.size()
		);
		return new Layout<>(
			original,
			new Climate.ParameterList<>(backgroundEntries),
			shallowStage,
			deepStage,
			Climate.quantizeCoord(selectionStart),
			Climate.quantizeCoord(fullDensityStart),
			Climate.quantizeCoord(VANILLA_BOTTOM_DEPTH),
			Climate.quantizeCoord(endDepth),
			coverage,
			climateInfluence,
			horizontalSize,
			verticalSize,
			settings.undergroundBiomeBanding,
			seed,
			shallowCandidates.size(),
			deepCandidates.size(),
			unknownEntryCount,
			classificationFailureCount,
			shallowCandidates.stream().map(StageCandidate::value).toList(),
			deepCandidates.stream().map(StageCandidate::value).toList()
		);
	}

	public static CandidateRole classify(Climate.ParameterPoint point, boolean caveTagged) {
		if (!isWellFormed(point)) {
			return CandidateRole.UNKNOWN;
		}
		Climate.Parameter depth = point.depth();
		if (depth.equals(VANILLA_UNDERGROUND_DEPTH)) {
			return CandidateRole.SHALLOW_CAVE;
		}
		if (depth.equals(VANILLA_BOTTOM)) {
			return CandidateRole.DEEP_CAVE;
		}

		long surface = Climate.quantizeCoord(0.0F);
		if (depth.max() <= surface) {
			return CandidateRole.SURFACE;
		}
		if (depth.min() > surface || (caveTagged && depth.min() == surface && depth.max() > surface)) {
			return depth.min() >= Climate.quantizeCoord(VANILLA_UNDERGROUND_DEPTH_END)
				? CandidateRole.DEEP_CAVE : CandidateRole.SHALLOW_CAVE;
		}
		return CandidateRole.UNKNOWN;
	}

	/** Classifies a sampled runtime climate point using the same shallow/deep boundary as band selection. */
	public static CandidateRole classifyTarget(Climate.TargetPoint target) {
		if (target == null) {
			return CandidateRole.UNKNOWN;
		}
		long depth = target.depth();
		if (depth < Climate.quantizeCoord(SURFACE_DEPTH)) {
			return CandidateRole.SURFACE;
		}
		return depth < Climate.quantizeCoord(VANILLA_BOTTOM_DEPTH)
			? CandidateRole.SHALLOW_CAVE
			: CandidateRole.DEEP_CAVE;
	}

	private static boolean isWellFormed(Climate.ParameterPoint point) {
		return point != null
			&& isWellFormed(point.temperature())
			&& isWellFormed(point.humidity())
			&& isWellFormed(point.continentalness())
			&& isWellFormed(point.erosion())
			&& isWellFormed(point.depth())
			&& isWellFormed(point.weirdness());
	}

	private static boolean isWellFormed(Climate.Parameter parameter) {
		return parameter != null && parameter.min() <= parameter.max();
	}

	static float endDepth(Preset preset) {
		WorldSettings.Properties properties = preset.world().properties;
		return Math.max(
			SURFACE_DEPTH + DEPTH_UNITS_PER_BLOCK,
			(properties.worldDepth + properties.worldHeight) * DEPTH_UNITS_PER_BLOCK
		);
	}

	private static long horizontalClimateDistanceSq(Climate.ParameterPoint point, Climate.TargetPoint target) {
		long dt = point.temperature().distance(target.temperature());
		long dh = point.humidity().distance(target.humidity());
		long dc = point.continentalness().distance(target.continentalness());
		long de = point.erosion().distance(target.erosion());
		long dw = point.weirdness().distance(target.weirdness());
		long dOff = Math.abs(point.offset());
		return dt * dt + dh * dh + dc * dc + de * de + dw * dw + dOff * dOff;
	}

	private static long cellHash(long seed, int x, int y, int z, long salt) {
		long hash = mix64(seed ^ salt ^ 0x9E3779B97F4A7C15L);
		hash = mix64(hash ^ Integer.toUnsignedLong(x) * 0xD6E8FEB86659FD93L);
		hash = mix64(hash ^ Integer.toUnsignedLong(y) * 0xA5A3564E27F8866DL);
		return mix64(hash ^ Integer.toUnsignedLong(z) * 0x9E6C63D0676A9A99L);
	}

	private static long mix64(long value) {
		value ^= value >>> 30;
		value *= 0xBF58476D1CE4E5B9L;
		value ^= value >>> 27;
		value *= 0x94D049BB133111EBL;
		return value ^ value >>> 31;
	}

	private static double unit(long value) {
		return (mix64(value) >>> 11) * 0x1.0p-53;
	}

	private static final class RegionCache {
		private static final int CAPACITY = 8192;
		private static final int MASK = CAPACITY - 1;

		private final long[] keys = new long[CAPACITY];
		private final long[] nearestKeys = new long[CAPACITY];
		private final float[] occupancies = new float[CAPACITY];
		private final int[] cellXs = new int[CAPACITY];
		private final int[] nearestCellYs = new int[CAPACITY];
		private final int[] cellZs = new int[CAPACITY];
		private final int[] identityQuartXs = new int[CAPACITY];
		private final int[] identityQuartYs = new int[CAPACITY];
		private final int[] identityQuartZs = new int[CAPACITY];

		int getOrCompute(
			long key,
			long seed,
			int quartX,
			int quartY,
			int quartZ,
			int horizontalSize,
			int verticalSize
		) {
			int slot = (int) HashCommon.mix(key) & MASK;
			if (this.keys[slot] == key) {
				return slot;
			}
			this.computeInto(slot, key, seed, quartX, quartY, quartZ, horizontalSize, verticalSize);
			return slot;
		}

		private void computeInto(
			int slot,
			long key,
			long seed,
			int quartX,
			int quartY,
			int quartZ,
			int horizontalSize,
			int verticalSize
		) {
			int blockX = QuartPos.toBlock(quartX);
			int blockY = QuartPos.toBlock(quartY);
			int blockZ = QuartPos.toBlock(quartZ);
			double x = (double) blockX / horizontalSize;
			double y = (double) blockY / verticalSize;
			double z = (double) blockZ / horizontalSize;
			int cellX = (int) Math.floor(x);
			int cellY = (int) Math.floor(y);
			int cellZ = (int) Math.floor(z);
			double nearestDistance = Double.POSITIVE_INFINITY;
			long nearestKey = 0L;
			int nearestCellX = cellX;
			int nearestCellY = cellY;
			int nearestCellZ = cellZ;
			double nearestSiteX = 0.0D;
			double nearestSiteY = 0.0D;
			double nearestSiteZ = 0.0D;

			for (int offsetY = -1; offsetY <= 1; offsetY++) {
				for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
					for (int offsetX = -1; offsetX <= 1; offsetX++) {
						int candidateX = cellX + offsetX;
						int candidateY = cellY + offsetY;
						int candidateZ = cellZ + offsetZ;
						long k = cellHash(seed, candidateX, candidateY, candidateZ, OCCUPANCY_CELL_SALT);
					double siteX = candidateX + 0.5D + (unit(k ^ 0x632BE59BD9B4E019L) - 0.5D) * CELL_JITTER;
					double siteY = candidateY + 0.5D + (unit(k ^ 0xC6BC279692B5C323L) - 0.5D) * CELL_JITTER;
					double siteZ = candidateZ + 0.5D + (unit(k ^ 0xD1B54A32D192ED03L) - 0.5D) * CELL_JITTER;
					double dx = x - siteX;
					double dy = y - siteY;
					double dz = z - siteZ;
					double distance = dx * dx + dy * dy + dz * dz;
					if (distance < nearestDistance || (distance == nearestDistance && k < nearestKey)) {
						nearestDistance = distance;
						nearestKey = k;
						nearestCellX = candidateX;
						nearestCellY = candidateY;
						nearestCellZ = candidateZ;
						nearestSiteX = siteX;
						nearestSiteY = siteY;
						nearestSiteZ = siteZ;
					}
					}
				}
			}
			this.keys[slot] = key;
			this.nearestKeys[slot] = nearestKey;
			this.occupancies[slot] = (float) unit(nearestKey ^ OCCUPANCY_SCORE_SALT);
			this.cellXs[slot] = cellX;
			this.nearestCellYs[slot] = nearestCellY;
			this.cellZs[slot] = cellZ;
			this.identityQuartXs[slot] = QuartPos.fromBlock((int) Math.floor(nearestSiteX * horizontalSize));
			this.identityQuartYs[slot] = QuartPos.fromBlock((int) Math.floor(nearestSiteY * verticalSize));
			this.identityQuartZs[slot] = QuartPos.fromBlock((int) Math.floor(nearestSiteZ * horizontalSize));
		}

		void clear() {
			java.util.Arrays.fill(this.keys, 0L);
		}
	}
	private static final ThreadLocal<RegionCache> REGION_CACHE = ThreadLocal.withInitial(RegionCache::new);

	private static long sampleKey(long seed, int quartX, int quartY, int quartZ, int horizontalSize, int verticalSize) {
		long coords = ((long) (quartY & 0xFFFF) << 48) | ((long) (quartX & 0xFFFFFF) << 24) | (quartZ & 0xFFFFFFL);
		long sizes = ((long) horizontalSize << 32) | (verticalSize & 0xFFFFFFFFL);
		return coords ^ seed ^ HashCommon.mix(sizes);
	}

	public static boolean allowsCaveBiome(
		Preset preset,
		long seed,
		Climate.TargetPoint target,
		int quartX,
		int quartY,
		int quartZ,
		float surfaceCoverageFactor
	) {
		ClimateSettings.BiomeShape settings = preset.climate().biomeShape;
		long selectionStart = Climate.quantizeCoord(SURFACE_DEPTH);
		if (target.depth() < selectionStart) {
			return true;
		}
		long fullDensityStart = selectionStart + Climate.quantizeCoord(
			(UndergroundBiomeSurfaceProtection.HARD_SHELL_BLOCKS
				+ UndergroundBiomeSurfaceProtection.TRANSITION_BLOCKS) * DEPTH_UNITS_PER_BLOCK
		);
		float effectiveDensity = settings.undergroundBiomeCoverage() * Math.min(
			surfaceFactor(target.depth(), selectionStart, fullDensityStart),
			Math.clamp(surfaceCoverageFactor, 0.0F, 1.0F)
		);
		if (effectiveDensity <= 0.0F) {
			return false;
		}
		if (effectiveDensity >= 1.0F) {
			return true;
		}

		int horizontalSize = settings.undergroundBiomeSize();
		int verticalSize = settings.undergroundBiomeVerticalSize(
			preset.world().properties.worldHeight,
			preset.world().properties.worldDepth
		);
		long key = sampleKey(seed, quartX, quartY, quartZ, horizontalSize, verticalSize);
		RegionCache cache = REGION_CACHE.get();
		int slot = cache.getOrCompute(key, seed, quartX, quartY, quartZ, horizontalSize, verticalSize);
		return cache.occupancies[slot] < effectiveDensity;
	}

	private static float surfaceFactor(long depth, long selectionStart, long fullDensityStart) {
		long hardShellEnd = selectionStart + Climate.quantizeCoord(
			UndergroundBiomeSurfaceProtection.HARD_SHELL_BLOCKS * DEPTH_UNITS_PER_BLOCK
		);
		if (depth <= hardShellEnd || fullDensityStart <= hardShellEnd) {
			return 0.0F;
		}
		if (depth >= fullDensityStart) {
			return 1.0F;
		}
		return Math.clamp(
			(float) (depth - hardShellEnd) / (float) (fullDensityStart - hardShellEnd),
			0.0F,
			1.0F
		);
	}

	public static void clearThreadCaches() {
		REGION_CACHE.get().clear();
		Stage.LocalMru mru = Stage.LOCAL_MRU.get();
		mru.stageId = 0L;
		mru.key0 = 0L;
		mru.val0 = null;
		mru.key1 = 0L;
		mru.val1 = null;
		UndergroundBiomeSurfaceProtection.clearThreadCaches();
	}

	private static final class Candidate<T> {
		private final T value;
		private final Set<Climate.ParameterPoint> shallowRegistrations = new LinkedHashSet<>();
		private final Set<Climate.ParameterPoint> bottomRegistrations = new LinkedHashSet<>();

		private Candidate(T value) {
			this.value = value;
		}

		private void addShallow(Climate.ParameterPoint point) {
			this.shallowRegistrations.add(point);
		}

		private void addBottom(Climate.ParameterPoint point) {
			this.bottomRegistrations.add(point);
		}

		private boolean hasShallow() {
			return !this.shallowRegistrations.isEmpty();
		}

		private boolean hasBottom() {
			return !this.bottomRegistrations.isEmpty();
		}

		private StageCandidate<T> forStage(boolean includeBottom) {
			List<Climate.ParameterPoint> registrations = new ArrayList<>(this.shallowRegistrations);
			if (includeBottom) {
				for (Climate.ParameterPoint point : this.bottomRegistrations) {
					if (!registrations.contains(point)) {
						registrations.add(point);
					}
				}
			}
			return new StageCandidate<>(this.value, List.copyOf(registrations));
		}
	}

	private static final class StageCandidate<T> {
		private final T value;
		private final Climate.ParameterPoint[] registrations;

		private StageCandidate(T value, List<Climate.ParameterPoint> registrations) {
			this.value = value;
			this.registrations = registrations.toArray(new Climate.ParameterPoint[0]);
		}

		private T value() {
			return this.value;
		}

		private long distanceSq(Climate.TargetPoint target) {
			long minDistanceSq = Long.MAX_VALUE;
			for (Climate.ParameterPoint registration : this.registrations) {
				long d = horizontalClimateDistanceSq(registration, target);
				if (d < minDistanceSq) {
					minDistanceSq = d;
				}
			}
			return minDistanceSq;
		}
	}

	private static final class ConcurrentRankedCache {
		private static final int SETS = 1024;
		private static final int SET_MASK = SETS - 1;
		private static final int WAYS = 4;
		private static final int TOTAL_ENTRIES = SETS * WAYS;

		private final AtomicLongArray keys = new AtomicLongArray(TOTAL_ENTRIES);
		private final AtomicReferenceArray<int[]> values = new AtomicReferenceArray<>(TOTAL_ENTRIES);
		private final AtomicIntegerArray clocks = new AtomicIntegerArray(SETS);

		int[] find(long key) {
			long searchKey = key == 0L ? 1L : key;
			int set = (int) HashCommon.mix(searchKey) & SET_MASK;
			int base = set * WAYS;
			for (int way = 0; way < WAYS; way++) {
				int slot = base + way;
				if (this.keys.get(slot) == searchKey) {
					int[] value = this.values.get(slot);
					if (value != null && this.keys.get(slot) == searchKey) {
						return value;
					}
				}
			}
			return null;
		}

		void store(long key, int[] value) {
			if (value == null) {
				return;
			}
			long storeKey = key == 0L ? 1L : key;
			int set = (int) HashCommon.mix(storeKey) & SET_MASK;
			int base = set * WAYS;

			int emptySlot = -1;
			for (int way = 0; way < WAYS; way++) {
				int slot = base + way;
				long currentKey = this.keys.get(slot);
				if (currentKey == storeKey) {
					this.values.set(slot, value);
					return;
				}
				if (currentKey == 0L && emptySlot == -1) {
					emptySlot = slot;
				}
			}

			if (emptySlot != -1) {
				this.values.set(emptySlot, value);
				this.keys.set(emptySlot, storeKey);
				return;
			}

			int victimWay = (this.clocks.getAndIncrement(set) & 0x7FFFFFFF) % WAYS;
			int victimSlot = base + victimWay;
			this.keys.set(victimSlot, 0L);
			this.values.set(victimSlot, value);
			this.keys.set(victimSlot, storeKey);
		}

		void clear() {
			for (int i = 0; i < TOTAL_ENTRIES; i++) {
				this.keys.set(i, 0L);
				this.values.set(i, null);
			}
			for (int i = 0; i < SETS; i++) {
				this.clocks.set(i, 0);
			}
		}
	}

	private static final class Stage<T> {
		private static final AtomicLong STAGE_ID_GEN = new AtomicLong();

		private static final class PhaseMru {
			long seed;
			int cellX = Integer.MIN_VALUE;
			int cellZ = Integer.MIN_VALUE;
			int candidateCount;
			int phase;
		}
		private static final ThreadLocal<PhaseMru> PHASE_MRU = ThreadLocal.withInitial(PhaseMru::new);

		private static final class LocalMru {
			long stageId;
			long key0;
			int[] val0;
			long key1;
			int[] val1;
		}
		private static final ThreadLocal<LocalMru> LOCAL_MRU = ThreadLocal.withInitial(LocalMru::new);

		private final long stageId = STAGE_ID_GEN.incrementAndGet();
		private final StageCandidate<T>[] candidates;
		private final ConcurrentRankedCache rankedCache = new ConcurrentRankedCache();

		@SuppressWarnings("unchecked")
		private Stage(List<StageCandidate<T>> candidates) {
			this.candidates = candidates.toArray(new StageCandidate[0]);
		}

		private T findValue(
			long regionKey,
			int cellX,
			int cellY,
			int cellZ,
			int identityQuartX,
			int identityQuartY,
			int identityQuartZ,
			TargetSampler targetSampler,
			long seed,
			float climateInfluence,
			boolean bandingEnabled
		) {
			int candidateCount = this.candidates.length;
			if (candidateCount == 1) {
				return this.candidates[0].value();
			}

			int horizontalPhase = this.getHorizontalPhase(seed, cellX, cellZ, candidateCount);

			if (climateInfluence <= 0.0F) {
				if (!bandingEnabled) {
					return this.candidates[horizontalPhase].value();
				}
				int rotationIndex = Math.floorMod(cellY + horizontalPhase, candidateCount);
				return this.candidates[rotationIndex].value();
			}

			int[] rankedIndices;
			LocalMru mru = LOCAL_MRU.get();
			if (mru.stageId == this.stageId && mru.key0 == regionKey && mru.val0 != null) {
				rankedIndices = mru.val0;
			} else if (mru.stageId == this.stageId && mru.key1 == regionKey && mru.val1 != null) {
				rankedIndices = mru.val1;
				mru.key1 = mru.key0;
				mru.val1 = mru.val0;
				mru.key0 = regionKey;
				mru.val0 = rankedIndices;
			} else {
				rankedIndices = this.rankedCache.find(regionKey);
				if (rankedIndices == null) {
					Climate.TargetPoint identityTarget = targetSampler != null
						? targetSampler.sample(identityQuartX, identityQuartY, identityQuartZ)
						: Climate.target(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
					rankedIndices = this.computeRankedIndices(identityTarget);
					this.rankedCache.store(regionKey, rankedIndices);
				}
				if (mru.stageId != this.stageId) {
					mru.stageId = this.stageId;
					mru.key0 = 0L;
					mru.val0 = null;
					mru.key1 = 0L;
					mru.val1 = null;
				}
				mru.key1 = mru.key0;
				mru.val1 = mru.val0;
				mru.key0 = regionKey;
				mru.val0 = rankedIndices;
			}

			if (!bandingEnabled) {
				if (climateInfluence >= 1.0F) {
					return this.candidates[rankedIndices[0]].value();
				}
				return this.candidates[rankedIndices[horizontalPhase]].value();
			}

			int rotationIndex = Math.floorMod(cellY + horizontalPhase, candidateCount);
			return this.candidates[rankedIndices[rotationIndex]].value();
		}

		private int getHorizontalPhase(long seed, int cellX, int cellZ, int candidateCount) {
			PhaseMru mru = PHASE_MRU.get();
			if (mru.seed == seed && mru.cellX == cellX && mru.cellZ == cellZ && mru.candidateCount == candidateCount) {
				return mru.phase;
			}
			long horizontalHash = cellHash(seed, cellX, 0, cellZ, IDENTITY_CELL_SALT);
			int phase = Math.floorMod((int) (horizontalHash ^ (horizontalHash >>> 32)), candidateCount);
			mru.seed = seed;
			mru.cellX = cellX;
			mru.cellZ = cellZ;
			mru.candidateCount = candidateCount;
			mru.phase = phase;
			return phase;
		}

		private static final ThreadLocal<long[]> DISTANCE_SCRATCH = ThreadLocal.withInitial(() -> new long[32]);

		private int[] computeRankedIndices(Climate.TargetPoint target) {
			int candidateCount = this.candidates.length;
			long[] distancesSq = DISTANCE_SCRATCH.get();
			if (distancesSq.length < candidateCount) {
				distancesSq = new long[candidateCount];
				DISTANCE_SCRATCH.set(distancesSq);
			}
			for (int i = 0; i < candidateCount; i++) {
				distancesSq[i] = this.candidates[i].distanceSq(target);
			}

			int[] ranked = new int[candidateCount];
			for (int i = 0; i < candidateCount; i++) {
				ranked[i] = i;
			}

			// Stable primitive insertion sort with index tie-breaker
			for (int i = 1; i < candidateCount; i++) {
				int key = ranked[i];
				long keyDist = distancesSq[key];
				int j = i - 1;
				while (j >= 0) {
					int prev = ranked[j];
					long prevDist = distancesSq[prev];
					if (prevDist > keyDist || (prevDist == keyDist && prev > key)) {
						ranked[j + 1] = prev;
						j--;
					} else {
						break;
					}
				}
				ranked[j + 1] = key;
			}
			return ranked;
		}

		private void clear() {
			this.rankedCache.clear();
		}
	}


	@FunctionalInterface
	public interface TargetSampler {
		Climate.TargetPoint sample(int quartX, int quartY, int quartZ);
	}

	public static final class Layout<T> {
		private final Climate.ParameterList<T> original;
		private final Climate.ParameterList<T> background;
		private final Stage<T> shallowStage;
		private final Stage<T> deepStage;
		private final long selectionStart;
		private final long fullDensityStart;
		private final long deepStart;
		private final long endDepth;
		private final float density;
		private final float climateInfluence;
		private final int horizontalSize;
		private final int verticalSize;
		private final boolean bandingEnabled;
		private final long seed;
		private final int shallowCandidateCount;
		private final int deepCandidateCount;
		private final int unknownEntryCount;
		private final int classificationFailureCount;
		private final List<T> shallowCandidateValues;
		private final List<T> deepCandidateValues;
		private final Set<T> caveCandidateSet;

		private Layout(
			Climate.ParameterList<T> original,
			Climate.ParameterList<T> background,
			Stage<T> shallowStage,
			Stage<T> deepStage,
			long selectionStart,
			long fullDensityStart,
			long deepStart,
			long endDepth,
			float density,
			float climateInfluence,
			int horizontalSize,
			int verticalSize,
			boolean bandingEnabled,
			long seed,
			int shallowCandidateCount,
			int deepCandidateCount,
			int unknownEntryCount,
			int classificationFailureCount,
			List<T> shallowCandidateValues,
			List<T> deepCandidateValues
		) {
			this.original = original;
			this.background = background;
			this.shallowStage = shallowStage;
			this.deepStage = deepStage;
			this.selectionStart = selectionStart;
			this.fullDensityStart = fullDensityStart;
			this.deepStart = deepStart;
			this.endDepth = endDepth;
			this.density = density;
			this.climateInfluence = climateInfluence;
			this.horizontalSize = horizontalSize;
			this.verticalSize = verticalSize;
			this.bandingEnabled = bandingEnabled;
			this.seed = seed;
			this.shallowCandidateCount = shallowCandidateCount;
			this.deepCandidateCount = deepCandidateCount;
			this.unknownEntryCount = unknownEntryCount;
			this.classificationFailureCount = classificationFailureCount;
			this.shallowCandidateValues = List.copyOf(shallowCandidateValues);
			this.deepCandidateValues = List.copyOf(deepCandidateValues);
			Set<T> candidateSet = new LinkedHashSet<>(this.shallowCandidateValues);
			candidateSet.addAll(this.deepCandidateValues);
			this.caveCandidateSet = Set.copyOf(candidateSet);
		}

		private static <T> Layout<T> unmodified(
			Climate.ParameterList<T> original,
			Map<T, Candidate<T>> candidates,
			int unknownEntryCount,
			int classificationFailureCount
		) {
			int shallowCandidates = (int) candidates.values().stream().filter(Candidate::hasShallow).count();
			List<T> shallowValues = candidates.values().stream()
				.filter(Candidate::hasShallow)
				.map(candidate -> candidate.value)
				.toList();
			List<T> deepValues = candidates.values().stream().map(candidate -> candidate.value).toList();
			return new Layout<>(
				original,
				original,
				null,
				null,
				Long.MAX_VALUE,
				Long.MAX_VALUE,
				Long.MAX_VALUE,
				Long.MAX_VALUE,
				0.0F,
				1.0F,
				1,
				1,
				false,
				0L,
				shallowCandidates,
				candidates.size(),
				unknownEntryCount,
				classificationFailureCount,
				shallowValues,
				deepValues
			);
		}

		public long bandingStart() {
			return this.selectionStart;
		}

		public long fullDensityStart() {
			return this.fullDensityStart;
		}

		public boolean appliesAt(Climate.TargetPoint target) {
			return target.depth() >= this.selectionStart;
		}

		public int shallowCandidateCount() {
			return this.shallowCandidateCount;
		}

		public int deepCandidateCount() {
			return this.deepCandidateCount;
		}

		public int unknownEntryCount() {
			return this.unknownEntryCount;
		}

		public int classificationFailureCount() {
			return this.classificationFailureCount;
		}

		public List<T> shallowCandidateValues() {
			return this.shallowCandidateValues;
		}

		public List<T> deepCandidateValues() {
			return this.deepCandidateValues;
		}

		public boolean isCaveCandidate(T value) {
			return this.caveCandidateSet.contains(value);
		}

		public T backgroundValue(Climate.TargetPoint target) {
			return this.background.findValue(target);
		}

		public T findValue(Climate.TargetPoint target, int quartX, int quartY, int quartZ) {
			return this.findValue(target, quartX, quartY, quartZ, 1.0F);
		}

		public T findValue(
			Climate.TargetPoint target,
			int quartX,
			int quartY,
			int quartZ,
			float surfaceCoverageFactor
		) {
			return this.findValue(
				target, quartX, quartY, quartZ, surfaceCoverageFactor,
				(identityQuartX, identityQuartY, identityQuartZ) -> target
			);
		}

		public T findValue(
			Climate.TargetPoint target,
			int quartX,
			int quartY,
			int quartZ,
			float surfaceCoverageFactor,
			TargetSampler targetSampler
		) {
			if (!this.appliesAt(target)) {
				return this.original.findValue(target);
			}
			Stage<T> stage = target.depth() < this.deepStart && this.shallowStage != null
				? this.shallowStage
				: this.deepStage;
			if (stage == null) {
				stage = this.shallowStage;
			}
			if (stage == null) {
				return this.original.findValue(target);
			}

			float surfaceFactor = Math.min(
				this.surfaceFactor(target.depth()),
				Math.clamp(surfaceCoverageFactor, 0.0F, 1.0F)
			);
			float effectiveDensity = this.density * surfaceFactor;
			if (effectiveDensity <= 0.0F) {
				return this.background.findValue(target);
			}

			long key = sampleKey(this.seed, quartX, quartY, quartZ, this.horizontalSize, this.verticalSize);
			RegionCache cache = REGION_CACHE.get();
			int slot = cache.getOrCompute(key, this.seed, quartX, quartY, quartZ, this.horizontalSize, this.verticalSize);
			if (effectiveDensity < 1.0F && cache.occupancies[slot] >= effectiveDensity) {
				return this.background.findValue(target);
			}

			return stage.findValue(
				cache.nearestKeys[slot],
				cache.cellXs[slot],
				cache.nearestCellYs[slot],
				cache.cellZs[slot],
				cache.identityQuartXs[slot],
				cache.identityQuartYs[slot],
				cache.identityQuartZs[slot],
				targetSampler,
				this.seed,
				this.climateInfluence,
				this.bandingEnabled
			);
		}

		public void clear() {
			if (this.shallowStage != null) {
				this.shallowStage.clear();
			}
			if (this.deepStage != null) {
				this.deepStage.clear();
			}
			clearThreadCaches();
		}

		private float surfaceFactor(long depth) {
			return UndergroundBiomeBanding.surfaceFactor(
				depth,
				this.selectionStart,
				this.fullDensityStart
			);
		}
	}

	public enum CandidateRole {
		SURFACE,
		SHALLOW_CAVE,
		DEEP_CAVE,
		UNKNOWN
	}
}
