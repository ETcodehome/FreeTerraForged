package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Registry-independent syntax retained from the native biome replacement file.
 */
public record BiomeReplacementConfig(
	int formatVersion,
	boolean legacyImport,
	List<Rule> rules,
	List<Diagnostic> diagnostics,
	Map<String, String> options
) {
	public static final int CURRENT_FORMAT_VERSION = 1;

	public BiomeReplacementConfig {
		if (formatVersion <= 0) {
			throw new IllegalArgumentException("Biome replacement format version must be positive");
		}
		rules = List.copyOf(rules);
		diagnostics = List.copyOf(diagnostics);
		options = Map.copyOf(options);
	}

	public boolean hasErrors() {
		return this.diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
	}

	public enum SelectorKind {
		BIOME,
		TAG
	}

	public enum ActionKind {
		REPLACE,
		REMOVE
	}

	public enum Severity {
		INFO,
		WARNING,
		ERROR
	}

	public record Rule(
		int line,
		String dimension,
		SelectorKind selectorKind,
		String selector,
		ActionKind actionKind,
		String target
	) {
		public Rule {
			if (line <= 0) {
				throw new IllegalArgumentException("Biome replacement rule line must be positive");
			}
			dimension = dimension == null ? null : dimension.trim();
			selectorKind = Objects.requireNonNull(selectorKind, "selectorKind");
			selector = Objects.requireNonNull(selector, "selector");
			actionKind = Objects.requireNonNull(actionKind, "actionKind");
			if (actionKind == ActionKind.REPLACE) {
				target = Objects.requireNonNull(target, "replacement target");
			} else if (target != null) {
				throw new IllegalArgumentException("Removal rule cannot retain a target");
			}
		}
	}

	public record Diagnostic(Severity severity, String code, int line, String message) {
		public Diagnostic {
			severity = Objects.requireNonNull(severity, "severity");
			code = Objects.requireNonNull(code, "code");
			if (line < 0) {
				throw new IllegalArgumentException("Diagnostic line must be zero or positive");
			}
			message = Objects.requireNonNull(message, "message");
		}
	}

	public static BiomeReplacementConfig parse(String content) {
		Objects.requireNonNull(content, "content");
		List<Rule> rules = new ArrayList<>();
		List<Diagnostic> diagnostics = new ArrayList<>();
		Map<String, String> options = new LinkedHashMap<>();
		Map<String, Integer> optionLines = new LinkedHashMap<>();
		String currentDimension = null;
		String[] lines = content.split("\\R", -1);
		for (int index = 0; index < lines.length; index++) {
			int lineNumber = index + 1;
			String line = lines[index].trim();
			if (line.isEmpty() || line.startsWith("!") || line.startsWith("# ")) {
				continue;
			}
			if (line.startsWith("[") && line.endsWith("]")) {
				String dimension = line.substring(1, line.length() - 1).trim();
				currentDimension = dimension.isEmpty() ? null : dimension;
				continue;
			}
			if (line.contains("=")) {
				int separator = line.indexOf('=');
				String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
				String value = line.substring(separator + 1).trim();
				if (key.isEmpty() || value.isEmpty()) {
					diagnostics.add(error(
						"malformed_option", lineNumber,
						"Options must use a non-empty key=value form"
					));
					continue;
				}
				if (options.put(key, value) != null) {
					diagnostics.add(warning(
						"duplicate_option", lineNumber,
						"Option '" + key + "' replaces its earlier value"
					));
				}
				optionLines.put(key, lineNumber);
				continue;
			}

			String[] split = line.split(">", 2);
			if (split.length != 2) {
				diagnostics.add(error(
					"malformed_rule", lineNumber,
					"Expected 'source_biome > target_biome'"
				));
				continue;
			}
			String source = split[0].trim();
			String targetExpression = split[1].trim();
			if (source.isEmpty() || targetExpression.isEmpty()) {
				diagnostics.add(error(
					"malformed_rule", lineNumber,
					"Biome replacement source and target must both be non-empty"
				));
				continue;
			}
			String[] targetParts = targetExpression.split("\\s+", 2);
			if (targetParts.length != 1) {
				diagnostics.add(error(
					"unsupported_probability", lineNumber,
					"Chance-based replacement is unsupported; remove the suffix after the target biome"
				));
				continue;
			}
			SelectorKind selectorKind = source.startsWith("#")
				? SelectorKind.TAG
				: SelectorKind.BIOME;
			String selector = selectorKind == SelectorKind.TAG ? source.substring(1).trim() : source;
			if (selector.isEmpty()) {
				diagnostics.add(error(
					"empty_selector", lineNumber,
					"Biome tag selectors must name a tag after '#'"
				));
				continue;
			}
			String target = targetParts[0].trim();
			ActionKind actionKind = target.equals("null") ? ActionKind.REMOVE : ActionKind.REPLACE;
			rules.add(new Rule(
				lineNumber,
				currentDimension,
				selectorKind,
				selector,
				actionKind,
				actionKind == ActionKind.REPLACE ? target : null
			));
		}

		int formatVersion = CURRENT_FORMAT_VERSION;
		String format = options.get("format_version");
		if (format != null) {
			try {
				formatVersion = Integer.parseInt(format);
				if (formatVersion != CURRENT_FORMAT_VERSION) {
					diagnostics.add(error(
						"unsupported_format_version", optionLines.get("format_version"),
						"Unsupported biome replacement format_version=" + formatVersion
					));
				}
			} catch (NumberFormatException failure) {
				diagnostics.add(error(
					"invalid_format_version", optionLines.get("format_version"),
					"format_version must be an integer"
				));
			}
		}

		boolean legacyImport = false;
		String migrationSource = options.get("migration_source");
		if (migrationSource != null) {
			if (migrationSource.equalsIgnoreCase("biome_replacer")) {
				legacyImport = true;
			} else {
				diagnostics.add(error(
					"invalid_migration_source", optionLines.get("migration_source"),
					"migration_source must be 'biome_replacer'"
				));
			}
		}

		for (String option : options.keySet()) {
			if (!option.equals("format_version") && !option.equals("migration_source")) {
				BiomeReplacementConfig.Diagnostic diagnostic = legacyImport
					? warning(
						"unknown_option", optionLines.get(option),
						"Ignoring legacy option '" + option + "'"
					)
					: error(
						"unknown_option", optionLines.get(option),
						"Unknown biome replacement option '" + option + "'"
					);
				diagnostics.add(diagnostic);
			}
		}
		return new BiomeReplacementConfig(formatVersion, legacyImport, rules, diagnostics, options);
	}

	private static Diagnostic error(String code, int line, String message) {
		return new Diagnostic(Severity.ERROR, code, line, message);
	}

	private static Diagnostic warning(String code, int line, String message) {
		return new Diagnostic(Severity.WARNING, code, line, message);
	}
}
