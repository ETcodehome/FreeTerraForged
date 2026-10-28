package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import etcodehome.freeterraforged.platform.ModLoaderUtil;

/** Binds parsed rules to one owner's frozen biome registry, dimension, and tag epoch. */
public final class BiomeReplacementCompiler {
	private static final Set<String> MARKER_BIOMES = Set.of(
		"terrablender:deferred_placeholder",
		"blueprint:original_source_marker"
	);
	private static final Set<String> ALWAYS_FATAL_PARSE_CODES = Set.of(
		"unsupported_format_version",
		"invalid_format_version",
		"invalid_migration_source"
	);

	private BiomeReplacementCompiler() {
	}

	public static BiomeReplacementPlan compile(WorldgenOwner owner) {
		return compile(owner, ModLoaderUtil::isLoaded);
	}

	static BiomeReplacementPlan compile(WorldgenOwner owner, Predicate<String> modLoaded) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(modLoaded, "modLoaded");
		BiomeReplacementConfigSnapshot snapshot = owner.biomeReplacementConfig();
		BiomeReplacementConfig config = snapshot.config();
		List<BiomeReplacementConfig.Diagnostic> diagnostics = new ArrayList<>();
		for (BiomeReplacementConfig.Diagnostic diagnostic : config.diagnostics()) {
			if (config.legacyImport()
				&& diagnostic.severity() == BiomeReplacementConfig.Severity.ERROR
				&& !ALWAYS_FATAL_PARSE_CODES.contains(diagnostic.code())) {
				diagnostics.add(new BiomeReplacementConfig.Diagnostic(
					BiomeReplacementConfig.Severity.WARNING,
					diagnostic.code(), diagnostic.line(),
					diagnostic.message() + "; compatibility validation skipped this input"
				));
			} else {
				diagnostics.add(diagnostic);
			}
		}
		requireNoErrors(diagnostics, snapshot);

		Registry<Biome> biomes = owner.registries().registryOrThrow(Registries.BIOME);
		ResourceLocation dimension = owner.dimension().location();
		Scope global = new Scope();
		Scope selectedDimension = new Scope();
		int ruleIndex = 0;
		for (BiomeReplacementConfig.Rule rule : config.rules()) {
			int currentRuleIndex = ruleIndex++;
			Scope scope;
			if (rule.dimension() == null) {
				scope = global;
			} else {
				ResourceLocation ruleDimension = ResourceLocation.tryParse(rule.dimension());
				if (ruleDimension == null) {
					bindingFailure(
						config, diagnostics, rule.line(), "invalid_dimension",
						"Invalid dimension ID '" + rule.dimension() + "'"
					);
					continue;
				}
				if (!ruleDimension.equals(dimension)) {
					continue;
				}
				scope = selectedDimension;
			}
			try {
				bindRule(rule, currentRuleIndex, scope, biomes, diagnostics);
			} catch (RuleBindingException failure) {
				bindingFailure(config, diagnostics, rule.line(), failure.code, failure.getMessage());
			}
		}
		requireNoErrors(diagnostics, snapshot);

		IdentityHashMap<Holder<Biome>, BiomeReplacementPlan.Decision> effective =
			BiomeReplacementPrecedence.overlay(
				IdentityHashMap::new, global.decisions, selectedDimension.decisions
			);
		effective.entrySet().removeIf(entry -> entry.getValue() instanceof BiomeReplacementPlan.Keep);
		Set<Holder<Biome>> removals = identitySet();
		Set<Holder<Biome>> targets = identitySet();
		for (Map.Entry<Holder<Biome>, BiomeReplacementPlan.Decision> entry : effective.entrySet()) {
			if (entry.getValue() instanceof BiomeReplacementPlan.Remove) {
				removals.add(entry.getKey());
			} else if (entry.getValue() instanceof BiomeReplacementPlan.Replace replace) {
				targets.add(replace.target());
			}
		}
		for (Map.Entry<Holder<Biome>, BiomeReplacementPlan.Decision> entry : effective.entrySet()) {
			if (entry.getValue() instanceof BiomeReplacementPlan.Replace replace
				&& removals.contains(replace.target())) {
				diagnostics.add(error(
					"replacement_target_removed", replace.rule().line(),
					"Replacement target " + biomeId(replace.target())
						+ " is removed by the effective rules"
				));
			}
		}
		addGraphDiagnostics(effective, diagnostics);
		Map<BiomeReplacementPlan.RuleInfo, List<Holder<Biome>>> replacedByRule = new LinkedHashMap<>();
		for (Map.Entry<Holder<Biome>, BiomeReplacementPlan.Decision> entry : sortedEntries(effective)) {
			if (entry.getValue() instanceof BiomeReplacementPlan.Replace replace) {
				replacedByRule.computeIfAbsent(replace.rule(), ignored -> new ArrayList<>())
					.add(entry.getKey());
			}
		}
		for (Map.Entry<BiomeReplacementPlan.RuleInfo, List<Holder<Biome>>> entry
			: replacedByRule.entrySet()) {
			BiomeReplacementPlan.Replace replace = (BiomeReplacementPlan.Replace) effective.get(
				entry.getValue().getFirst()
			);
			String sources = entry.getValue().stream().limit(3)
				.map(BiomeReplacementCompiler::biomeId)
				.collect(java.util.stream.Collectors.joining(", "));
			diagnostics.add(info(
				"placement_preserved", entry.getKey().line(),
				"Rule replaces " + entry.getValue().size() + " source biome(s) with "
					+ biomeId(replace.target()) + " (examples: " + sources + ") while preserving "
					+ "source climate, terrain, hydrology, and placement envelopes"
			));
		}
		if (modLoaded.test("biome_replacer")) {
			diagnostics.add(info(
				"biome_replacer_present", 0,
				"Biome Replacer is installed; FTF uses " + snapshot.path()
					+ " only for FTF-owned plans and leaves other generators untouched"
			));
			if (modLoaded.test("lithostitched")) {
				diagnostics.add(info(
					"biome_replacer_lithostitched_suppressed", 0,
					"FTF excludes Lithostitched injectors whose registered identifier has the "
						+ "biome_replacer namespace; suppression is scoped to the FTF-owned plan"
				));
			}
			if (modLoaded.test("biolith")) {
				diagnostics.add(warning(
					"biome_replacer_biolith_provenance_unavailable", 0,
					"Biolith does not expose caller provenance for captured replacement ranges. If "
						+ "Biome Replacer registered the same rules through Biolith, FTF cannot source-prove "
						+ "exact-once coexistence; keep FTF-world rules in the native FTF file"
				));
			}
		}
		requireNoErrors(diagnostics, snapshot);
		return new BiomeReplacementPlan(snapshot, dimension, effective, removals, targets, diagnostics);
	}

	private static void bindRule(
		BiomeReplacementConfig.Rule rule,
		int ruleIndex,
		Scope scope,
		Registry<Biome> biomes,
		List<BiomeReplacementConfig.Diagnostic> diagnostics
	) throws RuleBindingException {
		validateMarker(rule.selector(), rule.line(), "source");
		Holder<Biome> target = null;
		if (rule.actionKind() == BiomeReplacementConfig.ActionKind.REPLACE) {
			validateMarker(rule.target(), rule.line(), "target");
			target = biome(biomes, rule.target(), "target");
		}
		BiomeReplacementPlan.RuleInfo ruleInfo = new BiomeReplacementPlan.RuleInfo(
			ruleIndex, rule.line(), rule.dimension(), rule.selector(),
			rule.selectorKind(), rule.actionKind()
		);
		BiomeReplacementPlan.Decision decision = target == null
			? new BiomeReplacementPlan.Remove(ruleInfo)
			: new BiomeReplacementPlan.Replace(target, ruleInfo);
		if (rule.selectorKind() == BiomeReplacementConfig.SelectorKind.BIOME) {
			Holder<Biome> source = biome(biomes, rule.selector(), "source");
			BiomeReplacementPlan.Decision scopedDecision = decision;
			if (decision instanceof BiomeReplacementPlan.Replace replace && source == replace.target()) {
				scopedDecision = new BiomeReplacementPlan.Keep(ruleInfo);
				diagnostics.add(warning(
					"self_replacement", rule.line(),
					"Self-replacement for " + biomeId(source)
						+ " has no terminal effect and was discarded after precedence binding"
				));
			}
			BiomeReplacementPlan.Decision previous = BiomeReplacementPrecedence.overwrite(
				scope.decisions, source, scopedDecision
			);
			if (previous != null) {
				diagnostics.add(warning(
					"direct_rule_overwrite", rule.line(),
					"Direct rule for " + biomeId(source) + " overwrites line " + previous.rule().line()
				));
			}
			return;
		}

		ResourceLocation tagId = parseId(rule.selector(), "biome tag");
		TagKey<Biome> tag = TagKey.create(Registries.BIOME, tagId);
		List<Holder.Reference<Biome>> members = biomes.holders()
			.filter(holder -> holder.is(tag))
			.sorted(Comparator.comparing(holder -> holder.key().location().toString()))
			.toList();
		if (members.isEmpty()) {
			diagnostics.add(warning(
				"empty_tag", rule.line(),
				"Biome tag #" + tagId + " has no members in the owner's frozen tag snapshot"
			));
		}
		for (Holder.Reference<Biome> source : members) {
			if (MARKER_BIOMES.contains(source.key().location().toString())) {
				throw new RuleBindingException(
					"integration_marker",
					"Biome tag #" + tagId + " includes integration marker "
						+ source.key().location() + " and cannot be used"
				);
			}
		}
		BiomeReplacementPrecedence.FillResult<Holder<Biome>> fill =
			BiomeReplacementPrecedence.fillUnclaimedWith(
				scope.decisions, members,
				source -> decision instanceof BiomeReplacementPlan.Replace replace
					&& source == replace.target()
						? new BiomeReplacementPlan.Keep(ruleInfo)
						: decision
			);
		for (Holder<Biome> source : fill.added()) {
			if (decision instanceof BiomeReplacementPlan.Replace replace && source == replace.target()) {
				diagnostics.add(warning(
					"self_replacement", rule.line(),
					"Tag rule resolves to a self-replacement for " + biomeId(source)
						+ "; that source was discarded after precedence binding"
				));
			}
		}
		if (fill.shadowed() > 0) {
			diagnostics.add(warning(
				"tag_rule_shadowed", rule.line(),
				"Tag rule #" + tagId + " left " + fill.shadowed()
					+ " already-claimed biome source(s) unchanged in this scope"
			));
		}
	}

	private static Holder.Reference<Biome> biome(
		Registry<Biome> biomes,
		String id,
		String role
	) throws RuleBindingException {
		ResourceLocation location = parseId(id, role + " biome");
		ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, location);
		return biomes.getHolder(key).orElseThrow(() -> new RuleBindingException(
			"missing_biome", "Biome " + role + " '" + location + "' does not exist"
		));
	}

	private static ResourceLocation parseId(String id, String role) throws RuleBindingException {
		ResourceLocation location = ResourceLocation.tryParse(id);
		if (location == null) {
			throw new RuleBindingException("invalid_resource_location", "Invalid " + role + " ID '" + id + "'");
		}
		return location;
	}

	private static void validateMarker(String id, int line, String role) throws RuleBindingException {
		if (MARKER_BIOMES.contains(id)) {
			throw new RuleBindingException(
				"integration_marker",
				"Biome " + role + " '" + id + "' is an integration marker and cannot be used"
			);
		}
	}

	private static void bindingFailure(
		BiomeReplacementConfig config,
		List<BiomeReplacementConfig.Diagnostic> diagnostics,
		int line,
		String code,
		String message
	) {
		BiomeReplacementConfig.Severity severity = code.equals("integration_marker")
			|| !config.legacyImport()
				? BiomeReplacementConfig.Severity.ERROR
				: BiomeReplacementConfig.Severity.WARNING;
		diagnostics.add(new BiomeReplacementConfig.Diagnostic(
			severity, code, line,
			message + (severity == BiomeReplacementConfig.Severity.WARNING
				? "; compatibility validation ignored this rule"
				: "")
		));
	}

	private static void addGraphDiagnostics(
		IdentityHashMap<Holder<Biome>, BiomeReplacementPlan.Decision> decisions,
		List<BiomeReplacementConfig.Diagnostic> diagnostics
	) {
		Set<Holder<Biome>> cycleMembers = identitySet();
		Set<String> reportedCycles = new LinkedHashSet<>();
		for (Map.Entry<Holder<Biome>, BiomeReplacementPlan.Decision> start : sortedEntries(decisions)) {
			IdentityHashMap<Holder<Biome>, Integer> positions = new IdentityHashMap<>();
			List<Holder<Biome>> path = new ArrayList<>();
			Holder<Biome> current = start.getKey();
			while (true) {
				Integer repeatedAt = positions.putIfAbsent(current, path.size());
				if (repeatedAt != null) {
					List<Holder<Biome>> cycle = path.subList(repeatedAt, path.size());
					if (cycle.size() > 1) {
						List<String> orderedIds = cycle.stream()
							.map(BiomeReplacementCompiler::biomeId).toList();
						String signature = cycle.stream().map(BiomeReplacementCompiler::biomeId)
							.sorted().collect(java.util.stream.Collectors.joining("|"));
						if (reportedCycles.add(signature)) {
							cycleMembers.addAll(cycle);
							int line = cycle.stream()
								.map(decisions::get)
								.mapToInt(decision -> decision.rule().line())
								.min().orElse(0);
							diagnostics.add(warning(
								"replacement_cycle", line,
								"Atomic one-hop cycle: " + String.join(" -> ", orderedIds)
									+ " -> " + orderedIds.getFirst()
							));
						}
					}
					break;
				}
				path.add(current);
				BiomeReplacementPlan.Decision decision = decisions.get(current);
				if (!(decision instanceof BiomeReplacementPlan.Replace replace)
					|| !(decisions.get(replace.target()) instanceof BiomeReplacementPlan.Replace)) {
					break;
				}
				current = replace.target();
			}
		}
		for (Map.Entry<Holder<Biome>, BiomeReplacementPlan.Decision> entry : sortedEntries(decisions)) {
			if (cycleMembers.contains(entry.getKey())
				|| !(entry.getValue() instanceof BiomeReplacementPlan.Replace replace)) {
				continue;
			}
			BiomeReplacementPlan.Decision next = decisions.get(replace.target());
			if (next instanceof BiomeReplacementPlan.Replace chained
				&& chained.target() != replace.target()) {
				diagnostics.add(info(
					"replacement_chain", replace.rule().line(),
					"Atomic one-hop chain: original " + biomeId(entry.getKey()) + " becomes "
						+ biomeId(replace.target()) + ", not " + biomeId(chained.target())
				));
			}
		}
	}

	private static List<Map.Entry<Holder<Biome>, BiomeReplacementPlan.Decision>> sortedEntries(
		IdentityHashMap<Holder<Biome>, BiomeReplacementPlan.Decision> decisions
	) {
		return decisions.entrySet().stream()
			.sorted(Comparator.comparing(entry -> biomeId(entry.getKey())))
			.toList();
	}

	private static void requireNoErrors(
		List<BiomeReplacementConfig.Diagnostic> diagnostics,
		BiomeReplacementConfigSnapshot snapshot
	) {
		List<BiomeReplacementConfig.Diagnostic> errors = diagnostics.stream()
			.filter(diagnostic -> diagnostic.severity() == BiomeReplacementConfig.Severity.ERROR)
			.toList();
		if (!errors.isEmpty()) {
			String first = errors.getFirst().line() > 0
				? "line " + errors.getFirst().line() + ": " + errors.getFirst().message()
				: errors.getFirst().message();
			throw new IllegalStateException(
				"Invalid biome replacement config " + snapshot.path() + " ["
					+ errors.getFirst().code() + "] " + first
					+ (errors.size() == 1 ? "" : " (and " + (errors.size() - 1) + " more error(s))")
			);
		}
	}

	private static Set<Holder<Biome>> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}

	private static String biomeId(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> key.location().toString()).orElse("<unregistered>");
	}

	private static BiomeReplacementConfig.Diagnostic info(String code, int line, String message) {
		return new BiomeReplacementConfig.Diagnostic(
			BiomeReplacementConfig.Severity.INFO, code, line, message
		);
	}

	private static BiomeReplacementConfig.Diagnostic warning(String code, int line, String message) {
		return new BiomeReplacementConfig.Diagnostic(
			BiomeReplacementConfig.Severity.WARNING, code, line, message
		);
	}

	private static BiomeReplacementConfig.Diagnostic error(String code, int line, String message) {
		return new BiomeReplacementConfig.Diagnostic(
			BiomeReplacementConfig.Severity.ERROR, code, line, message
		);
	}

	private static final class Scope {
		private final IdentityHashMap<Holder<Biome>, BiomeReplacementPlan.Decision> decisions =
			new IdentityHashMap<>();
	}

	private static final class RuleBindingException extends Exception {
		private final String code;

		private RuleBindingException(String code, String message) {
			super(message);
			this.code = code;
		}
	}
}
