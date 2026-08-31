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

/**
 * Removes dynamically registered underground candidates from preview-only biome selection.
 * Classification is driven by climate registration shape and tags supplied by the caller.
 */
public final class SurfaceBiomeFilter<T> {
	private final Climate.ParameterList<T> surfaceParameters;
	private final Set<T> undergroundOnly;
	private final Predicate<T> undergroundTag;
	private final T finalFallback;

	private SurfaceBiomeFilter(
		Climate.ParameterList<T> surfaceParameters,
		Set<T> undergroundOnly,
		Predicate<T> undergroundTag,
		T finalFallback
	) {
		this.surfaceParameters = surfaceParameters;
		this.undergroundOnly = undergroundOnly;
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
		Map<T, Roles> roles = new HashMap<>();
		for (Pair<Climate.ParameterPoint, T> entry : entries) {
			UndergroundBiomeBanding.CandidateRole role = classifier.apply(entry.getFirst(), entry.getSecond());
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
		for (Pair<Climate.ParameterPoint, T> entry : entries) {
			UndergroundBiomeBanding.CandidateRole role = classifier.apply(entry.getFirst(), entry.getSecond());
			if (!undergroundOnly.contains(entry.getSecond())
				&& role != UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE
				&& role != UndergroundBiomeBanding.CandidateRole.DEEP_CAVE) {
				surfaceEntries.add(entry);
			}
		}
		Climate.ParameterList<T> surfaceParameters = surfaceEntries.isEmpty()
			? null
			: new Climate.ParameterList<>(surfaceEntries);
		return new SurfaceBiomeFilter<>(surfaceParameters, undergroundOnly, undergroundTag, finalFallback);
	}

	public boolean hasSurfaceCandidate() {
		return this.surfaceParameters != null;
	}

	public Optional<Climate.ParameterList<T>> surfaceParameters() {
		return Optional.ofNullable(this.surfaceParameters);
	}

	public boolean isUnderground(T value) {
		return value != null && (this.undergroundTag.test(value) || this.undergroundOnly.contains(value));
	}

	public T resolve(Climate.TargetPoint target, T selected) {
		if (!this.isUnderground(selected)) {
			return selected;
		}
		if (this.surfaceParameters != null) {
			T fallback = this.surfaceParameters.findValue(target);
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
