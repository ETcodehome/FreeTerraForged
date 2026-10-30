package etcodehome.freeterraforged.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates an offline, human-evaluated, self-contained HTML verification report
 * for local test runs on any operating system.
 */
public final class LocalTestReportGenerator {

	public static final List<String> BASE_TEST_TOKENS = List.of(
		"presetValidation.checkSynchronizedRegistry",
		"presetValidation.spawnChunkRegularity",
		"presetValidation.builtinRiversFallback",
		"presetValidation.userSelectedDefault",
		"presetValidation.manualSelectionOverrule",
		"presetValidation.worldTypeCycling",
		"presetValidation.successiveRunPreservation",
		"presetValidation.recreateWorldFlow",
		"presetValidation.customFileResolution",
		"presetValidation.multiRunSeamContinuity",
		"presetValidation.inGameCommandParity",
		"presetValidation.twoStageReloadSpawnPersistence",
		"presetValidation.nonFtfWorldResolution"
	);

	public static void main(String[] args) {
		Path targetDir;
		if (args.length > 0 && args[0] != null && !args[0].isBlank()) {
			targetDir = Paths.get(args[0]).toAbsolutePath().normalize();
		} else {
			targetDir = Paths.get("build/reports/preset-test-heightmaps").toAbsolutePath().normalize();
		}

		Path result = generate(targetDir);
		if (result != null) {
			System.out.println("[FTF-Test] Local test report generated: " + result.toUri());
		} else {
			System.err.println("[FTF-Test] Report directory does not exist or has no test outputs: " + targetDir);
			System.exit(1);
		}
	}

	public static Path generate(Path reportDir) {
		if (reportDir == null || !Files.isDirectory(reportDir)) {
			return null;
		}

		try {
			List<Path> allFiles;
			try (Stream<Path> stream = Files.list(reportDir)) {
				allFiles = stream.collect(Collectors.toList());
			}

			// Group suite summaries by loader (fabric, neoforge) and pick the most recent file
			Map<String, Path> latestSummaries = new LinkedHashMap<>();
			Pattern summaryPattern = Pattern.compile("suite_summary_([a-zA-Z0-9_-]+)_(\\d{8}-\\d{6})_report\\.txt");

			for (Path f : allFiles) {
				String name = f.getFileName().toString();
				Matcher m = summaryPattern.matcher(name);
				if (m.matches()) {
					String loader = m.group(1).toLowerCase(Locale.ROOT);
					String timestamp = m.group(2);
					Path existing = latestSummaries.get(loader);
					if (existing == null || name.compareTo(existing.getFileName().toString()) > 0) {
						latestSummaries.put(loader, f);
					}
				}
			}

			// Parse test entries from the latest summaries
			Map<String, TestResult> resultsByToken = new LinkedHashMap<>();
			List<String> failures = new ArrayList<>();

			for (Map.Entry<String, Path> entry : latestSummaries.entrySet()) {
				String loader = entry.getKey();
				Path summaryPath = entry.getValue();
				String content = Files.readString(summaryPath, StandardCharsets.UTF_8);

				String[] entries = content.split("\\[Test Entry\\]");
				for (int i = 1; i < entries.length; i++) {
					String blk = entries[i];
					String name = extractMatch(blk, "Name:\\s*([^\\r\\n]+)");
					String outcome = extractMatch(blk, "Outcome:\\s*([^\\r\\n]+)");
					String notes = extractMatch(blk, "Notes:\\s*([^\\r\\n]+)");
					String error = extractMatch(blk, "Error:\\s*([^\\r\\n]+)");

					if (name != null) {
						boolean passed = "PASSED".equalsIgnoreCase(outcome);
						if (!passed && error != null) {
							failures.add(name + ": " + error);
						}
						resultsByToken.put(name, new TestResult(name, loader, passed, notes != null ? notes : "", error));
					}
				}
			}

			// Parse any watchdog timeout reports
			for (Path f : allFiles) {
				String name = f.getFileName().toString();
				if (name.startsWith("timeout_") && name.endsWith("_report.txt")) {
					String content = Files.readString(f, StandardCharsets.UTF_8);
					String loader = name.contains("neoforge") ? "neoforge" : "fabric";
					String step = extractMatch(content, "Failed Step:\\s*([^\\r\\n]+)");
					String reason = extractMatch(content, "Reason:\\s*([^\\r\\n]+)");
					String token = "watchdogTimeout." + (step != null ? step : "unknown") + "." + loader;
					resultsByToken.put(token, new TestResult(token, loader, false, "Watchdog Timeout: " + reason, reason));
					failures.add(token + ": " + reason);
				}
			}

			// Discover diagnostic reports (*_report.txt excluding suite_summary and timeout)
			List<DiagnosticLog> diagnosticLogs = new ArrayList<>();
			for (Path f : allFiles) {
				String name = f.getFileName().toString();
				if (name.endsWith("_report.txt") && !name.startsWith("suite_summary_") && !name.startsWith("timeout_")) {
					try {
						String logContent = Files.readString(f, StandardCharsets.UTF_8);
						diagnosticLogs.add(new DiagnosticLog(name, logContent));
					} catch (IOException ignored) {
					}
				}
			}
			diagnosticLogs.sort(Comparator.comparing(DiagnosticLog::fileName));

			// Match image previews
			Map<String, List<ImageThumb>> imagesByToken = mapImages(allFiles);

			// Calculate stats
			int totalExpected = BASE_TEST_TOKENS.size() * 2;
			long passedCount = resultsByToken.values().stream().filter(TestResult::passed).count();
			long failedCount = resultsByToken.values().stream().filter(r -> !r.passed()).count();
			boolean overallPassed = (passedCount >= totalExpected) && (failedCount == 0);

			// Build HTML
			String html = renderHtml(
				resultsByToken,
				imagesByToken,
				diagnosticLogs,
				failures,
				totalExpected,
				(int) passedCount,
				(int) failedCount,
				overallPassed,
				reportDir
			);

			Path reportFile = reportDir.resolve("report.html");
			Path indexFile = reportDir.resolve("index.html");
			Files.writeString(reportFile, html, StandardCharsets.UTF_8);
			Files.writeString(indexFile, html, StandardCharsets.UTF_8);

			return reportFile;
		} catch (Exception e) {
			System.err.println("[FTF-Test] Error generating local test report: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	private static Map<String, List<ImageThumb>> mapImages(List<Path> allFiles) {
		Map<String, List<ImageThumb>> map = new LinkedHashMap<>();

		for (String loader : List.of("fabric", "neoforge")) {
			// 1. Spawn regularity
			String spawnToken = "presetValidation.spawnChunkRegularity." + loader;
			List<ImageThumb> spawnList = new ArrayList<>();
			Path spawnVisual = findLatest(allFiles, p -> {
				String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return s.startsWith("heightmap_" + loader) && s.endsWith("_visual.png") && !s.contains("multirun") && !s.contains("command");
			});
			Path spawnRaw = findLatest(allFiles, p -> {
				String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return s.startsWith("heightmap_" + loader) && s.endsWith(".png") && !s.contains("_visual") && !s.contains("multirun") && !s.contains("command");
			});
			if (spawnVisual != null) spawnList.add(new ImageThumb(spawnVisual.getFileName().toString(), "Relief", "Spawn Shaded Relief"));
			if (spawnRaw != null) spawnList.add(new ImageThumb(spawnRaw.getFileName().toString(), "Raw", "Spawn Raw Heightmap"));
			if (!spawnList.isEmpty()) map.put(spawnToken, spawnList);

			// 2. Multi-run seam continuity
			String seamToken = "presetValidation.multiRunSeamContinuity." + loader;
			List<ImageThumb> seamList = new ArrayList<>();
			Path seamVisual = findLatest(allFiles, p -> {
				String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return s.startsWith("heightmap_" + loader) && s.contains("multirun") && s.endsWith("_visual.png");
			});
			Path seamRaw = findLatest(allFiles, p -> {
				String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return s.startsWith("heightmap_" + loader) && s.contains("multirun") && s.endsWith(".png") && !s.contains("_visual");
			});
			if (seamVisual != null) seamList.add(new ImageThumb(seamVisual.getFileName().toString(), "Seam Relief", "Multi-Run Seam Relief"));
			if (seamRaw != null) seamList.add(new ImageThumb(seamRaw.getFileName().toString(), "Seam Raw", "Multi-Run Seam Raw Heightmap"));
			if (!seamList.isEmpty()) map.put(seamToken, seamList);

			// 3. In-game commands
			String cmdToken = "presetValidation.inGameCommandParity." + loader;
			List<ImageThumb> cmdList = new ArrayList<>();
			Path cmdVisual = findLatest(allFiles, p -> {
				String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return s.startsWith("reliefmap_" + loader) && s.contains("command") && s.endsWith(".png");
			});
			Path cmdRaw = findLatest(allFiles, p -> {
				String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return s.startsWith("heightmap_" + loader) && s.contains("command") && s.endsWith(".png");
			});
			if (cmdVisual != null) cmdList.add(new ImageThumb(cmdVisual.getFileName().toString(), "Cmd Relief", "/ftf reliefmap Command"));
			if (cmdRaw != null) cmdList.add(new ImageThumb(cmdRaw.getFileName().toString(), "Cmd Raw", "/ftf heightmap Command"));
			if (!cmdList.isEmpty()) map.put(cmdToken, cmdList);
		}

		return map;
	}

	private static Path findLatest(List<Path> files, java.util.function.Predicate<Path> predicate) {
		return files.stream()
			.filter(predicate)
			.max(Comparator.comparing(p -> p.getFileName().toString()))
			.orElse(null);
	}

	private static String extractMatch(String input, String regex) {
		Matcher m = Pattern.compile(regex).matcher(input);
		return m.find() ? m.group(1).trim() : null;
	}

	private static String escapeHtml(String text) {
		if (text == null) return "";
		return text.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}

	private static String renderHtml(
		Map<String, TestResult> results,
		Map<String, List<ImageThumb>> images,
		List<DiagnosticLog> diagnosticLogs,
		List<String> failures,
		int totalExpected,
		int passedCount,
		int failedCount,
		boolean overallPassed,
		Path reportDir
	) {
		String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		String badgeClass = overallPassed ? "badge-pass" : "badge-fail";
		String badgeText = overallPassed ? "VERIFICATION PASSED" : "VERIFICATION FAILED";
		String badgeIcon = overallPassed ? "&#10004;" : "&#10006;";

		StringBuilder sb = new StringBuilder(16384);
		sb.append("<!DOCTYPE html>\n");
		sb.append("<html lang=\"en\">\n<head>\n");
		sb.append("<meta charset=\"UTF-8\" />\n");
		sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n");
		sb.append("<title>FreeTerraForged Local Verification Report</title>\n");
		sb.append("<style>\n");
		sb.append("  :root {\n");
		sb.append("    --bg-primary: #0d1117;\n");
		sb.append("    --bg-surface: #161b22;\n");
		sb.append("    --bg-surface-hover: #1f242c;\n");
		sb.append("    --border: #30363d;\n");
		sb.append("    --text-primary: #e6edf3;\n");
		sb.append("    --text-muted: #8b949e;\n");
		sb.append("    --pass: #238636;\n");
		sb.append("    --pass-fg: #3fb950;\n");
		sb.append("    --fail: #da3633;\n");
		sb.append("    --fail-fg: #f85149;\n");
		sb.append("    --accent: #58a6ff;\n");
		sb.append("  }\n");
		sb.append("  * { box-sizing: border-box; margin: 0; padding: 0; }\n");
		sb.append("  body {\n");
		sb.append("    background: var(--bg-primary);\n");
		sb.append("    color: var(--text-primary);\n");
		sb.append("    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;\n");
		sb.append("    line-height: 1.5;\n");
		sb.append("    padding: 32px 20px;\n");
		sb.append("  }\n");
		sb.append("  .container { max-width: 1200px; margin: 0 auto; }\n");
		sb.append("  .header {\n");
		sb.append("    display: flex;\n");
		sb.append("    flex-wrap: wrap;\n");
		sb.append("    align-items: center;\n");
		sb.append("    justify-content: space-between;\n");
		sb.append("    gap: 16px;\n");
		sb.append("    margin-bottom: 24px;\n");
		sb.append("    padding-bottom: 16px;\n");
		sb.append("    border-bottom: 1px solid var(--border);\n");
		sb.append("  }\n");
		sb.append("  .header-left { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }\n");
		sb.append("  .badge {\n");
		sb.append("    display: inline-flex;\n");
		sb.append("    align-items: center;\n");
		sb.append("    gap: 8px;\n");
		sb.append("    padding: 8px 16px;\n");
		sb.append("    border-radius: 6px;\n");
		sb.append("    font-weight: 700;\n");
		sb.append("    font-size: 14px;\n");
		sb.append("    letter-spacing: 0.5px;\n");
		sb.append("    text-transform: uppercase;\n");
		sb.append("  }\n");
		sb.append("  .badge-pass { background: var(--pass); color: #ffffff; }\n");
		sb.append("  .badge-fail { background: var(--fail); color: #ffffff; }\n");
		sb.append("  .meta { color: var(--text-muted); font-size: 13px; }\n");
		sb.append("  .meta b { color: var(--text-primary); }\n");
		sb.append("  .filters {\n");
		sb.append("    display: flex;\n");
		sb.append("    gap: 8px;\n");
		sb.append("    margin-bottom: 16px;\n");
		sb.append("    flex-wrap: wrap;\n");
		sb.append("  }\n");
		sb.append("  .filter-btn {\n");
		sb.append("    background: var(--bg-surface);\n");
		sb.append("    border: 1px solid var(--border);\n");
		sb.append("    color: var(--text-primary);\n");
		sb.append("    padding: 6px 14px;\n");
		sb.append("    border-radius: 6px;\n");
		sb.append("    cursor: pointer;\n");
		sb.append("    font-size: 12px;\n");
		sb.append("    font-weight: 500;\n");
		sb.append("    transition: all 0.15s ease;\n");
		sb.append("  }\n");
		sb.append("  .filter-btn:hover { background: var(--bg-surface-hover); border-color: var(--text-muted); }\n");
		sb.append("  .filter-btn.active { background: var(--accent); color: #0d1117; border-color: var(--accent); font-weight: 700; }\n");
		sb.append("  .caution-box {\n");
		sb.append("    background: rgba(218, 54, 51, 0.15);\n");
		sb.append("    border: 1px solid var(--fail-fg);\n");
		sb.append("    border-radius: 6px;\n");
		sb.append("    padding: 16px;\n");
		sb.append("    margin-bottom: 20px;\n");
		sb.append("  }\n");
		sb.append("  .caution-box h4 { color: var(--fail-fg); margin-bottom: 8px; font-size: 14px; }\n");
		sb.append("  .caution-box ul { list-style: disc; margin-left: 20px; font-size: 13px; color: var(--text-primary); }\n");
		sb.append("  table {\n");
		sb.append("    width: 100%;\n");
		sb.append("    border-collapse: collapse;\n");
		sb.append("    background: var(--bg-surface);\n");
		sb.append("    border: 1px solid var(--border);\n");
		sb.append("    border-radius: 8px;\n");
		sb.append("    overflow: hidden;\n");
		sb.append("    margin-bottom: 24px;\n");
		sb.append("  }\n");
		sb.append("  tr { border-bottom: 1px solid var(--border); transition: background 0.15s ease; }\n");
		sb.append("  tr:last-child { border-bottom: none; }\n");
		sb.append("  tr:hover { background: var(--bg-surface-hover); }\n");
		sb.append("  td {\n");
		sb.append("    padding: 10px 14px;\n");
		sb.append("    vertical-align: middle;\n");
		sb.append("    font-size: 13px;\n");
		sb.append("  }\n");
		sb.append("  .td-icon { width: 44px; text-align: center; font-size: 15px; }\n");
		sb.append("  .icon-pass { color: var(--pass-fg); font-weight: bold; }\n");
		sb.append("  .icon-fail { color: var(--fail-fg); font-weight: bold; }\n");
		sb.append("  .td-token { width: 380px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }\n");
		sb.append("  .td-token sub { font-size: 12px; }\n");
		sb.append("  .td-metric sub { font-size: 12px; color: var(--text-muted); }\n");
		sb.append("  .error-text { color: var(--fail-fg); font-weight: 500; }\n");
		sb.append("  .thumb-group { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }\n");
		sb.append("  .thumb-card {\n");
		sb.append("    position: relative;\n");
		sb.append("    display: inline-block;\n");
		sb.append("    border: 1px solid var(--border);\n");
		sb.append("    border-radius: 4px;\n");
		sb.append("    overflow: hidden;\n");
		sb.append("    background: #000;\n");
		sb.append("    cursor: pointer;\n");
		sb.append("    transition: transform 0.15s ease, border-color 0.15s ease;\n");
		sb.append("  }\n");
		sb.append("  .thumb-card:hover { transform: scale(1.05); border-color: var(--accent); }\n");
		sb.append("  .thumb-card img { display: block; width: 90px; height: 90px; object-fit: cover; }\n");
		sb.append("  .thumb-tag {\n");
		sb.append("    position: absolute;\n");
		sb.append("    bottom: 0;\n");
		sb.append("    left: 0;\n");
		sb.append("    right: 0;\n");
		sb.append("    background: rgba(0, 0, 0, 0.75);\n");
		sb.append("    font-size: 9px;\n");
		sb.append("    text-align: center;\n");
		sb.append("    padding: 2px 0;\n");
		sb.append("    color: #fff;\n");
		sb.append("  }\n");
		sb.append("  details.diag {\n");
		sb.append("    background: var(--bg-surface);\n");
		sb.append("    border: 1px solid var(--border);\n");
		sb.append("    border-radius: 6px;\n");
		sb.append("    margin-bottom: 24px;\n");
		sb.append("    padding: 12px 16px;\n");
		sb.append("  }\n");
		sb.append("  details.diag summary {\n");
		sb.append("    cursor: pointer;\n");
		sb.append("    font-size: 14px;\n");
		sb.append("    font-weight: 600;\n");
		sb.append("    color: var(--text-primary);\n");
		sb.append("    user-select: none;\n");
		sb.append("  }\n");
		sb.append("  .diag-item {\n");
		sb.append("    margin-top: 14px;\n");
		sb.append("    padding-top: 10px;\n");
		sb.append("    border-top: 1px solid var(--border);\n");
		sb.append("  }\n");
		sb.append("  .diag-item h5 { font-size: 13px; color: var(--accent); margin-bottom: 6px; }\n");
		sb.append("  .diag-item pre {\n");
		sb.append("    background: var(--bg-primary);\n");
		sb.append("    padding: 10px;\n");
		sb.append("    border-radius: 4px;\n");
		sb.append("    font-size: 11px;\n");
		sb.append("    overflow-x: auto;\n");
		sb.append("    color: var(--text-muted);\n");
		sb.append("  }\n");
		sb.append("  .footer { text-align: center; color: var(--text-muted); font-size: 11px; margin-top: 32px; }\n");
		sb.append("  /* Lightbox Modal */\n");
		sb.append("  #lightbox {\n");
		sb.append("    display: none;\n");
		sb.append("    position: fixed;\n");
		sb.append("    top: 0; left: 0; width: 100%; height: 100%;\n");
		sb.append("    background: rgba(0, 0, 0, 0.88);\n");
		sb.append("    z-index: 1000;\n");
		sb.append("    align-items: center;\n");
		sb.append("    justify-content: center;\n");
		sb.append("    flex-direction: column;\n");
		sb.append("    cursor: zoom-out;\n");
		sb.append("  }\n");
		sb.append("  #lightbox img {\n");
		sb.append("    max-width: 90%;\n");
		sb.append("    max-height: 80%;\n");
		sb.append("    border-radius: 6px;\n");
		sb.append("    box-shadow: 0 10px 40px rgba(0, 0, 0, 0.8);\n");
		sb.append("    border: 1px solid var(--border);\n");
		sb.append("  }\n");
		sb.append("  #lightbox-caption { color: #fff; margin-top: 12px; font-size: 14px; font-weight: 500; }\n");
		sb.append("</style>\n</head>\n<body>\n");
		sb.append("<div class=\"container\">\n");

		// Header
		sb.append("  <header class=\"header\">\n");
		sb.append("    <div class=\"header-left\">\n");
		sb.append("      <span class=\"badge ").append(badgeClass).append("\">").append(badgeIcon).append(" ").append(badgeText).append("</span>\n");
		sb.append("      <div class=\"meta\">\n");
		sb.append("        <span><b>Date:</b> ").append(timestamp).append("</span> &nbsp;|&nbsp; \n");
		sb.append("        <span><b>Results:</b> ").append(passedCount).append(" / ").append(totalExpected).append(" Tests Passed</span>\n");
		sb.append("      </div>\n");
		sb.append("    </div>\n");
		sb.append("    <div class=\"meta\"><span><b>Path:</b> ").append(escapeHtml(reportDir.toString())).append("</span></div>\n");
		sb.append("  </header>\n\n");

		// Caution Box if failures
		if (!overallPassed && !failures.isEmpty()) {
			sb.append("  <div class=\"caution-box\">\n");
			sb.append("    <h4>&#9888; Verification Failures (").append(failures.size()).append("):</h4>\n");
			sb.append("    <ul>\n");
			for (String f : failures) {
				sb.append("      <li>").append(escapeHtml(f)).append("</li>\n");
			}
			sb.append("    </ul>\n");
			sb.append("  </div>\n\n");
		}

		// Filter Buttons
		sb.append("  <div class=\"filters\">\n");
		sb.append("    <button class=\"filter-btn active\" onclick=\"filterRows('all', this)\">All (").append(results.size()).append(")</button>\n");
		sb.append("    <button class=\"filter-btn\" onclick=\"filterRows('pass', this)\">Passed (").append(passedCount).append(")</button>\n");
		if (failedCount > 0) {
			sb.append("    <button class=\"filter-btn\" onclick=\"filterRows('fail', this)\">Failed (").append(failedCount).append(")</button>\n");
		}
		sb.append("    <button class=\"filter-btn\" onclick=\"filterRows('fabric', this)\">Fabric</button>\n");
		sb.append("    <button class=\"filter-btn\" onclick=\"filterRows('neoforge', this)\">NeoForge</button>\n");
		sb.append("  </div>\n\n");

		// Main Verification Table
		sb.append("  <table id=\"verification-table\">\n");
		sb.append("    <tbody>\n");

		// Canonical ordering: for each base token, show fabric then neoforge
		Set<String> processedTokens = new HashSet<>();
		for (String base : BASE_TEST_TOKENS) {
			for (String loader : List.of("fabric", "neoforge")) {
				String token = base + "." + loader;
				processedTokens.add(token);
				TestResult tr = results.get(token);
				appendRow(sb, token, loader, tr, images.get(token));
			}
		}

		// Extra / unexpected tokens
		for (Map.Entry<String, TestResult> e : results.entrySet()) {
			if (!processedTokens.contains(e.getKey())) {
				appendRow(sb, e.getKey(), e.getValue().loader(), e.getValue(), images.get(e.getKey()));
			}
		}

		sb.append("    </tbody>\n");
		sb.append("  </table>\n\n");

		// Detailed Diagnostic Logs
		if (!diagnosticLogs.isEmpty()) {
			sb.append("  <details class=\"diag\">\n");
			sb.append("    <summary>&#128269; Detailed Diagnostic Logs (").append(diagnosticLogs.size()).append(" reports)</summary>\n");
			for (DiagnosticLog diag : diagnosticLogs) {
				sb.append("    <div class=\"diag-item\">\n");
				sb.append("      <h5>").append(escapeHtml(diag.fileName())).append("</h5>\n");
				sb.append("      <pre><code>").append(escapeHtml(diag.content().trim())).append("</code></pre>\n");
				sb.append("    </div>\n");
			}
			sb.append("  </details>\n\n");
		}

		// Footer
		sb.append("  <footer class=\"footer\">\n");
		sb.append("    FreeTerraForged Verification Engine &bull; Generated locally across all platforms\n");
		sb.append("  </footer>\n");
		sb.append("</div>\n\n");

		// Lightbox HTML
		sb.append("<div id=\"lightbox\" onclick=\"closeLightbox()\">\n");
		sb.append("  <img id=\"lightbox-img\" src=\"\" alt=\"Preview\" />\n");
		sb.append("  <div id=\"lightbox-caption\"></div>\n");
		sb.append("</div>\n\n");

		// JavaScript for filtering and lightbox
		sb.append("<script>\n");
		sb.append("  function openLightbox(src, title) {\n");
		sb.append("    document.getElementById('lightbox-img').src = src;\n");
		sb.append("    document.getElementById('lightbox-caption').textContent = title || '';\n");
		sb.append("    document.getElementById('lightbox').style.display = 'flex';\n");
		sb.append("  }\n");
		sb.append("  function closeLightbox() {\n");
		sb.append("    document.getElementById('lightbox').style.display = 'none';\n");
		sb.append("  }\n");
		sb.append("  document.addEventListener('keydown', function(e) {\n");
		sb.append("    if (e.key === 'Escape') closeLightbox();\n");
		sb.append("  });\n");
		sb.append("  function filterRows(type, btn) {\n");
		sb.append("    document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));\n");
		sb.append("    if (btn) btn.classList.add('active');\n");
		sb.append("    const rows = document.querySelectorAll('#verification-table tbody tr');\n");
		sb.append("    rows.forEach(r => {\n");
		sb.append("      const status = r.getAttribute('data-status');\n");
		sb.append("      const loader = r.getAttribute('data-loader');\n");
		sb.append("      if (type === 'all') r.style.display = '';\n");
		sb.append("      else if (type === 'pass') r.style.display = (status === 'pass') ? '' : 'none';\n");
		sb.append("      else if (type === 'fail') r.style.display = (status === 'fail') ? '' : 'none';\n");
		sb.append("      else if (type === 'fabric') r.style.display = (loader === 'fabric') ? '' : 'none';\n");
		sb.append("      else if (type === 'neoforge') r.style.display = (loader === 'neoforge') ? '' : 'none';\n");
		sb.append("    });\n");
		sb.append("  }\n");
		sb.append("</script>\n");
		sb.append("</body>\n</html>\n");

		return sb.toString();
	}

	private static void appendRow(StringBuilder sb, String token, String loader, TestResult tr, List<ImageThumb> thumbList) {
		boolean passed = tr != null && tr.passed();
		String statusStr = passed ? "pass" : "fail";
		String icon = passed ? "<span class=\"icon-pass\">&#10004;</span>" : "<span class=\"icon-fail\">&#10006;</span>";
		String notes = tr != null ? tr.notes() : "Test execution not recorded";
		if (!passed && tr != null && tr.error() != null && !tr.error().isBlank()) {
			notes = "Error: " + tr.error();
		}

		sb.append("      <tr data-status=\"").append(statusStr).append("\" data-loader=\"").append(loader).append("\">\n");
		sb.append("        <td class=\"td-icon\"><sub>").append(icon).append("</sub></td>\n");
		sb.append("        <td class=\"td-token\"><sub>").append(escapeHtml(token)).append("</sub></td>\n");
		sb.append("        <td class=\"td-metric\">\n");

		if (thumbList != null && !thumbList.isEmpty()) {
			sb.append("          <div class=\"thumb-group\">\n");
			for (ImageThumb img : thumbList) {
				sb.append("            <div class=\"thumb-card\" onclick=\"openLightbox('").append(escapeHtml(img.fileName()))
					.append("', '").append(escapeHtml(img.title())).append("')\" title=\"").append(escapeHtml(img.title())).append("\">\n");
				sb.append("              <img src=\"").append(escapeHtml(img.fileName())).append("\" alt=\"").append(escapeHtml(img.label())).append("\" />\n");
				sb.append("              <div class=\"thumb-tag\">").append(escapeHtml(img.label())).append("</div>\n");
				sb.append("            </div>\n");
			}
			sb.append("          </div>\n");
		}

		if (!passed) {
			sb.append("          <sub class=\"error-text\">").append(escapeHtml(notes)).append("</sub>\n");
		} else {
			sb.append("          <sub>").append(escapeHtml(notes)).append("</sub>\n");
		}

		sb.append("        </td>\n");
		sb.append("      </tr>\n");
	}

	public record TestResult(String token, String loader, boolean passed, String notes, String error) {}
	public record ImageThumb(String fileName, String label, String title) {}
	public record DiagnosticLog(String fileName, String content) {}
}
