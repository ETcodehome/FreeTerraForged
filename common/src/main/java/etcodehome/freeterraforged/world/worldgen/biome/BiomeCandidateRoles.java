package etcodehome.freeterraforged.world.worldgen.biome;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;

/**
 * Aggregates registration-level climate roles into semantic roles for each biome candidate.
 *
 * <p>An ordinary surface biome may also have underground-depth registrations so it can provide the
 * background, or "null", result where a named cave biome does not win. Such registrations do not
 * make that biome underground-only. A candidate is underground-only when a supported cave tag says
 * so, or when it has underground registrations and no surface registration.
 */
public final class BiomeCandidateRoles<T> {
	private final UndergroundBiomeBanding.CandidateRole[] registrations;
	private final Map<T, Roles> candidates;

	private BiomeCandidateRoles(
		UndergroundBiomeBanding.CandidateRole[] registrations,
		Map<T, Roles> candidates
	) {
		this.registrations = registrations;
		this.candidates = Collections.unmodifiableMap(candidates);
	}

	public static <T> BiomeCandidateRoles<T> compile(
		Climate.ParameterList<T> source,
		BiFunction<Climate.ParameterPoint, T, UndergroundBiomeBanding.CandidateRole> classifier,
		Predicate<T> undergroundTag,
		Collection<T> additionalUndergroundCandidates
	) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(classifier, "classifier");
		Objects.requireNonNull(undergroundTag, "undergroundTag");
		Objects.requireNonNull(additionalUndergroundCandidates, "additionalUndergroundCandidates");
		UndergroundBiomeBanding.CandidateRole[] registrations =
			new UndergroundBiomeBanding.CandidateRole[source.values().size()];
		Map<T, MutableRoles> mutable = new HashMap<>();
		for (int index = 0; index < source.values().size(); index++) {
			Pair<Climate.ParameterPoint, T> entry = source.values().get(index);
			UndergroundBiomeBanding.CandidateRole role = Objects.requireNonNull(
				classifier.apply(entry.getFirst(), entry.getSecond()),
				"candidate role"
			);
			registrations[index] = role;
			MutableRoles roles = mutable.computeIfAbsent(entry.getSecond(), ignored -> new MutableRoles());
			roles.accept(role);
		}
		for (T candidate : additionalUndergroundCandidates) {
			MutableRoles roles = mutable.computeIfAbsent(candidate, ignored -> new MutableRoles());
			roles.additionalUnderground = true;
		}
		Map<T, Roles> candidates = new HashMap<>();
		mutable.forEach((candidate, roles) -> {
			roles.undergroundTagged = undergroundTag.test(candidate);
			candidates.put(candidate, roles.freeze());
		});
		return new BiomeCandidateRoles<>(registrations, candidates);
	}

	public UndergroundBiomeBanding.CandidateRole registration(int index) {
		return this.registrations[index];
	}

	public Roles candidate(T value) {
		return this.candidates.getOrDefault(value, Roles.EMPTY);
	}

	public Map<T, Roles> candidates() {
		return this.candidates;
	}

	public record Roles(
		boolean surface,
		boolean shallow,
		boolean deep,
		boolean unknown,
		boolean undergroundTagged,
		boolean additionalUnderground
	) {
		private static final Roles EMPTY = new Roles(false, false, false, false, false, false);

		public boolean undergroundRegistered() {
			return this.shallow || this.deep || this.additionalUnderground;
		}

		public boolean undergroundOnly() {
			return this.undergroundTagged || (this.undergroundRegistered() && !this.surface);
		}
	}

	private static final class MutableRoles {
		private boolean surface;
		private boolean shallow;
		private boolean deep;
		private boolean unknown;
		private boolean undergroundTagged;
		private boolean additionalUnderground;

		private void accept(UndergroundBiomeBanding.CandidateRole role) {
			switch (role) {
				case SURFACE -> this.surface = true;
				case SHALLOW_CAVE -> this.shallow = true;
				case DEEP_CAVE -> this.deep = true;
				case UNKNOWN -> this.unknown = true;
			}
		}

		private Roles freeze() {
			return new Roles(
				this.surface,
				this.shallow,
				this.deep,
				this.unknown,
				this.undergroundTagged,
				this.additionalUnderground
			);
		}
	}
}
