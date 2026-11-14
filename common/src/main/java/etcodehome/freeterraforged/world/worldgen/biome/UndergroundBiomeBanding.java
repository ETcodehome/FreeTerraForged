package etcodehome.freeterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import com.mojang.datafixers.util.Pair;

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
	private static final float SURFACE_DEPTH = 0.0F;
	private static final long OCCUPANCY_CELL_SALT = 0x3CB2F11B1A5D39E7L;
	private static final long IDENTITY_CELL_SALT = 0x61F0CE12D47A958BL;

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

	@SuppressWarnings("unchecked")
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
		boolean hasShallow = !shallowCandidates.isEmpty() && endDepth > SURFACE_DEPTH;
		boolean hasDeep = !deepCandidates.isEmpty() && endDepth > VANILLA_BOTTOM_DEPTH;
		if (!hasShallow && !hasDeep) {
			return Layout.unmodified(original, candidates, unknownEntryCount, classificationFailureCount);
		}

		float selectionStart = !hasShallow ? VANILLA_BOTTOM_DEPTH : SURFACE_DEPTH;
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
			hasShallow ? shallowCandidates.toArray(new StageCandidate[0]) : null,
			hasDeep ? deepCandidates.toArray(new StageCandidate[0]) : null,
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
		if (depth <= Climate.quantizeCoord(0.0F)) {
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

	private static long climateDistanceSq(Climate.ParameterPoint point, Climate.TargetPoint target) {
		long dt = point.temperature().distance(target.temperature());
		long dh = point.humidity().distance(target.humidity());
		long dc = point.continentalness().distance(target.continentalness());
		long de = point.erosion().distance(target.erosion());
		long dd = point.depth().distance(target.depth());
		long dw = point.weirdness().distance(target.weirdness());
		long dOff = Math.abs(point.offset());
		return dt * dt + dh * dh + dc * dc + de * de + dd * dd + dw * dw + dOff * dOff;
	}

	private static long hash2D(long seed, int x, int z) {
		long hash = mix64(seed ^ 0x9E3779B97F4A7C15L);
		hash = mix64(hash ^ (Integer.toUnsignedLong(x) * 0xD6E8FEB86659FD93L));
		return mix64(hash ^ (Integer.toUnsignedLong(z) * 0x9E6C63D0676A9A99L));
	}

	private static long mix64(long value) {
		value ^= value >>> 30;
		value *= 0xBF58476D1CE4E5B9L;
		value ^= value >>> 27;
		value *= 0x94D049BB133111EBL;
		return value ^ value >>> 31;
	}

	static float surfaceFactor(long depth, long selectionStart, long fullDensityStart) {
		if (depth <= selectionStart) {
			return 0.0F;
		}
		if (depth >= fullDensityStart) {
			return 1.0F;
		}
		return (float) (depth - selectionStart) / (float) (fullDensityStart - selectionStart);
	}

	public static void clearThreadCaches() {
		UndergroundBiomeSurfaceProtection.clearThreadCaches();
		BiomeSurfaceEnvelope.clearThreadCaches();
		etcodehome.freeterraforged.world.worldgen.densityfunction.ClimateDepthFunction.clearThreadCaches();
		etcodehome.freeterraforged.world.worldgen.cell.heightmap.FilteredChunkCache.clearThreadCaches();
		etcodehome.freeterraforged.world.worldgen.cell.heightmap.RawChunkCache.clearThreadCaches();
		Layout.clearRankThreadLocals();
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
			return new StageCandidate<>(this.value, registrations);
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
				long d = climateDistanceSq(registration, target);
				if (d < minDistanceSq) {
					minDistanceSq = d;
				}
			}
			return minDistanceSq;
		}
	}

	public interface TargetSampler {
		Climate.TargetPoint sample(int identityQuartX, int identityQuartY, int identityQuartZ);
	}

	public static final class Layout<T> {
		private final Climate.ParameterList<T> original;
		private final Climate.ParameterList<T> background;
		private final StageCandidate<T>[] shallowCandidates;
		private final StageCandidate<T>[] deepCandidates;
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
			StageCandidate<T>[] shallowCandidates,
			StageCandidate<T>[] deepCandidates,
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
			this.shallowCandidates = shallowCandidates;
			this.deepCandidates = deepCandidates;
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
				null
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

			StageCandidate<T>[] candidates = (target.depth() < this.deepStart && this.shallowCandidates != null && this.shallowCandidates.length > 0)
				? this.shallowCandidates
				: this.deepCandidates;
			if (candidates == null || candidates.length == 0) {
				candidates = this.shallowCandidates;
			}
			if (candidates == null || candidates.length == 0) {
				return this.original.findValue(target);
			}

			// 1. Surface clearance protection: cave biomes must never project through the surface
			float surfaceFactor = Math.min(
				this.surfaceFactor(target.depth()),
				Math.max(0.0F, Math.min(1.0F, surfaceCoverageFactor))
			);
			float effectiveCoverage = this.density * surfaceFactor;
			if (effectiveCoverage <= 0.0F) {
				return this.background.findValue(target);
			}

			int candidateCount = candidates.length;
			if (candidateCount == 1) {
				return candidates[0].value();
			}

			int blockX = QuartPos.toBlock(quartX);
			int blockY = QuartPos.toBlock(quartY);
			int blockZ = QuartPos.toBlock(quartZ);

			// 2. Cave biome coverage: at 0 no cave biomes appear, at 1 all underground selections are cave biomes
			int hSize = Math.max(16, this.horizontalSize);
			int cellX = Math.floorDiv(blockX, hSize);
			int cellZ = Math.floorDiv(blockZ, hSize);

			if (effectiveCoverage < 1.0F) {
				long covHash = hash2D(this.seed ^ OCCUPANCY_CELL_SALT, cellX, cellZ);
				float regionRoll = (float) ((covHash >>> 11) * 0x1.0p-53);
				if (regionRoll >= effectiveCoverage) {
					return this.background.findValue(target);
				}
			}

			// 3. Underground biome horizontal size in blocks as lateral frequency of cave biomes
			long phaseHash = hash2D(this.seed ^ IDENTITY_CELL_SALT, cellX, cellZ);
			int horizontalPhase = Math.floorMod((int) (phaseHash ^ (phaseHash >>> 32)), candidateCount);

			// 4. Vertical cave biome banding & vertical size
			int layer;
			if (!this.bandingEnabled) {
				layer = 0; // Columnar: constant down the entire column
			} else {
				int vSize = Math.max(8, this.verticalSize);
				layer = Math.floorDiv(blockY, vSize); // Layer thickness in blocks
			}

			// 5. Cave climate influence:
			// At 0: any climate can be matched (completely tolerant), selecting from all candidates in layered stacks
			// At 1: only the closest climate match will ever be chosen
			if (this.climateInfluence <= 0.0F) {
				int index = Math.floorMod(layer + horizontalPhase, candidateCount);
				return candidates[index].value();
			}

			if (this.climateInfluence >= 1.0F) {
				int bestIdx = 0;
				long minDistance = Long.MAX_VALUE;
				for (int i = 0; i < candidateCount; i++) {
					long d = candidates[i].distanceSq(target);
					if (d < minDistance) {
						minDistance = d;
						bestIdx = i;
					}
				}
				return candidates[bestIdx].value();
			}

			// Intermediate influence: 0.0 -> all candidates in pool, 1.0 -> pool of 1 (closest candidate)
			float tolerance = 1.0F - this.climateInfluence;
			int poolSize = Math.max(1, Math.min(candidateCount, 1 + Math.round(tolerance * (candidateCount - 1))));
			int[] ranked = rankCandidatesByDistance(candidates, target);
			int chosenRank = Math.floorMod(layer + horizontalPhase, poolSize);
			return candidates[ranked[chosenRank]].value();
		}

		private static final ThreadLocal<int[]> RANK_INDICES = ThreadLocal.withInitial(() -> new int[64]);
		private static final ThreadLocal<long[]> RANK_DISTANCES = ThreadLocal.withInitial(() -> new long[64]);

		public static void clearRankThreadLocals() {
			RANK_INDICES.remove();
			RANK_DISTANCES.remove();
		}

		private static <T> int[] rankCandidatesByDistance(StageCandidate<T>[] candidates, Climate.TargetPoint target) {
			int n = candidates.length;
			int[] indices = RANK_INDICES.get();
			long[] distances = RANK_DISTANCES.get();
			if (indices.length < n) {
				indices = new int[Math.max(n, indices.length * 2)];
				distances = new long[indices.length];
				RANK_INDICES.set(indices);
				RANK_DISTANCES.set(distances);
			}
			for (int i = 0; i < n; i++) {
				indices[i] = i;
				distances[i] = candidates[i].distanceSq(target);
			}
			for (int i = 1; i < n; i++) {
				int keyIdx = indices[i];
				long keyDist = distances[keyIdx];
				int j = i - 1;
				while (j >= 0 && distances[indices[j]] > keyDist) {
					indices[j + 1] = indices[j];
					j--;
				}
				indices[j + 1] = keyIdx;
			}
			return indices;
		}

		public void clear() {
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
