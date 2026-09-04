package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

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
			return immutableCopyAllowingNull(regionalEntries);
		}
		List<Pair<Climate.ParameterPoint, T>> composed = new ArrayList<>(regionalEntries.size() + globalAdditions.size());
		composed.addAll(regionalEntries);
		composed.addAll(globalAdditions);
		return Collections.unmodifiableList(composed);
	}

	public static <T> Snapshot<T> snapshot(
		List<Pair<Climate.ParameterPoint, T>> base,
		List<Pair<Climate.ParameterPoint, T>> current,
		List<List<Pair<Climate.ParameterPoint, T>>> regions,
		Predicate<T> excludedValue
	) {
		if (base == null || current == null || regions == null || excludedValue == null) {
			return Snapshot.unusable("missing_capture_input");
		}

		List<Pair<Climate.ParameterPoint, T>> globalAdditions = additions(base, current);
		List<List<Pair<Climate.ParameterPoint, T>>> effectiveRegions = new ArrayList<>(regions.size());
		Set<Integer> invalidRegions = new LinkedHashSet<>();
		Map<Pair<Climate.ParameterPoint, T>, MutableRegistration<T>> canonical = new LinkedHashMap<>();
		int invalidEntryCount = 0;
		int excludedEntryCount = 0;

		for (int regionIndex = 0; regionIndex < regions.size(); regionIndex++) {
			List<Pair<Climate.ParameterPoint, T>> captured = regions.get(regionIndex);
			if (captured == null) {
				invalidRegions.add(regionIndex);
				effectiveRegions.add(List.of());
				continue;
			}

			List<Pair<Climate.ParameterPoint, T>> effective = regionIndex == 0
				? immutableCopyAllowingNull(current)
				: append(captured, globalAdditions);
			effectiveRegions.add(effective);
			for (Pair<Climate.ParameterPoint, T> entry : effective) {
				if (entry == null || entry.getFirst() == null || entry.getSecond() == null) {
					invalidEntryCount++;
					continue;
			}
				if (excludedValue.test(entry.getSecond())) {
					excludedEntryCount++;
					continue;
				}
				canonical.computeIfAbsent(entry, MutableRegistration::new).regions.add(regionIndex);
			}
		}

		for (Pair<Climate.ParameterPoint, T> addition : globalAdditions) {
			MutableRegistration<T> registration = canonical.get(addition);
			if (registration != null) {
				registration.lateGlobal = true;
			}
		}

		List<Registration<T>> registrations = canonical.values().stream()
			.map(MutableRegistration::freeze)
			.toList();
		List<Pair<Climate.ParameterPoint, T>> canonicalEntries = registrations.stream()
			.map(Registration::entry)
			.toList();
		String fallbackReason = canonicalEntries.isEmpty() ? "no_compatible_entries" : null;
		return new Snapshot<>(
			List.copyOf(effectiveRegions),
			canonicalEntries,
			registrations,
			globalAdditions,
			Set.copyOf(invalidRegions),
			invalidEntryCount,
			excludedEntryCount,
			fallbackReason
		);
	}

	public static <T> CandidateOverlay<T> overlayUndergroundCandidates(
		List<Pair<Climate.ParameterPoint, T>> defaultEntries,
		List<Pair<Climate.ParameterPoint, T>> regionalEntries,
		List<Pair<Climate.ParameterPoint, T>> globalAdditions,
		BiFunction<Climate.ParameterPoint, T, UndergroundBiomeBanding.CandidateRole> classifier,
		Predicate<T> excludedValue
	) {
		if (defaultEntries == null || regionalEntries == null || globalAdditions == null
			|| classifier == null || excludedValue == null) {
			return CandidateOverlay.unusable("missing_candidate_overlay_input");
		}

		Map<Climate.ParameterPoint, List<Pair<Climate.ParameterPoint, T>>> slots = new LinkedHashMap<>();
		MutableOverlayCounts counts = new MutableOverlayCounts();
		collectCandidateGroups(defaultEntries, classifier, excludedValue, counts).forEach(slots::put);

		Map<Climate.ParameterPoint, List<Pair<Climate.ParameterPoint, T>>> regional = collectCandidateGroups(
			regionalEntries, classifier, excludedValue, counts
		);
		for (Map.Entry<Climate.ParameterPoint, List<Pair<Climate.ParameterPoint, T>>> entry : regional.entrySet()) {
			if (slots.put(entry.getKey(), entry.getValue()) != null) {
				counts.replacedDefaultSlotCount++;
			}
		}

		Map<Climate.ParameterPoint, List<Pair<Climate.ParameterPoint, T>>> globals = collectCandidateGroups(
			globalAdditions, classifier, excludedValue, counts
		);
		for (Map.Entry<Climate.ParameterPoint, List<Pair<Climate.ParameterPoint, T>>> entry : globals.entrySet()) {
			List<Pair<Climate.ParameterPoint, T>> slot = slots.computeIfAbsent(entry.getKey(), key -> new ArrayList<>());
			for (Pair<Climate.ParameterPoint, T> addition : entry.getValue()) {
				if (!slot.contains(addition)) {
					slot.add(addition);
				}
			}
		}

		List<Pair<Climate.ParameterPoint, T>> entries = slots.values().stream()
			.flatMap(List::stream)
			.toList();
		return new CandidateOverlay<>(
			entries,
			counts.replacedDefaultSlotCount,
			counts.invalidEntryCount,
			counts.classificationFailureCount,
			null
		);
	}

	private static <T> Map<Climate.ParameterPoint, List<Pair<Climate.ParameterPoint, T>>> collectCandidateGroups(
		List<Pair<Climate.ParameterPoint, T>> entries,
		BiFunction<Climate.ParameterPoint, T, UndergroundBiomeBanding.CandidateRole> classifier,
		Predicate<T> excludedValue,
		MutableOverlayCounts counts
	) {
		Map<Climate.ParameterPoint, List<Pair<Climate.ParameterPoint, T>>> groups = new LinkedHashMap<>();
		for (Pair<Climate.ParameterPoint, T> entry : entries) {
			if (entry == null || entry.getFirst() == null || entry.getSecond() == null) {
				counts.invalidEntryCount++;
				continue;
			}
			if (excludedValue.test(entry.getSecond())) {
				continue;
			}

			UndergroundBiomeBanding.CandidateRole role;
			try {
				role = classifier.apply(entry.getFirst(), entry.getSecond());
			} catch (RuntimeException exception) {
				counts.classificationFailureCount++;
				continue;
			}
			if (role != UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE
				&& role != UndergroundBiomeBanding.CandidateRole.DEEP_CAVE) {
				continue;
			}
			groups.computeIfAbsent(entry.getFirst(), key -> new ArrayList<>()).add(entry);
		}
		return groups;
	}

	private static <T> List<T> immutableCopyAllowingNull(List<T> values) {
		return Collections.unmodifiableList(new ArrayList<>(values));
	}

	public record Registration<T>(
		Pair<Climate.ParameterPoint, T> entry,
		Set<Integer> sourceRegions,
		boolean lateGlobal
	) {
	}

	public record CandidateOverlay<T>(
		List<Pair<Climate.ParameterPoint, T>> entries,
		int replacedDefaultSlotCount,
		int invalidEntryCount,
		int classificationFailureCount,
		String fallbackReason
	) {
		private static <T> CandidateOverlay<T> unusable(String reason) {
			return new CandidateOverlay<>(List.of(), 0, 0, 0, reason);
		}

		public boolean usable() {
			return this.fallbackReason == null;
		}
	}

	public record Snapshot<T>(
		List<List<Pair<Climate.ParameterPoint, T>>> effectiveRegions,
		List<Pair<Climate.ParameterPoint, T>> canonicalEntries,
		List<Registration<T>> registrations,
		List<Pair<Climate.ParameterPoint, T>> globalAdditions,
		Set<Integer> invalidRegions,
		int invalidEntryCount,
		int excludedEntryCount,
		String fallbackReason
	) {
		private static <T> Snapshot<T> unusable(String reason) {
			return new Snapshot<>(List.of(), List.of(), List.of(), List.of(), Set.of(), 0, 0, reason);
		}

		public boolean usable() {
			return this.fallbackReason == null;
		}

		public boolean usableForRegion(int regionIndex) {
			return this.usable()
				&& regionIndex >= 0
				&& regionIndex < this.effectiveRegions.size()
				&& !this.invalidRegions.contains(regionIndex);
		}

		public int duplicateEntryCount() {
			int validSourceEntries = this.effectiveRegions.stream().mapToInt(List::size).sum()
				- this.invalidEntryCount - this.excludedEntryCount;
			return Math.max(0, validSourceEntries - this.canonicalEntries.size());
		}

		public int alternativePointCount() {
			Map<Climate.ParameterPoint, Set<T>> valuesByPoint = new HashMap<>();
			for (Pair<Climate.ParameterPoint, T> entry : this.canonicalEntries) {
				valuesByPoint.computeIfAbsent(entry.getFirst(), key -> new LinkedHashSet<>()).add(entry.getSecond());
			}
			return (int) valuesByPoint.values().stream().filter(values -> values.size() > 1).count();
		}
	}

	private static final class MutableRegistration<T> {
		private final Pair<Climate.ParameterPoint, T> entry;
		private final Set<Integer> regions = new LinkedHashSet<>();
		private boolean lateGlobal;

		private MutableRegistration(Pair<Climate.ParameterPoint, T> entry) {
			this.entry = entry;
		}

		private Registration<T> freeze() {
			return new Registration<>(this.entry, Set.copyOf(this.regions), this.lateGlobal);
		}
	}

	private static final class MutableOverlayCounts {
		private int replacedDefaultSlotCount;
		private int invalidEntryCount;
		private int classificationFailureCount;
	}
}
