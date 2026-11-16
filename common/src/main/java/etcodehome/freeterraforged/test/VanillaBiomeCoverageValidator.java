package etcodehome.freeterraforged.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.server.command.LocateBiomeCommand;
import etcodehome.freeterraforged.world.worldgen.search.BiomeLocateSearch;
import etcodehome.freeterraforged.world.worldgen.search.FTFBiomeLocateQuery;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.phys.Vec3;

/**
 * System test validator that retrieves the complete list of vanilla Minecraft biomes from the server
 * registry and verifies via {@code /ftf locate biome} that every vanilla Overworld biome generates
 * reasonable-sized non-dithered areas (default >= 12x12 blocks).
 */
public final class VanillaBiomeCoverageValidator {
	private static final long PER_BIOME_TIMEOUT_SECONDS = Long.getLong(
		"freeterraforged.test.allBiomes.timeoutSeconds", 25L
	);

	private VanillaBiomeCoverageValidator() {
	}

	public static Result validate(
		MinecraftServer server,
		String loader,
		Consumer<String> progressCallback
	) throws Exception {
		ServerLevel level = server.overworld();
		FTFBiomeLocateQuery query = LocateBiomeCommand.locateQuery(level);
		if (query == null) {
			throw new AssertionError("Vanilla biome coverage validation requires an active FTF overworld query");
		}

		Registry<Biome> biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
		var parameterLists = server.registryAccess().lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
		var overworldParamList = parameterLists.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters();

		Set<ResourceKey<Biome>> overworldParameterBiomes = new LinkedHashSet<>();
		overworldParamList.values().forEach(pair -> pair.getSecond().unwrapKey().ifPresent(overworldParameterBiomes::add));

		List<Holder.Reference<Biome>> allVanillaBiomes = biomeRegistry.holders()
			.filter(holder -> holder.key().location().getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE))
			.sorted(Comparator.comparing(holder -> holder.key().location().toString()))
			.toList();

		if (allVanillaBiomes.isEmpty()) {
			throw new AssertionError("No vanilla biomes found in Registries.BIOME");
		}

		List<Holder.Reference<Biome>> overworldBiomes = new ArrayList<>();
		List<Holder.Reference<Biome>> nonOverworldBiomes = new ArrayList<>();

		for (Holder.Reference<Biome> holder : allVanillaBiomes) {
			ResourceKey<Biome> key = holder.key();
			boolean inOverworldTag = holder.is(BiomeTags.IS_OVERWORLD);
			boolean inOverworldParams = overworldParameterBiomes.contains(key);
			if (inOverworldTag || inOverworldParams) {
				if (!inOverworldTag || !inOverworldParams) {
					throw new AssertionError(
						"Vanilla biome " + key.location() + " has inconsistent Overworld registration: "
							+ "is_overworld=" + inOverworldTag + ", overworldParameterList=" + inOverworldParams
					);
				}
				overworldBiomes.add(holder);
			} else if (holder.is(BiomeTags.IS_NETHER) || holder.is(BiomeTags.IS_END) || key.equals(Biomes.THE_VOID)) {
				nonOverworldBiomes.add(holder);
			} else {
				throw new AssertionError("Unclassified vanilla biome in Registries.BIOME: " + key.location());
			}
		}

		BlockPos origin = level.getSharedSpawnPos();
		CommandSourceStack source = server.createCommandSourceStack()
			.withLevel(level)
			.withPosition(Vec3.atCenterOf(origin))
			.withSuppressedOutput();

		// 1. Verify non-Overworld vanilla biomes cleanly report impossible via /ftf locate biome
		for (Holder.Reference<Biome> nonOverworld : nonOverworldBiomes) {
			String biomeId = nonOverworld.key().location().toString();
			java.util.concurrent.atomic.AtomicInteger cmdResult = new java.util.concurrent.atomic.AtomicInteger(-1);
			CommandSourceStack callSource = source.withCallback((success, res) -> cmdResult.set(success ? res : 0));
			server.getCommands().performPrefixedCommand(
				callSource,
				"ftf locate biome " + biomeId + " --pause-main-thread --timeout 5"
			);
			BiomeLocateSearch.Result locateResult = LocateBiomeCommand.lastCompletedResult();
			if (cmdResult.get() != 0 || locateResult == null || locateResult.targetPossible()) {
				throw new AssertionError(
					"Expected non-Overworld vanilla biome " + biomeId + " to be rejected as impossible, "
						+ "but got cmdResult=" + cmdResult.get() + ", locateResult=" + locateResult
				);
			}
		}

		// 2. Locate every vanilla Overworld biome via /ftf locate biome and verify minimum area (>= 12x12 blocks)
		List<BiomeEntryResult> entries = new ArrayList<>(overworldBiomes.size());
		List<String> failures = new ArrayList<>();
		long suiteStartNanos = System.nanoTime();
		long maxMillis = 0L;
		String slowestBiome = "";

		for (int i = 0; i < overworldBiomes.size(); i++) {
			Holder.Reference<Biome> biomeHolder = overworldBiomes.get(i);
			String biomeId = biomeHolder.key().location().toString();
			if (progressCallback != null) {
				progressCallback.accept(
					"presetValidation.allVanillaBiomesCoverage." + loader
						+ " [" + (i + 1) + "/" + overworldBiomes.size() + " " + biomeId + "]"
				);
			}

			long startNanos = System.nanoTime();
			java.util.concurrent.atomic.AtomicInteger cmdStatus = new java.util.concurrent.atomic.AtomicInteger(-1);
			CommandSourceStack callSource = source.withCallback((success, res) -> cmdStatus.set(success ? res : 0));
			server.getCommands().performPrefixedCommand(
				callSource,
				"ftf locate biome " + biomeId + " --pause-main-thread --timeout " + PER_BIOME_TIMEOUT_SECONDS
			);
			long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
			if (elapsedMillis > maxMillis) {
				maxMillis = elapsedMillis;
				slowestBiome = biomeId;
			}

			BiomeLocateSearch.Result result = LocateBiomeCommand.lastCompletedResult();
			if (cmdStatus.get() != 1 || result == null || !result.targetPossible() || result.timedOut() || result.match() == null) {
				String reason = result == null ? "no result"
					: !result.targetPossible() ? "marked impossible"
					: result.timedOut() ? "timed out after " + elapsedMillis + "ms (" + result.columns() + " cols, step "
						+ result.plan().resolution().horizontalStep() + "x" + result.plan().resolution().verticalStep() + ")"
					: "not found (" + result.columns() + " cols)";
				String msg = biomeId + " failed locate (" + reason + ")";
				FTFCommon.LOGGER.error("[FTF-Test] FAILED biome coverage for {}: {}", biomeId, reason);
				failures.add(msg);
				entries.add(new BiomeEntryResult(
					biomeId, false, null, elapsedMillis,
					result != null ? result.columns() : 0L,
					result != null ? result.plan().resolution().horizontalStep() : 0,
					reason
				));
				continue;
			}

			BiomeLocateSearch.Match match = result.match();
			if (!biomeHolder.equals(match.biome())) {
				String actualId = match.biome().unwrapKey().map(k -> k.location().toString()).orElse("<unknown>");
				String msg = biomeId + " resolved wrong biome: " + actualId;
				failures.add(msg);
				entries.add(new BiomeEntryResult(
					biomeId, false, match.position(), elapsedMillis,
					result.columns(), result.plan().resolution().horizontalStep(), msg
				));
				continue;
			}

			if (!LocateBiomeCommand.verifyMinimumBiomeArea(query, result)) {
				String msg = biomeId + " at " + match.position() + " failed >= "
					+ LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS + "x" + LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS
					+ " block area check";
				failures.add(msg);
				entries.add(new BiomeEntryResult(
					biomeId, false, match.position(), elapsedMillis,
					result.columns(), result.plan().resolution().horizontalStep(), msg
				));
				continue;
			}

			FTFCommon.LOGGER.info(
				"[FTF-Test] Verified vanilla biome [{}/{}] {} @ {} in {} ms ({} cols, step {}x{}, >= {}x{} area)",
				i + 1,
				overworldBiomes.size(),
				biomeId,
				match.position(),
				elapsedMillis,
				result.columns(),
				result.plan().resolution().horizontalStep(),
				result.plan().resolution().verticalStep(),
				LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS,
				LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS
			);
			entries.add(new BiomeEntryResult(
				biomeId,
				true,
				match.position(),
				elapsedMillis,
				result.columns(),
				result.plan().resolution().horizontalStep(),
				"OK"
			));
		}

		long totalMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - suiteStartNanos);
		writeDetailedReport(loader, allVanillaBiomes.size(), overworldBiomes.size(), nonOverworldBiomes.size(), entries, totalMillis, failures);

		if (!failures.isEmpty()) {
			throw new AssertionError(
				String.format(
					Locale.ROOT,
					"%d / %d vanilla Overworld biomes failed >= %dx%d area locate check: %s",
					failures.size(),
					overworldBiomes.size(),
					LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS,
					LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS,
					String.join("; ", failures)
				)
			);
		}

		return new Result(
			allVanillaBiomes.size(),
			overworldBiomes.size(),
			nonOverworldBiomes.size(),
			totalMillis,
			maxMillis,
			slowestBiome
		);
	}

	private static void writeDetailedReport(
		String loader,
		int totalVanillaBiomes,
		int overworldBiomes,
		int nonOverworldBiomes,
		List<BiomeEntryResult> entries,
		long totalMillis,
		List<String> failures
	) {
		try {
			Path reportDir = HeightmapValidator.resolveReportDirectory();
			Files.createDirectories(reportDir);
			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			Path reportFile = reportDir.resolve(String.format("vanilla_biome_coverage_%s_%s_report.txt", loader, timestamp));
			StringBuilder sb = new StringBuilder();
			sb.append("FreeTerraForged Vanilla Biome Coverage & Area Verification Report\n");
			sb.append("=================================================================\n");
			sb.append("Timestamp: ").append(timestamp).append("\n");
			sb.append("Loader: ").append(loader).append("\n");
			sb.append("Total Registered Vanilla Biomes: ").append(totalVanillaBiomes).append("\n");
			sb.append("Overworld Biomes Verified (>= ")
				.append(LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS).append("x").append(LocateBiomeCommand.MIN_BIOME_AREA_BLOCKS)
				.append(" blocks): ").append(overworldBiomes - failures.size()).append(" / ").append(overworldBiomes).append("\n");
			sb.append("Non-Overworld Biomes Rejected: ").append(nonOverworldBiomes).append("\n");
			sb.append("Total Elapsed Time: ").append(totalMillis).append(" ms\n");
			sb.append("Outcome: ").append(failures.isEmpty() ? "PASSED" : "FAILED").append("\n\n");
			for (BiomeEntryResult entry : entries) {
				sb.append(String.format(
					Locale.ROOT,
					"- [%s] %-36s | pos=%-22s | time=%5d ms | cols=%6d | step=%3d | %s\n",
					entry.passed() ? "PASSED" : "FAILED",
					entry.biomeId(),
					entry.position() != null
						? "(" + entry.position().getX() + ", " + entry.position().getY() + ", " + entry.position().getZ() + ")"
						: "N/A",
					entry.millis(),
					entry.columns(),
					entry.step(),
					entry.detail()
				));
			}
			Files.writeString(reportFile, sb.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			FTFCommon.LOGGER.warn("[FTF-Test] Failed to write vanilla biome coverage report: {}", e.getMessage());
		}
	}

	public record BiomeEntryResult(
		String biomeId,
		boolean passed,
		BlockPos position,
		long millis,
		long columns,
		int step,
		String detail
	) {
	}

	public record Result(
		int totalVanillaBiomes,
		int overworldBiomesVerified,
		int nonOverworldBiomesRejected,
		long totalMillis,
		long maxBiomeMillis,
		String slowestBiome
	) {
	}
}
