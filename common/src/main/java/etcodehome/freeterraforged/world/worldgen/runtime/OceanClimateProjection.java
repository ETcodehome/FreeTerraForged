package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;

record OceanClimateProjection(long targetMax) {
	private static final long SOURCE_MIN = Climate.quantizeCoord(Continentalness.OCEAN.min());
	private static final long SOURCE_MAX = Climate.quantizeCoord(Continentalness.OCEAN.max());
	private static final OceanClimateProjection IDENTITY = new OceanClimateProjection(SOURCE_MAX);

	static OceanClimateProjection from(Climate.ParameterList<Holder<Biome>> candidates) {
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = candidates.values();
		long edge = Long.MIN_VALUE;
		for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : entries) {
			Climate.ParameterPoint point = entry.getFirst();
			long min = point.continentalness().min();
			long max = point.continentalness().max();
			if (!surface(point) || !entry.getSecond().is(BiomeTags.IS_OCEAN)
				|| min > SOURCE_MIN || max <= SOURCE_MIN || max >= SOURCE_MAX) {
				continue;
			}
			if (edge != Long.MIN_VALUE && edge != max) {
				return IDENTITY;
			}
			edge = max;
		}
		if (edge == Long.MIN_VALUE) {
			return IDENTITY;
		}
		boolean adjoiningLand = false;
		for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : entries) {
			Climate.ParameterPoint point = entry.getFirst();
			if (!surface(point) || entry.getSecond().is(BiomeTags.IS_OCEAN)) {
				continue;
			}
			long min = point.continentalness().min();
			long max = point.continentalness().max();
			if (min < edge && max > SOURCE_MIN) {
				return IDENTITY;
			}
			adjoiningLand |= min == edge;
		}
		return adjoiningLand ? new OceanClimateProjection(edge - 1) : IDENTITY;
	}

	Climate.TargetPoint apply(Climate.TargetPoint target) {
		long original = target.continentalness();
		if (this.targetMax == SOURCE_MAX || original <= SOURCE_MIN || original >= SOURCE_MAX) {
			return target;
		}
		long projected = SOURCE_MIN + (original - SOURCE_MIN) * (this.targetMax - SOURCE_MIN)
			/ (SOURCE_MAX - SOURCE_MIN);
		return new Climate.TargetPoint(
			target.temperature(), target.humidity(), projected, target.erosion(),
			target.depth(), target.weirdness()
		);
	}

	private static boolean surface(Climate.ParameterPoint point) {
		return point.depth().min() <= 0 && point.depth().max() >= 0;
	}
}
