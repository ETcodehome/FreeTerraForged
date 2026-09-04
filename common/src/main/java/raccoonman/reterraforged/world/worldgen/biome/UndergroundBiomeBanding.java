package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.ClimateSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;

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
	private static final long IDENTITY_SCORE_SALT = 0x2AD4B83598FC1E67L;

	private static final Climate.Parameter VANILLA_UNDERGROUND_DEPTH = Climate.Parameter.span(
		VANILLA_UNDERGROUND_DEPTH_START,
		VANILLA_UNDERGROUND_DEPTH_END
	);
	private static final Climate.Parameter VANILLA_BOTTOM = Climate.Parameter.point(VANILLA_BOTTOM_DEPTH);

	private UndergroundBiomeBanding() {
	}

	public static <T> Layout<T> apply(Preset preset, List<Pair<Climate.ParameterPoint, T>> entries) {
		return apply(preset, entries, entries, 0L, (point, value) -> classify(point, false));
	}

	public static <T> Layout<T> apply(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> entries,
		long seed
	) {
		return apply(preset, entries, entries, seed, (point, value) -> classify(point, false));
	}

	public static <T> Layout<T> apply(
		Preset preset,
		Climate.ParameterList<T> source,
		long seed
	) {
		return apply(
			preset, source, source.values(), seed,
			(point, value) -> classify(point, false)
		);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> entries,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier
	) {
		return apply(preset, entries, entries, 0L, classifier);
	}

	public static <T> Layout<T> apply(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> entries,
		long seed,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier
	) {
		return apply(preset, entries, entries, seed, classifier);
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
			classifier
		);
	}

	private static <T> Layout<T> apply(
		Preset preset,
		Climate.ParameterList<T> original,
		List<Pair<Climate.ParameterPoint, T>> candidateEntries,
		long seed,
		BiFunction<Climate.ParameterPoint, T, CandidateRole> classifier
	) {
		List<Pair<Climate.ParameterPoint, T>> sourceEntries = original.values();
		Map<T, Candidate<T>> candidates = new LinkedHashMap<>();
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

		List<Pair<Climate.ParameterPoint, T>> backgroundEntries = new ArrayList<>();
		for (Pair<Climate.ParameterPoint, T> entry : sourceEntries) {
			if (entry == null || entry.getFirst() == null || entry.getSecond() == null) {
				continue;
			}
			try {
				CandidateRole role = classifier.apply(entry.getFirst(), entry.getSecond());
				if (role != CandidateRole.SHALLOW_CAVE && role != CandidateRole.DEEP_CAVE) {
					backgroundEntries.add(entry);
				}
			} catch (RuntimeException exception) {
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
		float fullDensityStart = Math.min(
			endDepth,
			selectionStart + (
				UndergroundBiomeSurfaceProtection.HARD_SHELL_BLOCKS
					+ UndergroundBiomeSurfaceProtection.TRANSITION_BLOCKS
			) * DEPTH_UNITS_PER_BLOCK
		);
		ClimateSettings.BiomeShape settings = preset.climate().biomeShape;
		int horizontalSize = settings.undergroundBiomeSize();
		int verticalSize = settings.undergroundBiomeVerticalSize(
			preset.world().properties.worldHeight,
			preset.world().properties.worldDepth
		);
		float coverage = settings.undergroundBiomeCoverage();
		float climateInfluence = settings.undergroundBiomeClimateInfluence();

		RTFCommon.LOGGER.info(
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
		long bottom = Climate.quantizeCoord(VANILLA_BOTTOM_DEPTH);
		if (depth.max() <= surface) {
			return CandidateRole.SURFACE;
		}
		if (caveTagged && depth.min() > surface) {
			return depth.min() >= bottom ? CandidateRole.DEEP_CAVE : CandidateRole.SHALLOW_CAVE;
		}
		return CandidateRole.UNKNOWN;
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

	private static double horizontalClimateDistance(Climate.ParameterPoint point, Climate.TargetPoint target) {
		double temperature = Climate.unquantizeCoord(point.temperature().distance(target.temperature()));
		double humidity = Climate.unquantizeCoord(point.humidity().distance(target.humidity()));
		double continentalness = Climate.unquantizeCoord(point.continentalness().distance(target.continentalness()));
		double erosion = Climate.unquantizeCoord(point.erosion().distance(target.erosion()));
		double weirdness = Climate.unquantizeCoord(point.weirdness().distance(target.weirdness()));
		double offset = Climate.unquantizeCoord(Math.abs(point.offset()));
		return Math.sqrt(
			temperature * temperature
				+ humidity * humidity
				+ continentalness * continentalness
				+ erosion * erosion
				+ weirdness * weirdness
				+ offset * offset
		);
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

	private static RegionSample sample3d(
		long seed,
		int blockX,
		int blockY,
		int blockZ,
		int horizontalSize,
		int verticalSize
	) {
		double x = (double) blockX / horizontalSize;
		double y = (double) blockY / verticalSize;
		double z = (double) blockZ / horizontalSize;
		int cellX = (int) Math.floor(x);
		int cellY = (int) Math.floor(y);
		int cellZ = (int) Math.floor(z);
		double nearestDistance = Double.POSITIVE_INFINITY;
		long nearestKey = 0L;

		for (int offsetY = -1; offsetY <= 1; offsetY++) {
			for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
				for (int offsetX = -1; offsetX <= 1; offsetX++) {
					int candidateX = cellX + offsetX;
					int candidateY = cellY + offsetY;
					int candidateZ = cellZ + offsetZ;
					long key = cellHash(seed, candidateX, candidateY, candidateZ, OCCUPANCY_CELL_SALT);
					double siteX = candidateX + 0.5D + (unit(key ^ 0x632BE59BD9B4E019L) - 0.5D) * CELL_JITTER;
					double siteY = candidateY + 0.5D + (unit(key ^ 0xC6BC279692B5C323L) - 0.5D) * CELL_JITTER;
					double siteZ = candidateZ + 0.5D + (unit(key ^ 0xD1B54A32D192ED03L) - 0.5D) * CELL_JITTER;
					double dx = x - siteX;
					double dy = y - siteY;
					double dz = z - siteZ;
					double distance = dx * dx + dy * dy + dz * dz;
					if (distance < nearestDistance || (distance == nearestDistance && key < nearestKey)) {
						nearestDistance = distance;
						nearestKey = key;
					}
				}
			}
		}
		return new RegionSample(nearestKey, unit(nearestKey ^ OCCUPANCY_SCORE_SALT));
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
		long endDepth = Climate.quantizeCoord(endDepth(preset));
		if (target.depth() < selectionStart || target.depth() > endDepth) {
			return true;
		}
		long fullDensityStart = Math.min(
			endDepth,
			selectionStart + Climate.quantizeCoord(
				(UndergroundBiomeSurfaceProtection.HARD_SHELL_BLOCKS
					+ UndergroundBiomeSurfaceProtection.TRANSITION_BLOCKS) * DEPTH_UNITS_PER_BLOCK
			)
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
		RegionSample region = sample3d(
			seed,
			QuartPos.toBlock(quartX),
			QuartPos.toBlock(quartY),
			QuartPos.toBlock(quartZ),
			horizontalSize,
			verticalSize
		);
		return region.occupancy() < effectiveDensity;
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

	private record StageCandidate<T>(T value, List<Climate.ParameterPoint> registrations) {
		private double distance(Climate.TargetPoint target) {
			double distance = Double.POSITIVE_INFINITY;
			for (Climate.ParameterPoint registration : this.registrations) {
				distance = Math.min(distance, horizontalClimateDistance(registration, target));
			}
			return distance;
		}
	}

	private static final class Stage<T> {
		private final List<StageCandidate<T>> candidates;

		private Stage(List<StageCandidate<T>> candidates) {
			this.candidates = List.copyOf(candidates);
		}

		private T findValue(Climate.TargetPoint target, long regionKey, float climateInfluence) {
			if (this.candidates.size() == 1) {
				return this.candidates.getFirst().value();
			}

			double minimumDistance = Double.POSITIVE_INFINITY;
			double[] distances = new double[this.candidates.size()];
			for (int index = 0; index < this.candidates.size(); index++) {
				double distance = this.candidates.get(index).distance(target);
				distances[index] = distance;
				minimumDistance = Math.min(minimumDistance, distance);
			}

			if (climateInfluence >= 1.0F) {
				int tieCount = 0;
				for (double distance : distances) {
					if (distance == minimumDistance) {
						tieCount++;
					}
				}
				int tie = Math.min((int) (unit(regionKey ^ IDENTITY_SCORE_SALT) * tieCount), tieCount - 1);
				for (int index = 0; index < distances.length; index++) {
					if (distances[index] == minimumDistance && tie-- == 0) {
						return this.candidates.get(index).value();
					}
				}
			}

			double sharpness = CLIMATE_INFLUENCE_SCALE * climateInfluence / (1.0D - climateInfluence);
			double totalWeight = 0.0D;
			double[] weights = new double[distances.length];
			for (int index = 0; index < distances.length; index++) {
				double weight = Math.exp(-(distances[index] - minimumDistance) * sharpness);
				weights[index] = weight;
				totalWeight += weight;
			}
			double selectedWeight = unit(regionKey ^ IDENTITY_SCORE_SALT) * totalWeight;
			for (int index = 0; index < weights.length; index++) {
				selectedWeight -= weights[index];
				if (selectedWeight <= 0.0D) {
					return this.candidates.get(index).value();
				}
			}
			return this.candidates.getLast().value();
		}
	}

	private record RegionSample(long key, double occupancy) {
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
			return target.depth() >= this.selectionStart && target.depth() <= this.endDepth;
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
			return this.shallowCandidateValues.contains(value) || this.deepCandidateValues.contains(value);
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

			T backgroundValue = this.background.findValue(target);
			float surfaceFactor = Math.min(
				this.surfaceFactor(target.depth()),
				Math.clamp(surfaceCoverageFactor, 0.0F, 1.0F)
			);
			float effectiveDensity = this.density * surfaceFactor;
			if (effectiveDensity <= 0.0F) {
				return backgroundValue;
			}

			int blockX = QuartPos.toBlock(quartX);
			int blockY = QuartPos.toBlock(quartY);
			int blockZ = QuartPos.toBlock(quartZ);
			RegionSample region = sample3d(
				this.seed,
				blockX,
				blockY,
				blockZ,
				this.horizontalSize,
				this.verticalSize
			);
			if (effectiveDensity < 1.0F && region.occupancy() >= effectiveDensity) {
				return backgroundValue;
			}

			if (!this.bandingEnabled) {
				return this.original.findValue(target);
			}
			return stage.findValue(target, region.key(), this.climateInfluence);
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
