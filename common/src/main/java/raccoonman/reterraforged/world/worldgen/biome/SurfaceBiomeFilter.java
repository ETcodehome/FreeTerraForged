package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;

public final class SurfaceBiomeFilter<T> {
	private final List<Pair<Climate.ParameterPoint, T>> surfaceEntries;
	private final Climate.ParameterList<T> retainedSurfaceParameters;
	private final Set<T> undergroundOnly;
	private final Set<T> knownCandidates;
	private final Predicate<T> undergroundTag;
	private final T finalFallback;
	private volatile Climate.ParameterList<T> materializedSurfaceParameters;

	private SurfaceBiomeFilter(
		List<Pair<Climate.ParameterPoint, T>> surfaceEntries,
		Climate.ParameterList<T> retainedSurfaceParameters,
		Set<T> undergroundOnly,
		Set<T> knownCandidates,
		Predicate<T> undergroundTag,
		T finalFallback
	) {
		this.surfaceEntries = List.copyOf(surfaceEntries);
		this.retainedSurfaceParameters = retainedSurfaceParameters;
		this.undergroundOnly = undergroundOnly;
		this.knownCandidates = knownCandidates;
		this.undergroundTag = undergroundTag;
		this.finalFallback = finalFallback;
	}

	public static <T> SurfaceBiomeFilter<T> create(
		List<Pair<Climate.ParameterPoint, T>> entries,
		BiFunction<Climate.ParameterPoint, T, UndergroundBiomeBanding.CandidateRole> classifier,
		Predicate<T> undergroundTag,
		Collection<T> additionalUndergroundCandidates,
		T finalFallback
	) {
		return create(
			new Climate.ParameterList<>(entries), classifier, undergroundTag,
			additionalUndergroundCandidates, finalFallback
		);
	}

	public static <T> SurfaceBiomeFilter<T> create(
		Climate.ParameterList<T> source,
		BiFunction<Climate.ParameterPoint, T, UndergroundBiomeBanding.CandidateRole> classifier,
		Predicate<T> undergroundTag,
		Collection<T> additionalUndergroundCandidates,
		T finalFallback
	) {
		List<Pair<Climate.ParameterPoint, T>> entries = source.values();
		Map<T, Roles> roles = new HashMap<>();
		UndergroundBiomeBanding.CandidateRole[] classified =
			new UndergroundBiomeBanding.CandidateRole[entries.size()];
		for (int index = 0; index < entries.size(); index++) {
			Pair<Climate.ParameterPoint, T> entry = entries.get(index);
			UndergroundBiomeBanding.CandidateRole role = classifier.apply(entry.getFirst(), entry.getSecond());
			classified[index] = role;
			roles.computeIfAbsent(entry.getSecond(), ignored -> new Roles()).accept(role);
		}
		for (T candidate : additionalUndergroundCandidates) {
			roles.computeIfAbsent(candidate, ignored -> new Roles()).underground = true;
		}

		Set<T> undergroundOnly = new HashSet<>();
		for (Map.Entry<T, Roles> entry : roles.entrySet()) {
			if (undergroundTag.test(entry.getKey())
				|| (entry.getValue().underground && !entry.getValue().surface)) {
				undergroundOnly.add(entry.getKey());
			}
		}

		List<Pair<Climate.ParameterPoint, T>> surfaceEntries = new ArrayList<>();
		for (int index = 0; index < entries.size(); index++) {
			Pair<Climate.ParameterPoint, T> entry = entries.get(index);
			UndergroundBiomeBanding.CandidateRole role = classified[index];
			if (!undergroundOnly.contains(entry.getSecond())
				&& role != UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE
				&& role != UndergroundBiomeBanding.CandidateRole.DEEP_CAVE) {
				surfaceEntries.add(entry);
			}
		}
		Climate.ParameterList<T> retained = !surfaceEntries.isEmpty()
			&& surfaceEntries.size() == entries.size()
			? source
			: null;
		return new SurfaceBiomeFilter<>(
			surfaceEntries, retained, Set.copyOf(undergroundOnly), Set.copyOf(roles.keySet()),
			undergroundTag, finalFallback
		);
	}

	public boolean hasSurfaceCandidate() {
		return !this.surfaceEntries.isEmpty();
	}

	public Optional<Climate.ParameterList<T>> surfaceParameters() {
		if (this.surfaceEntries.isEmpty()) {
			return Optional.empty();
		}
		if (this.retainedSurfaceParameters != null) {
			return Optional.of(this.retainedSurfaceParameters);
		}
		Climate.ParameterList<T> parameters = this.materializedSurfaceParameters;
		if (parameters == null) {
			synchronized (this) {
				parameters = this.materializedSurfaceParameters;
				if (parameters == null) {
					parameters = new Climate.ParameterList<>(this.surfaceEntries);
					this.materializedSurfaceParameters = parameters;
				}
			}
		}
		return Optional.of(parameters);
	}

	public boolean isUnderground(T value) {
		return value != null && (this.undergroundOnly.contains(value)
			|| (!this.knownCandidates.contains(value) && this.undergroundTag.test(value)));
	}

	public T resolve(Climate.TargetPoint target, T selected) {
		if (!this.isUnderground(selected)) {
			return selected;
		}
		Optional<Climate.ParameterList<T>> parameters = this.surfaceParameters();
		if (parameters.isPresent()) {
			T fallback = parameters.orElseThrow().findValue(target);
			if (!this.isUnderground(fallback)) {
				return fallback;
			}
		}
		return this.finalFallback;
	}

	private static final class Roles {
		private boolean surface;
		private boolean underground;

		private void accept(UndergroundBiomeBanding.CandidateRole role) {
			if (role == UndergroundBiomeBanding.CandidateRole.SURFACE) {
				this.surface = true;
			} else if (role == UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE
				|| role == UndergroundBiomeBanding.CandidateRole.DEEP_CAVE) {
				this.underground = true;
			}
		}
	}

}
