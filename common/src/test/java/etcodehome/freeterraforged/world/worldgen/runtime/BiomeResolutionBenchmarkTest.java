package etcodehome.freeterraforged.world.worldgen.runtime;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.test.HeightmapValidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeResolutionBenchmarkTest {

	private static final int WARMUP_ITERATIONS = 10_000;
	private static final int BENCHMARK_SAMPLES = 50_000;

	@BeforeAll
	static void setUp() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@SuppressWarnings("unchecked")
	private static List<Pair<Climate.ParameterPoint, Holder<Biome>>> createOverworldCandidates() throws Exception {
		OverworldBiomeBuilder builder = new OverworldBiomeBuilder();
		List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> rawEntries = new ArrayList<>();
		Method addBiomes = OverworldBiomeBuilder.class.getDeclaredMethod("addBiomes", Consumer.class);
		addBiomes.setAccessible(true);
		addBiomes.invoke(builder, (Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>) rawEntries::add);

		List<Pair<Climate.ParameterPoint, Holder<Biome>>> candidatePairs = new ArrayList<>(rawEntries.size());
		for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> entry : rawEntries) {
			Holder<Biome> holder = Holder.Reference.createStandAlone(null, entry.getSecond());
			candidatePairs.add(Pair.of(entry.getFirst(), holder));
		}
		return candidatePairs;
	}

	@Test
	@DisplayName("Benchmark Climate.ParameterList.findValue (R-Tree) vs findValueBruteForce (Linear Scan)")
	void testClimateParameterListPerformanceBenchmark() throws Exception {
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> candidatePairs = createOverworldCandidates();
		Climate.ParameterList<Holder<Biome>> parameterList = new Climate.ParameterList<>(candidatePairs);
		int parameterCount = candidatePairs.size();
		assertTrue(parameterCount > 20, "Overworld parameter list must contain realistic candidates");

		// Deterministic grid of climate targets
		List<Climate.TargetPoint> targets = new ArrayList<>(BENCHMARK_SAMPLES);
		for (int i = 0; i < BENCHMARK_SAMPLES; i++) {
			float temp = -1.0F + 2.0F * ((i % 17) / 16.0F);
			float humid = -1.0F + 2.0F * (((i / 17) % 19) / 18.0F);
			float cont = -1.2F + 2.4F * (((i / 323) % 13) / 12.0F);
			float eros = -1.0F + 2.0F * (((i / 4199) % 11) / 10.0F);
			float depth = 0.0F;
			float weird = -1.0F + 2.0F * ((i % 23) / 22.0F);
			targets.add(Climate.target(temp, humid, cont, eros, depth, weird));
		}

		// Warmup
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			Climate.TargetPoint target = targets.get(i);
			Holder<Biome> indexed = parameterList.findValue(target);
			Holder<Biome> brute = parameterList.findValueBruteForce(target);
			assertEquals(indexed, brute, "Indexed and brute-force must return identical biomes");
		}

		// Benchmark findValue (RTree)
		long rtreeStartNanos = System.nanoTime();
		for (int i = 0; i < BENCHMARK_SAMPLES; i++) {
			Holder<Biome> result = parameterList.findValue(targets.get(i));
			assertNotNull(result);
		}
		long rtreeElapsedNanos = System.nanoTime() - rtreeStartNanos;

		// Benchmark findValueBruteForce (Linear Scan)
		long bruteStartNanos = System.nanoTime();
		for (int i = 0; i < BENCHMARK_SAMPLES; i++) {
			Holder<Biome> result = parameterList.findValueBruteForce(targets.get(i));
			assertNotNull(result);
		}
		long bruteElapsedNanos = System.nanoTime() - bruteStartNanos;

		double rtreeMillis = rtreeElapsedNanos / 1_000_000.0;
		double bruteMillis = bruteElapsedNanos / 1_000_000.0;
		double rtreeNsPerOp = (double) rtreeElapsedNanos / BENCHMARK_SAMPLES;
		double bruteNsPerOp = (double) bruteElapsedNanos / BENCHMARK_SAMPLES;
		double rtreeOpsSec = (BENCHMARK_SAMPLES / (rtreeElapsedNanos / 1_000_000_000.0));
		double bruteOpsSec = (BENCHMARK_SAMPLES / (bruteElapsedNanos / 1_000_000_000.0));
		double speedup = bruteMillis / rtreeMillis;

		String benchmarkLog = String.format(
			Locale.ROOT,
			"""
			--------------------------------------------------------------------------------
			[FTF Microbenchmark] Climate.ParameterList.findValue vs findValueBruteForce
			Candidates: %d points | Samples: %d queries
			  - findValue (R-Tree Index) : %8.2f ms | %8.1f ns/op | %10.0f ops/sec
			  - findValueBruteForce      : %8.2f ms | %8.1f ns/op | %10.0f ops/sec
			  - Speedup Ratio            : %.2fx faster with R-Tree index
			--------------------------------------------------------------------------------
			""",
			parameterCount, BENCHMARK_SAMPLES,
			rtreeMillis, rtreeNsPerOp, rtreeOpsSec,
			bruteMillis, bruteNsPerOp, bruteOpsSec,
			speedup
		);
		System.out.print(benchmarkLog);

		writeReportFile("climate_find_value_benchmark_report.txt", benchmarkLog);

		assertTrue(speedup > 1.5, "R-Tree indexed search should be substantially faster than brute force");
	}

	@Test
	@DisplayName("Benchmark WorldgenPlans.ProviderSelection.resolve throughput")
	void testProviderSelectionResolveBenchmark() throws Exception {
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> candidatePairs = createOverworldCandidates();
		Climate.ParameterList<Holder<Biome>> candidates = new Climate.ParameterList<>(candidatePairs);
		ResourceLocation domainId = ResourceLocation.fromNamespaceAndPath("freeterraforged", "test_domain");
		WorldgenPlans.ProviderDomain domain = new WorldgenPlans.ProviderDomain(domainId, 1.0D, candidates, 0);

		PlanDescriptor descriptor = new PlanDescriptor(
			ResourceLocation.fromNamespaceAndPath("freeterraforged", "test_provider"),
			WorldgenFacet.PROVIDER_SELECTION,
			CapabilityState.NORMALIZED,
			"test",
			"test",
			Optional.empty()
		);

		WorldgenPlans.ProviderSelection providerSelection = new WorldgenPlans.ProviderSelection(
			descriptor,
			1337L,
			List.of(domain),
			Optional.of(domainId),
			Optional.of(candidates),
			Optional.empty()
		);

		List<Climate.TargetPoint> targets = new ArrayList<>(BENCHMARK_SAMPLES);
		for (int i = 0; i < BENCHMARK_SAMPLES; i++) {
			float temp = -1.0F + 2.0F * ((i % 17) / 16.0F);
			float humid = -1.0F + 2.0F * (((i / 17) % 19) / 18.0F);
			float cont = -1.2F + 2.4F * (((i / 323) % 13) / 12.0F);
			float eros = -1.0F + 2.0F * (((i / 4199) % 11) / 10.0F);
			float depth = 0.0F;
			float weird = -1.0F + 2.0F * ((i % 23) / 22.0F);
			targets.add(Climate.target(temp, humid, cont, eros, depth, weird));
		}

		// Warmup
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			providerSelection.resolve(i, i, targets.get(i));
		}

		// Benchmark
		long startNanos = System.nanoTime();
		for (int i = 0; i < BENCHMARK_SAMPLES; i++) {
			Optional<WorldgenPlans.ProviderResult> result = providerSelection.resolve(i, i, targets.get(i));
			assertTrue(result.isPresent());
		}
		long elapsedNanos = System.nanoTime() - startNanos;

		double elapsedMillis = elapsedNanos / 1_000_000.0;
		double nsPerOp = (double) elapsedNanos / BENCHMARK_SAMPLES;
		double opsSec = (BENCHMARK_SAMPLES / (elapsedNanos / 1_000_000_000.0));

		String log = String.format(
			Locale.ROOT,
			"""
			--------------------------------------------------------------------------------
			[FTF Microbenchmark] WorldgenPlans.ProviderSelection.resolve
			Samples: %d queries
			Elapsed: %.2f ms | %.1f ns/op | %.0f ops/sec
			--------------------------------------------------------------------------------
			""",
			BENCHMARK_SAMPLES, elapsedMillis, nsPerOp, opsSec
		);
		System.out.print(log);

		writeReportFile("provider_selection_resolve_benchmark_report.txt", log);
	}

	private static void writeReportFile(String fileName, String content) {
		try {
			Path reportDir = HeightmapValidator.resolveReportDirectory();
			Files.createDirectories(reportDir);
			Path reportFile = reportDir.resolve(fileName);
			Files.writeString(reportFile, content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			// Non-fatal in test
		}
	}
}
