package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

/** Immutable, owner-bound one-hop biome replacement decisions. */
public final class BiomeReplacementPlan {
	private final BiomeReplacementConfigSnapshot config;
	private final ResourceLocation dimension;
	private final Map<Holder<Biome>, Decision> decisions;
	private final Set<Holder<Biome>> removals;
	private final Set<Holder<Biome>> replacementTargets;
	private final List<BiomeReplacementConfig.Diagnostic> diagnostics;
	private final boolean active;

	BiomeReplacementPlan(
		BiomeReplacementConfigSnapshot config,
		ResourceLocation dimension,
		Map<Holder<Biome>, Decision> decisions,
		Set<Holder<Biome>> removals,
		Set<Holder<Biome>> replacementTargets,
		List<BiomeReplacementConfig.Diagnostic> diagnostics
	) {
		this.config = Objects.requireNonNull(config, "config");
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		IdentityHashMap<Holder<Biome>, Decision> decisionCopy = new IdentityHashMap<>();
		decisionCopy.putAll(decisions);
		this.decisions = Collections.unmodifiableMap(decisionCopy);
		this.removals = immutableIdentitySet(removals);
		this.replacementTargets = immutableIdentitySet(replacementTargets);
		this.diagnostics = List.copyOf(diagnostics);
		this.active = !this.decisions.isEmpty();
	}

	private static Set<Holder<Biome>> immutableIdentitySet(Collection<Holder<Biome>> values) {
		Set<Holder<Biome>> copy = Collections.newSetFromMap(new IdentityHashMap<>());
		copy.addAll(values);
		return Collections.unmodifiableSet(copy);
	}

	public BiomeReplacementConfigSnapshot config() {
		return this.config;
	}

	public ResourceLocation dimension() {
		return this.dimension;
	}

	public boolean active() {
		return this.active;
	}

	public int decisionCount() {
		return this.decisions.size();
	}

	public Set<Holder<Biome>> removals() {
		return this.removals;
	}

	public Set<Holder<Biome>> replacementTargets() {
		return this.replacementTargets;
	}

	public List<BiomeReplacementConfig.Diagnostic> diagnostics() {
		return this.diagnostics;
	}

	public Optional<Decision> decision(Holder<Biome> biome) {
		return Optional.ofNullable(this.decisions.get(biome));
	}

	public boolean isRemoved(Holder<Biome> biome) {
		return this.removals.contains(biome);
	}

	/** Hot-path terminal substitution. Missing rules return the original holder without allocation. */
	public Holder<Biome> apply(Holder<Biome> selected, Holder<Biome> providerBase) {
		Objects.requireNonNull(selected, "selected");
		if (!this.active) {
			return selected;
		}
		Decision decision = this.decisions.get(selected);
		if (decision == null) {
			return selected;
		}
		if (decision instanceof Replace replace) {
			return replace.target();
		}
		if (decision instanceof Keep) {
			return selected;
		}
		if (providerBase == null) {
			throw new IllegalStateException(
				"Biome removal reached a source without a provider-base fallback: " + biomeId(selected)
			);
		}
		if (this.removals.contains(providerBase)) {
			throw new IllegalStateException(
				"Biome removal fallback is itself removed: " + biomeId(providerBase)
			);
		}
		return providerBase;
	}

	/** Preview-only provenance; normal generation uses {@link #apply(Holder, Holder)}. */
	public PreviewSelection describe(Holder<Biome> selected, Holder<Biome> providerBase) {
		Objects.requireNonNull(selected, "selected");
		Decision decision = this.active ? this.decisions.get(selected) : null;
		if (decision == null) {
			return new PreviewSelection(selected, selected, OverrideKind.NONE, null);
		}
		if (decision instanceof Replace replace) {
			return new PreviewSelection(replace.target(), selected, OverrideKind.REPLACED, replace.rule());
		}
		if (decision instanceof Keep) {
			return new PreviewSelection(selected, selected, OverrideKind.NONE, null);
		}
		Holder<Biome> fallback = this.apply(selected, providerBase);
		return new PreviewSelection(fallback, selected, OverrideKind.REMOVAL_FALLBACK, decision.rule());
	}

	public Set<Holder<Biome>> transformPossible(Collection<Holder<Biome>> possible) {
		LinkedHashSet<Holder<Biome>> transformed = new LinkedHashSet<>();
		for (Holder<Biome> biome : possible) {
			Decision decision = this.active ? this.decisions.get(biome) : null;
			if (decision instanceof Replace replace) {
				transformed.add(replace.target());
			} else if (!(decision instanceof Remove)) {
				transformed.add(biome);
			}
		}
		return Collections.unmodifiableSet(transformed);
	}

	private static String biomeId(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> key.location().toString()).orElse("<unregistered>");
	}

	public sealed interface Decision permits Replace, Remove, Keep {
		RuleInfo rule();
	}

	public record Replace(Holder<Biome> target, RuleInfo rule) implements Decision {
		public Replace {
			target = Objects.requireNonNull(target, "target");
			rule = Objects.requireNonNull(rule, "rule");
		}
	}

	public record Remove(RuleInfo rule) implements Decision {
		public Remove {
			rule = Objects.requireNonNull(rule, "rule");
		}
	}

	/** Compile-time precedence tombstone for a diagnosed self-replacement; never published. */
	record Keep(RuleInfo rule) implements Decision {
		Keep {
			rule = Objects.requireNonNull(rule, "rule");
		}
	}

	public record RuleInfo(
		int index,
		int line,
		String dimension,
		String selector,
		BiomeReplacementConfig.SelectorKind selectorKind,
		BiomeReplacementConfig.ActionKind actionKind
	) {
		public RuleInfo {
			if (index < 0 || line <= 0) {
				throw new IllegalArgumentException("Rule index and line are invalid");
			}
			selector = Objects.requireNonNull(selector, "selector");
			selectorKind = Objects.requireNonNull(selectorKind, "selectorKind");
			actionKind = Objects.requireNonNull(actionKind, "actionKind");
		}
	}

	public enum OverrideKind {
		NONE,
		REPLACED,
		REMOVAL_FALLBACK
	}

	public record PreviewSelection(
		Holder<Biome> finalBiome,
		Holder<Biome> preReplacementBiome,
		OverrideKind overrideKind,
		RuleInfo rule
	) {
		public PreviewSelection {
			finalBiome = Objects.requireNonNull(finalBiome, "finalBiome");
			preReplacementBiome = Objects.requireNonNull(preReplacementBiome, "preReplacementBiome");
			overrideKind = Objects.requireNonNull(overrideKind, "overrideKind");
			if ((overrideKind == OverrideKind.NONE) != (rule == null)) {
				throw new IllegalArgumentException("Preview replacement provenance and rule disagree");
			}
		}
	}
}
