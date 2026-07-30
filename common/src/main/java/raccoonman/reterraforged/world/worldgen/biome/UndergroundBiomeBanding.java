package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;

/**
 * Redistributes convention-following underground biome points through the usable world depth.
 *
 * <p>A terrain-relative surface buffer keeps cave biomes away from the surface. Shallow cave
 * candidates are then banded down to vanilla's bottom-biome threshold; below that threshold,
 * shallow and bottom candidates are banded together. Weirdness partitions provide horizontally
 * coherent candidate rotation, while nonconforming mod registrations remain unchanged.</p>
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
	private static final int MAX_TERRAIN_HEIGHT = 256;
	private static final int MAX_BAND_COUNT = 32;
	static final int MAX_SURFACE_BUFFER_BLOCKS = 24;
	private static final long BAND_FALLBACK_OFFSET = 0L;
	private static final float DEFAULT_DYNAMIC_STAGE_BLOCKS =
		DEFAULT_WORLD_DEPTH + MAX_TERRAIN_HEIGHT - VANILLA_BOTTOM_DEPTH / DEPTH_UNITS_PER_BLOCK;

	private static final Climate.Parameter VANILLA_UNDERGROUND_DEPTH = Climate.Parameter.span(VANILLA_UNDERGROUND_DEPTH_START, VANILLA_UNDERGROUND_DEPTH_END);
	private static final Climate.Parameter VANILLA_BOTTOM = Climate.Parameter.point(VANILLA_BOTTOM_DEPTH);
	private static final Climate.Parameter VANILLA_FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);

	private UndergroundBiomeBanding() {
	}

	public static <T> Layout<T> apply(Preset preset, List<Pair<Climate.ParameterPoint, T>> entries) {
		Map<T, Candidate<T>> candidates = new LinkedHashMap<>();
		List<Pair<Climate.ParameterPoint, T>> retained = new ArrayList<>(entries.size());

		for (Pair<Climate.ParameterPoint, T> entry : entries) {
			Climate.ParameterPoint point = entry.getFirst();
			if (isVanillaConventionUnderground(point)) {
				if (point.depth().equals(VANILLA_UNDERGROUND_DEPTH)) {
					candidates.computeIfAbsent(entry.getSecond(), Candidate::new).shallow = true;
				} else {
					candidates.computeIfAbsent(entry.getSecond(), Candidate::new).bottom = true;
				}
			} else {
				retained.add(entry);
			}
		}

		if (candidates.size() < 2) {
			return Layout.unmodified(entries);
		}

		float endDepth = endDepth(preset);
		List<T> shallowValues = candidates.values().stream()
			.filter(candidate -> candidate.shallow)
			.map(candidate -> candidate.value)
			.toList();
		List<T> deepValues = List.copyOf(candidates.keySet());
		long bottomCandidateCount = candidates.values().stream()
			.filter(candidate -> candidate.bottom)
			.count();
		float bandingStart = bandingStart(preset, shallowValues.size());
		List<Pair<Climate.ParameterPoint, T>> result = new ArrayList<>(
			retained.size() + MAX_BAND_COUNT * (shallowValues.size() + deepValues.size())
		);
		result.addAll(retained);

		float shallowEnd = Math.min(SHALLOW_STAGE_END, endDepth);
		int shallowBands = addStage(preset, result, shallowValues, bandingStart, shallowEnd);
		int deepBands = addStage(preset, result, deepValues, VANILLA_BOTTOM_DEPTH, endDepth);
		if (shallowBands == 0 && deepBands == 0) {
			return Layout.unmodified(entries);
		}

		RTFCommon.LOGGER.info(
			"Applied staged underground biome banding: {} shallow candidates / {} bands, {} total deep-stage candidates ({} bottom-role) / {} bands, surface buffer {} blocks, depth {}..{}, {} -> {} parameter points",
			shallowValues.size(),
			shallowBands,
			candidates.size(),
			bottomCandidateCount,
			deepBands,
			(bandingStart - SURFACE_DEPTH) / DEPTH_UNITS_PER_BLOCK,
			bandingStart,
			endDepth,
			entries.size(),
			result.size()
		);
		return new Layout<>(
			new Climate.ParameterList<>(List.copyOf(result)),
			Climate.quantizeCoord(bandingStart)
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
		// Square roots keep extreme depth and Biome Size settings useful without exploding the R-tree.
		float verticalScale = (float) Math.sqrt(Math.max(1.0F, stageBlocks) / DEFAULT_DYNAMIC_STAGE_BLOCKS);
		float biomeScale = (float) Math.sqrt((float) DEFAULT_BIOME_SIZE / Math.max(1, preset.climate().biomeShape.biomeSize));
		return Math.clamp(Math.round(candidateCount * verticalScale * biomeScale), 1, MAX_BAND_COUNT);
	}

	static float endDepth(Preset preset) {
		WorldSettings.Properties properties = preset.world().properties;
		int terrainHeight = Math.min(properties.worldHeight, MAX_TERRAIN_HEIGHT);
		return Math.max(SURFACE_DEPTH + DEPTH_UNITS_PER_BLOCK, (properties.worldDepth + terrainHeight) * DEPTH_UNITS_PER_BLOCK);
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

	private static <T> int addStage(
		Preset preset,
		List<Pair<Climate.ParameterPoint, T>> result,
		List<T> candidateValues,
		float startDepth,
		float endDepth
	) {
		int bandCount = bandCount(preset, candidateValues.size(), startDepth, endDepth);
		if (bandCount == 0) {
			return 0;
		}
		float bandWidth = (endDepth - startDepth) / bandCount;
		int regimeCount = candidateValues.size();
		for (int regime = 0; regime < regimeCount; regime++) {
			Climate.Parameter regimeRange = partition(VANILLA_FULL_RANGE, regime, regimeCount);
			for (int band = 0; band < bandCount; band++) {
				float bandStart = startDepth + bandWidth * band;
				float bandEnd = band == bandCount - 1 ? endDepth : startDepth + bandWidth * (band + 1);
				T value = candidateValues.get(Math.floorMod(band + regime, regimeCount));
				result.add(Pair.of(
					new Climate.ParameterPoint(
						VANILLA_FULL_RANGE,
						VANILLA_FULL_RANGE,
						VANILLA_FULL_RANGE,
						VANILLA_FULL_RANGE,
						Climate.Parameter.span(bandStart, bandEnd),
						regimeRange,
						BAND_FALLBACK_OFFSET
					),
					value
				));
			}
		}
		return bandCount;
	}

	private static Climate.Parameter partition(Climate.Parameter range, int index, int count) {
		long width = range.max() - range.min();
		long min = range.min() + width * index / count;
		long max = index == count - 1 ? range.max() : range.min() + width * (index + 1) / count;
		return new Climate.Parameter(min, max);
	}

	private static final class Candidate<T> {
		private final T value;
		private boolean shallow;
		private boolean bottom;

		private Candidate(T value) {
			this.value = value;
		}
	}

	/**
	 * Uses the untouched parameter list above the local surface buffer and a fully covered staged
	 * parameter list below it. The hard switch keeps nearest-neighbor entries from bleeding upward.
	 */
	public record Layout<T>(
		Climate.ParameterList<T> parameters,
		long bandingStart
	) {
		private static <T> Layout<T> unmodified(List<Pair<Climate.ParameterPoint, T>> entries) {
			return new Layout<>(
				new Climate.ParameterList<>(entries),
				Long.MAX_VALUE
			);
		}

		public boolean appliesAt(Climate.TargetPoint target) {
			return target.depth() >= this.bandingStart;
		}

		public T findValue(Climate.TargetPoint target) {
			return this.parameters.findValue(target);
		}
	}
}
