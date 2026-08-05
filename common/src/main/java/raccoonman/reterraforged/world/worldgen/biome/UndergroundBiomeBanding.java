package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;

/**
 * Redistributes convention-following underground biomes through the usable world depth.
 *
 * <p>The original biome source remains authoritative above a terrain-relative surface buffer.
 * The first dynamic band selects a cave candidate from its original horizontal climate
 * registrations. Every later band has direct candidate ownership, rotated by depth and phased by
 * weirdness, so a single two-dimensional climate winner cannot monopolize an extended column.</p>
 */
public final class UndergroundBiomeBanding {
	public static final int DEFAULT_BIOME_SIZE = 225;

	private static final float VANILLA_UNDERGROUND_DEPTH_START = 0.2F;
	private static final float VANILLA_UNDERGROUND_DEPTH_END = 0.9F;
	private static final float VANILLA_BOTTOM_DEPTH = 1.1F;
	private static final float DEPTH_UNITS_PER_BLOCK = 1.0F / 128.0F;
	private static final float SURFACE_DEPTH = NoiseRouterData.GLOBAL_OFFSET + 0.5F;
	private static final float SHALLOW_STAGE_END = Climate.unquantizeCoord(Climate.quantizeCoord(VANILLA_BOTTOM_DEPTH) - 1L);
	private static final int DEFAULT_WORLD_DEPTH = 64;
	private static final int DEFAULT_WORLD_HEIGHT = 256;
	private static final int MAX_BAND_COUNT = 32;
	static final int MAX_SURFACE_BUFFER_BLOCKS = 24;
	private static final float DEFAULT_DYNAMIC_STAGE_BLOCKS =
		DEFAULT_WORLD_DEPTH + DEFAULT_WORLD_HEIGHT - VANILLA_BOTTOM_DEPTH / DEPTH_UNITS_PER_BLOCK;

	private static final Climate.Parameter VANILLA_UNDERGROUND_DEPTH = Climate.Parameter.span(VANILLA_UNDERGROUND_DEPTH_START, VANILLA_UNDERGROUND_DEPTH_END);
	private static final Climate.Parameter VANILLA_BOTTOM = Climate.Parameter.point(VANILLA_BOTTOM_DEPTH);
	private static final Climate.Parameter VANILLA_FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);

	private UndergroundBiomeBanding() {
	}

	public static <T> Layout<T> apply(Preset preset, List<Pair<Climate.ParameterPoint, T>> entries) {
		Map<T, Candidate<T>> candidates = new LinkedHashMap<>();
		for (Pair<Climate.ParameterPoint, T> entry : entries) {
			Climate.ParameterPoint point = entry.getFirst();
			if (!isVanillaConventionUnderground(point)) {
				continue;
			}
			Candidate<T> candidate = candidates.computeIfAbsent(entry.getSecond(), Candidate::new);
			if (point.depth().equals(VANILLA_UNDERGROUND_DEPTH)) {
				candidate.addShallow(point);
			} else {
				candidate.addBottom(point);
			}
		}

		Climate.ParameterList<T> original = new Climate.ParameterList<>(entries);
		if (candidates.size() < 2) {
			return Layout.unmodified(original);
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
		float bandingStart = bandingStart(preset, shallowCandidates.size());
		float shallowEnd = Math.min(SHALLOW_STAGE_END, endDepth);
		int shallowBandCount = bandCount(preset, shallowCandidates.size(), bandingStart, shallowEnd);
		int deepBandCount = bandCount(preset, deepCandidates.size(), VANILLA_BOTTOM_DEPTH, endDepth);
		if (shallowBandCount == 0 && deepBandCount == 0) {
			return Layout.unmodified(original);
		}

		Stage<T> shallowStage = shallowBandCount == 0
			? null
			: new Stage<>(shallowCandidates, bandingStart, shallowEnd, shallowBandCount, true);
		Stage<T> deepStage = deepBandCount == 0
			? null
			: new Stage<>(deepCandidates, VANILLA_BOTTOM_DEPTH, endDepth, deepBandCount, shallowStage == null);

		RTFCommon.LOGGER.info(
			"Applied staged underground biome rotation: {} shallow candidates / {} bands, {} total deep-stage candidates ({} bottom-role) / {} bands, surface buffer {} blocks, depth {}..{}, {} source parameter points",
			shallowCandidates.size(),
			shallowBandCount,
			deepCandidates.size(),
			bottomCandidateCount,
			deepBandCount,
			(bandingStart - SURFACE_DEPTH) / DEPTH_UNITS_PER_BLOCK,
			bandingStart,
			endDepth,
			entries.size()
		);
		return new Layout<>(
			original,
			shallowStage,
			deepStage,
			Climate.quantizeCoord(bandingStart),
			Climate.quantizeCoord(VANILLA_BOTTOM_DEPTH)
		);
	}

	public static double undergroundNoiseScale(Preset preset) {
		int biomeSize = Math.max(1, preset.climate().biomeShape.biomeSize);
		return 0.25D * DEFAULT_BIOME_SIZE / biomeSize;
	}

	static int bandCount(Preset preset, int candidateCount, float startDepth, float endDepth) {
		if (candidateCount == 0 || endDepth <= startDepth) {
			return 0;
		}
		float stageBlocks = (endDepth - startDepth) / DEPTH_UNITS_PER_BLOCK;
		float verticalScale = (float) Math.sqrt(Math.max(1.0F, stageBlocks) / DEFAULT_DYNAMIC_STAGE_BLOCKS);
		float biomeScale = (float) Math.sqrt((float) DEFAULT_BIOME_SIZE / Math.max(1, preset.climate().biomeShape.biomeSize));
		return Math.clamp(Math.round(candidateCount * verticalScale * biomeScale), 1, MAX_BAND_COUNT);
	}

	static float endDepth(Preset preset) {
		WorldSettings.Properties properties = preset.world().properties;
		return Math.max(
			SURFACE_DEPTH + DEPTH_UNITS_PER_BLOCK,
			(properties.worldDepth + properties.worldHeight) * DEPTH_UNITS_PER_BLOCK
		);
	}

	static float bandingStart(Preset preset, int shallowCandidateCount) {
		float shallowEnd = Math.min(VANILLA_BOTTOM_DEPTH, endDepth(preset));
		if (shallowCandidateCount == 0 || shallowEnd <= SURFACE_DEPTH) {
			return VANILLA_BOTTOM_DEPTH;
		}
		int naturalBandCount = bandCount(preset, shallowCandidateCount, SURFACE_DEPTH, shallowEnd);
		float naturalBuffer = (shallowEnd - SURFACE_DEPTH) / (naturalBandCount + 1);
		float cappedBuffer = Math.min(MAX_SURFACE_BUFFER_BLOCKS * DEPTH_UNITS_PER_BLOCK, naturalBuffer);
		return SURFACE_DEPTH + cappedBuffer;
	}

	private static boolean isVanillaConventionUnderground(Climate.ParameterPoint point) {
		return point.weirdness().equals(VANILLA_FULL_RANGE)
			&& (point.depth().equals(VANILLA_UNDERGROUND_DEPTH) || point.depth().equals(VANILLA_BOTTOM));
	}

	private static int phase(long weirdness, int count) {
		if (count <= 1) {
			return 0;
		}
		long min = VANILLA_FULL_RANGE.min();
		long span = VANILLA_FULL_RANGE.max() - min + 1L;
		long position = Math.clamp(weirdness - min, 0L, span - 1L);
		return (int) (position * count / span);
	}

	private static long horizontalFitness(Climate.ParameterPoint point, Climate.TargetPoint target) {
		return square(point.temperature().distance(target.temperature()))
			+ square(point.humidity().distance(target.humidity()))
			+ square(point.continentalness().distance(target.continentalness()))
			+ square(point.erosion().distance(target.erosion()))
			+ square(point.weirdness().distance(target.weirdness()))
			+ square(point.offset());
	}

	private static long square(long value) {
		return value * value;
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
		private long fitness(Climate.TargetPoint target) {
			long fitness = Long.MAX_VALUE;
			for (Climate.ParameterPoint registration : this.registrations) {
				fitness = Math.min(fitness, horizontalFitness(registration, target));
			}
			return fitness;
		}
	}

	private static final class Stage<T> {
		private final List<StageCandidate<T>> candidates;
		private final long startDepth;
		private final long endDepth;
		private final int bandCount;
		private final boolean climateEntryBand;

		private Stage(
			List<StageCandidate<T>> candidates,
			float startDepth,
			float endDepth,
			int bandCount,
			boolean climateEntryBand
		) {
			this.candidates = List.copyOf(candidates);
			this.startDepth = Climate.quantizeCoord(startDepth);
			this.endDepth = Climate.quantizeCoord(endDepth);
			this.bandCount = bandCount;
			this.climateEntryBand = climateEntryBand;
		}

		private T findValue(Climate.TargetPoint target) {
			int band = this.band(target.depth());
			if (this.climateEntryBand && band == 0) {
				return this.findEntryValue(target);
			}

			int rotationBand = band - (this.climateEntryBand ? 1 : 0);
			int candidate = Math.floorMod(rotationBand + phase(target.weirdness(), this.candidates.size()), this.candidates.size());
			return this.candidates.get(candidate).value();
		}

		private int band(long depth) {
			if (depth <= this.startDepth) {
				return 0;
			}
			if (depth >= this.endDepth) {
				return this.bandCount - 1;
			}
			return (int) ((depth - this.startDepth) * this.bandCount / (this.endDepth - this.startDepth));
		}

		private T findEntryValue(Climate.TargetPoint target) {
			long bestFitness = Long.MAX_VALUE;
			int tieCount = 0;
			for (StageCandidate<T> candidate : this.candidates) {
				long fitness = candidate.fitness(target);
				if (fitness < bestFitness) {
					bestFitness = fitness;
					tieCount = 1;
				} else if (fitness == bestFitness) {
					tieCount++;
				}
			}

			int tie = phase(target.weirdness(), tieCount);
			for (StageCandidate<T> candidate : this.candidates) {
				if (candidate.fitness(target) == bestFitness && tie-- == 0) {
					return candidate.value();
				}
			}
			throw new IllegalStateException("No underground entry candidate");
		}
	}

	public static final class Layout<T> {
		private final Climate.ParameterList<T> original;
		private final Stage<T> shallowStage;
		private final Stage<T> deepStage;
		private final long bandingStart;
		private final long deepStart;

		private Layout(
			Climate.ParameterList<T> original,
			Stage<T> shallowStage,
			Stage<T> deepStage,
			long bandingStart,
			long deepStart
		) {
			this.original = original;
			this.shallowStage = shallowStage;
			this.deepStage = deepStage;
			this.bandingStart = bandingStart;
			this.deepStart = deepStart;
		}

		private static <T> Layout<T> unmodified(Climate.ParameterList<T> original) {
			return new Layout<>(original, null, null, Long.MAX_VALUE, Long.MAX_VALUE);
		}

		public long bandingStart() {
			return this.bandingStart;
		}

		public boolean appliesAt(Climate.TargetPoint target) {
			return target.depth() >= this.bandingStart;
		}

		public T findValue(Climate.TargetPoint target) {
			if (!this.appliesAt(target)) {
				return this.original.findValue(target);
			}
			if (target.depth() < this.deepStart && this.shallowStage != null) {
				return this.shallowStage.findValue(target);
			}
			if (this.deepStage != null) {
				return this.deepStage.findValue(target);
			}
			return this.shallowStage.findValue(target);
		}
	}
}
