package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;

/**
 * Redistributes convention-following underground biome points through the usable world depth.
 *
 * <p>The original shallow cave points remain untouched. Dynamic bands begin at vanilla's bottom
 * depth point, use weirdness partitions for horizontally coherent candidate rotation, and leave
 * nonconforming mod registrations unchanged.</p>
 */
public final class UndergroundBiomeBanding {
	public static final int DEFAULT_BIOME_SIZE = 225;

	private static final float VANILLA_UNDERGROUND_DEPTH_START = 0.2F;
	private static final float VANILLA_UNDERGROUND_DEPTH_END = 0.9F;
	private static final float BANDING_DEPTH_START = 1.1F;
	private static final float DEPTH_UNITS_PER_BLOCK = 1.0F / 128.0F;
	private static final int DEFAULT_WORLD_DEPTH = 64;
	private static final int MAX_TERRAIN_HEIGHT = 256;
	private static final int MAX_BAND_COUNT = 32;
	private static final long BAND_FALLBACK_OFFSET = 0L;

	private static final Climate.Parameter VANILLA_UNDERGROUND_DEPTH = Climate.Parameter.span(VANILLA_UNDERGROUND_DEPTH_START, VANILLA_UNDERGROUND_DEPTH_END);
	private static final Climate.Parameter VANILLA_BOTTOM_DEPTH = Climate.Parameter.point(1.1F);
	private static final Climate.Parameter VANILLA_FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);

	private UndergroundBiomeBanding() {
	}

	public static <T> List<Pair<Climate.ParameterPoint, T>> apply(Preset preset, List<Pair<Climate.ParameterPoint, T>> entries) {
		Map<T, List<Pair<Climate.ParameterPoint, T>>> candidates = new LinkedHashMap<>();
		List<Pair<Climate.ParameterPoint, T>> retained = new ArrayList<>(entries.size());

		for (Pair<Climate.ParameterPoint, T> entry : entries) {
			Climate.ParameterPoint point = entry.getFirst();
			if (isVanillaConventionUnderground(point)) {
				candidates.computeIfAbsent(entry.getSecond(), ignored -> new ArrayList<>()).add(entry);
				if (point.depth().equals(VANILLA_UNDERGROUND_DEPTH)) {
					retained.add(entry);
				}
			} else {
				retained.add(entry);
			}
		}

		if (candidates.size() < 2) {
			return entries;
		}

		int bandCount = bandCount(preset, candidates.size());
		float endDepth = endDepth(preset);
		float bandWidth = (endDepth - BANDING_DEPTH_START) / bandCount;
		List<T> candidateValues = List.copyOf(candidates.keySet());
		int regimeCount = candidateValues.size();
		List<Pair<Climate.ParameterPoint, T>> result = new ArrayList<>(
			retained.size() + entries.size() * Math.max(bandCount, regimeCount)
		);
		result.addAll(retained);

		for (int regime = 0; regime < regimeCount; regime++) {
			Climate.Parameter regimeRange = partition(VANILLA_FULL_RANGE, regime, regimeCount);

			for (int band = 0; band < bandCount; band++) {
				T value = candidateValues.get(Math.floorMod(band + regime, regimeCount));
				Climate.Parameter bandRange = Climate.Parameter.span(
					BANDING_DEPTH_START + bandWidth * band,
					band == bandCount - 1 ? endDepth : BANDING_DEPTH_START + bandWidth * (band + 1)
				);

				for (Pair<Climate.ParameterPoint, T> source : candidates.get(value)) {
					Climate.ParameterPoint point = source.getFirst();
					result.add(Pair.of(
						withDepthAndWeirdness(point, bandRange, regimeRange),
						source.getSecond()
					));
				}
				if (band > 0) {
					result.add(Pair.of(
						new Climate.ParameterPoint(
							VANILLA_FULL_RANGE,
							VANILLA_FULL_RANGE,
							VANILLA_FULL_RANGE,
							VANILLA_FULL_RANGE,
							bandRange,
							regimeRange,
							BAND_FALLBACK_OFFSET
						),
						value
					));
				}
			}
		}

		RTFCommon.LOGGER.info(
			"Applied underground biome banding: {} candidate biomes, {} bands, {} regimes, depth {}..{}, {} -> {} parameter points",
			candidates.size(),
			bandCount,
			regimeCount,
			BANDING_DEPTH_START,
			endDepth,
			entries.size(),
			result.size()
		);
		return List.copyOf(result);
	}

	public static double undergroundNoiseScale(Preset preset) {
		int biomeSize = Math.max(1, preset.climate().biomeShape.biomeSize);
		return 0.25D * DEFAULT_BIOME_SIZE / biomeSize;
	}

	public static boolean appliesAt(Climate.TargetPoint target) {
		return target.depth() >= VANILLA_BOTTOM_DEPTH.min();
	}

	static int bandCount(Preset preset, int candidateCount) {
		WorldSettings.Properties properties = preset.world().properties;
		int terrainHeight = Math.min(properties.worldHeight, MAX_TERRAIN_HEIGHT);
		float usableHeight = properties.worldDepth + terrainHeight - BANDING_DEPTH_START / DEPTH_UNITS_PER_BLOCK;
		float defaultUsableHeight = DEFAULT_WORLD_DEPTH + MAX_TERRAIN_HEIGHT - BANDING_DEPTH_START / DEPTH_UNITS_PER_BLOCK;
		// Square roots keep extreme depth and Biome Size settings useful without exploding the R-tree.
		float verticalScale = (float) Math.sqrt(Math.max(1.0F, usableHeight) / defaultUsableHeight);
		float biomeScale = (float) Math.sqrt((float) DEFAULT_BIOME_SIZE / Math.max(1, preset.climate().biomeShape.biomeSize));
		return Math.clamp(Math.round(candidateCount * verticalScale * biomeScale), 1, MAX_BAND_COUNT);
	}

	static float endDepth(Preset preset) {
		WorldSettings.Properties properties = preset.world().properties;
		int terrainHeight = Math.min(properties.worldHeight, MAX_TERRAIN_HEIGHT);
		return Math.max(BANDING_DEPTH_START + DEPTH_UNITS_PER_BLOCK, (properties.worldDepth + terrainHeight) * DEPTH_UNITS_PER_BLOCK);
	}

	private static boolean isVanillaConventionUnderground(Climate.ParameterPoint point) {
		return point.weirdness().equals(VANILLA_FULL_RANGE)
			&& (point.depth().equals(VANILLA_UNDERGROUND_DEPTH) || point.depth().equals(VANILLA_BOTTOM_DEPTH));
	}

	private static Climate.ParameterPoint withDepthAndWeirdness(
		Climate.ParameterPoint point,
		Climate.Parameter depth,
		Climate.Parameter weirdness
	) {
		return new Climate.ParameterPoint(
			point.temperature(),
			point.humidity(),
			point.continentalness(),
			point.erosion(),
			depth,
			weirdness,
			point.offset()
		);
	}

	private static Climate.Parameter partition(Climate.Parameter range, int index, int count) {
		long width = range.max() - range.min();
		long min = range.min() + width * index / count;
		long max = index == count - 1 ? range.max() : range.min() + width * (index + 1) / count;
		return new Climate.Parameter(min, max);
	}
}
