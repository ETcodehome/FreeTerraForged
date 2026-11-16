package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.biome.BiomeCandidateRoles;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeTags;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/** Immutable semantic placement roles published with one compiled worldgen runtime state. */
public final class BiomePlacementPlan {
	private final WorldgenPlan plan;
	private final Map<Holder<Biome>, Set<Role>> roles;
	private final boolean bandingEnabled;
	private final SearchScales searchScales;

	private BiomePlacementPlan(
		WorldgenPlan plan,
		Map<Holder<Biome>, Set<Role>> roles,
		boolean bandingEnabled,
		SearchScales searchScales
	) {
		this.plan = plan;
		this.roles = roles;
		this.bandingEnabled = bandingEnabled;
		this.searchScales = searchScales;
	}

	public static BiomePlacementPlan compile(
		WorldgenPlan plan,
		Preset preset,
		Set<Holder<Biome>> possibleBiomes
	) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(preset, "preset");
		Objects.requireNonNull(possibleBiomes, "possibleBiomes");
		Map<Holder<Biome>, EnumSet<Role>> source = new IdentityHashMap<>();
		boolean bandingEnabled = preset.climate().biomeShape.undergroundBiomeBanding;
		plan.providerSelection().providers().forEach(domain -> addSemantic(
			source, domain.candidates(), bandingEnabled
		));
		plan.providerSelection().fallback().ifPresent(candidates -> addSemantic(
			source, candidates, bandingEnabled
		));
		plan.providerSelection().directInput().ifPresent(input -> input.possibleOutputs()
			.forEach(biome -> roleSet(source, biome).add(Role.UNKNOWN)));
		plan.biomeComposition().entries().forEach(entry -> source.computeIfAbsent(
			entry.getSecond(), ignored -> EnumSet.of(Role.UNKNOWN)
		));
		plan.selectionDecoration().possibleOutputs()
			.forEach(biome -> roleSet(source, biome).add(Role.UNKNOWN));

		BiomeReplacementPlan replacement = plan.biomeReplacement().plan();
		Map<Holder<Biome>, EnumSet<Role>> transformed = new IdentityHashMap<>();
		for (Map.Entry<Holder<Biome>, EnumSet<Role>> entry : source.entrySet()) {
			BiomeReplacementPlan.Decision decision = replacement.decision(entry.getKey()).orElse(null);
			if (decision instanceof BiomeReplacementPlan.Remove) {
				continue;
			}
			Holder<Biome> output = decision instanceof BiomeReplacementPlan.Replace replace
				? replace.target()
				: entry.getKey();
			roleSet(transformed, output).addAll(entry.getValue());
		}

		for (Holder<Biome> biome : possibleBiomes) {
			transformed.computeIfAbsent(biome, ignored -> EnumSet.of(Role.UNKNOWN));
		}
		Map<Holder<Biome>, Set<Role>> immutable = new IdentityHashMap<>();
		transformed.forEach((biome, value) -> immutable.put(biome, Set.copyOf(value)));
		return new BiomePlacementPlan(
			plan,
			Collections.unmodifiableMap(immutable),
			bandingEnabled,
			new SearchScales(
				preset.climate().biomeShape.biomeSize(),
				preset.climate().biomeShape.undergroundBiomeSize(),
				preset.climate().biomeShape.undergroundBiomeVerticalSize(
					preset.world().properties.worldHeight,
					preset.world().properties.worldDepth
				)
			)
		);
	}

	public WorldgenPlan plan() {
		return this.plan;
	}

	public SearchScales searchScales() {
		return this.searchScales;
	}

	public TargetRoles classify(Predicate<Holder<Biome>> target) {
		Objects.requireNonNull(target, "target");
		EnumSet<Role> result = EnumSet.noneOf(Role.class);
		boolean possible = false;
		boolean thinSurface = false;
		for (Map.Entry<Holder<Biome>, Set<Role>> entry : this.roles.entrySet()) {
			if (target.test(entry.getKey())) {
				possible = true;
				result.addAll(entry.getValue());
				if (entry.getValue().contains(Role.SURFACE) || entry.getValue().contains(Role.UNKNOWN)) {
					thinSurface |= entry.getKey().is(BiomeTags.IS_RIVER) || entry.getKey().is(BiomeTags.IS_BEACH);
				}
			}
		}
		return new TargetRoles(possible, Set.copyOf(result), this.bandingEnabled, thinSurface);
	}

	private static void addSemantic(
		Map<Holder<Biome>, EnumSet<Role>> roles,
		Climate.ParameterList<Holder<Biome>> candidates,
		boolean bandingEnabled
	) {
		BiomeCandidateRoles<Holder<Biome>> classified = BiomeCandidateRoles.compile(
			candidates,
			(point, biome) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(biome)),
			UndergroundBiomeTags::isCave,
			Set.of()
		);
		classified.candidates().forEach((biome, candidate) -> {
			EnumSet<Role> semantic = roleSet(roles, biome);
			if (candidate.unknown()) {
				semantic.add(Role.UNKNOWN);
				return;
			}
			if (!candidate.undergroundOnly()) {
				if (candidate.surface()) {
					semantic.add(Role.SURFACE);
				} else {
					semantic.add(Role.UNKNOWN);
				}
				return;
			}
			if (!candidate.undergroundRegistered()) {
				semantic.add(Role.UNKNOWN);
				return;
			}
			if (!bandingEnabled) {
				// Without stage banding, either underground registration may win at any underground depth.
				semantic.add(Role.SHALLOW_CAVE);
				semantic.add(Role.DEEP_CAVE);
				return;
			}
			if (candidate.shallow() || candidate.additionalUnderground()) {
				semantic.add(Role.SHALLOW_CAVE);
				// The deep stage deliberately includes shallow candidates as well as bottom candidates.
				semantic.add(Role.DEEP_CAVE);
			}
			if (candidate.deep()) {
				semantic.add(Role.DEEP_CAVE);
			}
		});
	}

	private static EnumSet<Role> roleSet(
		Map<Holder<Biome>, EnumSet<Role>> roles,
		Holder<Biome> biome
	) {
		return roles.computeIfAbsent(biome, ignored -> EnumSet.noneOf(Role.class));
	}

	public enum Role {
		SURFACE,
		SHALLOW_CAVE,
		DEEP_CAVE,
		UNKNOWN
	}

	public record SearchScales(
		int surfaceBiomeSize,
		int undergroundBiomeSize,
		int undergroundBiomeVerticalSize
	) {
	}

	public record TargetRoles(
		boolean possible,
		Set<Role> roles,
		boolean bandingEnabled,
		boolean thinSurface
	) {
		public TargetRoles {
			roles = Set.copyOf(roles);
		}

		public boolean searchesSurface() {
			return this.roles.contains(Role.SURFACE) || this.roles.contains(Role.UNKNOWN);
		}

		public boolean searchesShallow() {
			return this.roles.contains(Role.SHALLOW_CAVE) || this.roles.contains(Role.UNKNOWN);
		}

		public boolean searchesDeep() {
			return this.roles.contains(Role.DEEP_CAVE) || this.roles.contains(Role.UNKNOWN);
		}

		public boolean searchesUnderground() {
			return this.searchesShallow() || this.searchesDeep();
		}

		public boolean unknown() {
			return this.roles.contains(Role.UNKNOWN);
		}
	}
}
