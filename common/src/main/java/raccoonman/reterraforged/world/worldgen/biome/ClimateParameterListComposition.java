package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;

/**
 * Composes parameter points contributed after a positional biome system captured its base tree.
 */
public final class ClimateParameterListComposition {
	private ClimateParameterListComposition() {
	}

	public static <T> List<Pair<Climate.ParameterPoint, T>> additions(
		List<Pair<Climate.ParameterPoint, T>> base,
		List<Pair<Climate.ParameterPoint, T>> current
	) {
		Map<Pair<Climate.ParameterPoint, T>, Integer> remainingBaseOccurrences = new HashMap<>();
		for (Pair<Climate.ParameterPoint, T> entry : base) {
			remainingBaseOccurrences.merge(entry, 1, Integer::sum);
		}

		List<Pair<Climate.ParameterPoint, T>> additions = new ArrayList<>();
		for (Pair<Climate.ParameterPoint, T> entry : current) {
			Integer occurrences = remainingBaseOccurrences.get(entry);
			if (occurrences == null || occurrences == 0) {
				additions.add(entry);
			} else if (occurrences == 1) {
				remainingBaseOccurrences.remove(entry);
			} else {
				remainingBaseOccurrences.put(entry, occurrences - 1);
			}
		}
		return List.copyOf(additions);
	}

	public static <T> List<Pair<Climate.ParameterPoint, T>> append(
		List<Pair<Climate.ParameterPoint, T>> regionalEntries,
		List<Pair<Climate.ParameterPoint, T>> globalAdditions
	) {
		if (globalAdditions.isEmpty()) {
			return regionalEntries;
		}
		List<Pair<Climate.ParameterPoint, T>> composed = new ArrayList<>(regionalEntries.size() + globalAdditions.size());
		composed.addAll(regionalEntries);
		composed.addAll(globalAdditions);
		return List.copyOf(composed);
	}
}
