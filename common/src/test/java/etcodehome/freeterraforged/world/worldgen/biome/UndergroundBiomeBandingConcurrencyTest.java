package etcodehome.freeterraforged.world.worldgen.biome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.datafixers.util.Pair;
import org.junit.jupiter.api.Test;

import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndergroundBiomeBandingConcurrencyTest {

	private static final Climate.Parameter FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);

	private static Climate.ParameterPoint point(float depthMin, float depthMax) {
		return new Climate.ParameterPoint(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			Climate.Parameter.span(depthMin, depthMax), FULL_RANGE, 0L
		);
	}

	private static Climate.ParameterPoint pointExact(float depth) {
		return new Climate.ParameterPoint(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			Climate.Parameter.point(depth), FULL_RANGE, 0L
		);
	}

	private List<Pair<Climate.ParameterPoint, String>> makeTestEntries() {
		return List.of(
			Pair.of(point(-1.0F, 0.0F), "surface_plains"),
			Pair.of(point(0.2F, 0.9F), "lush_caves"),
			Pair.of(point(0.2F, 0.9F), "dripstone_caves"),
			Pair.of(pointExact(1.1F), "deep_dark")
		);
	}

	@Test
	void testHeavyMultithreadedConcurrentFindValue() throws Exception {
		Preset preset = Presets.makeFTFDefault();
		preset.world().properties.worldDepth = 512;
		preset.world().properties.worldHeight = 320;
		preset.climate().biomeShape.undergroundBiomeSize = 225;
		preset.climate().biomeShape.undergroundBiomeVerticalSize = 64;
		preset.climate().biomeShape.undergroundBiomeCoverage = 1.0F;
		preset.climate().biomeShape.undergroundBiomeClimateInfluence = 0.75F;
		preset.climate().biomeShape.undergroundBiomeBanding = true;

		long seed = 123456789012345L;
		List<Pair<Climate.ParameterPoint, String>> entries = this.makeTestEntries();
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset, entries, seed,
			(point, value) -> UndergroundBiomeBanding.classify(point, value.contains("caves") || value.contains("dark"))
		);

		assertTrue(layout.deepCandidateCount() >= 3, "Expected at least 3 deep candidates");

		AtomicInteger targetSampleCount = new AtomicInteger();
		UndergroundBiomeBanding.TargetSampler targetSampler = (ix, iy, iz) -> {
			targetSampleCount.incrementAndGet();
			return Climate.target(0.0F, 0.0F, 0.0F, -0.5F, 1.5F, 0.0F);
		};

		// 1. Compute single-threaded baseline for 2000 coordinate triplets
		int queryCount = 2000;
		int[] quartXs = new int[queryCount];
		int[] quartYs = new int[queryCount];
		int[] quartZs = new int[queryCount];
		String[] baseline = new String[queryCount];

		Climate.TargetPoint target = Climate.target(0.0F, 0.0F, 0.0F, -0.5F, 1.5F, 0.0F);
		for (int i = 0; i < queryCount; i++) {
			int qx = (i * 37) % 500;
			int qy = QuartPos.fromBlock(-96 - ((i * 16) % 350));
			int qz = (i * 73) % 500;
			quartXs[i] = qx;
			quartYs[i] = qy;
			quartZs[i] = qz;
			baseline[i] = layout.findValue(target, qx, qy, qz, 1.0F, targetSampler);
			assertNotNull(baseline[i]);
		}

		// 2. Execute parallel heavy multithreaded queries across 16 threads
		int threadCount = 16;
		int queriesPerThread = 5000;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);

		List<Future<Void>> futures = new ArrayList<>();
		for (int t = 0; t < threadCount; t++) {
			final int threadIndex = t;
			Callable<Void> task = () -> {
				readyLatch.countDown();
				startLatch.await();

				for (int q = 0; q < queriesPerThread; q++) {
					int idx = (threadIndex * 131 + q) % queryCount;
					String result = layout.findValue(
						target, quartXs[idx], quartYs[idx], quartZs[idx], 1.0F, targetSampler
					);
					assertEquals(baseline[idx], result, "Concurrent result must match baseline at index " + idx);
				}
				return null;
			};
			futures.add(executor.submit(task));
		}

		readyLatch.await();
		startLatch.countDown();

		for (Future<Void> future : futures) {
			future.get();
		}
		executor.shutdown();

		// Total queries: 2000 baseline + 80,000 parallel = 82,000 queries.
		// The targetSampler count should be small due to caching (at most a few hundred Voronoi cells visited)
		int samples = targetSampleCount.get();
		assertTrue(samples < 1000, "Cache should prevent excessive targetSampler calls, found: " + samples);
	}

	@Test
	void testColumnarStabilityWhenBandingDisabled() {
		Preset preset = Presets.makeFTFDefault();
		preset.world().properties.worldDepth = 512;
		preset.world().properties.worldHeight = 320;
		preset.climate().biomeShape.undergroundBiomeSize = 225;
		preset.climate().biomeShape.undergroundBiomeVerticalSize = 64;
		preset.climate().biomeShape.undergroundBiomeCoverage = 1.0F;
		preset.climate().biomeShape.undergroundBiomeClimateInfluence = 0.75F;
		preset.climate().biomeShape.undergroundBiomeBanding = false;

		long seed = 9876543210987L;
		List<Pair<Climate.ParameterPoint, String>> entries = this.makeTestEntries();
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset, entries, seed,
			(point, value) -> UndergroundBiomeBanding.classify(point, value.contains("caves") || value.contains("dark"))
		);

		Climate.TargetPoint target = Climate.target(0.0F, 0.0F, 0.0F, -0.5F, 1.5F, 0.0F);
		String firstBiome = null;
		for (int blockY = -96; blockY >= -448; blockY -= 16) {
			int quartY = QuartPos.fromBlock(blockY);
			String biome = layout.findValue(target, 0, quartY, 0, 1.0F);
			if (firstBiome == null) {
				firstBiome = biome;
			} else {
				assertEquals(firstBiome, biome, "When banding is disabled, a column must resolve to exactly 1 cave biome");
			}
		}
	}

	@Test
	void testVerticalStackingWhenBandingEnabled() {
		Preset preset = Presets.makeFTFDefault();
		preset.world().properties.worldDepth = 512;
		preset.world().properties.worldHeight = 320;
		preset.climate().biomeShape.undergroundBiomeSize = 225;
		preset.climate().biomeShape.undergroundBiomeVerticalSize = 64;
		preset.climate().biomeShape.undergroundBiomeCoverage = 1.0F;
		preset.climate().biomeShape.undergroundBiomeClimateInfluence = 0.75F;
		preset.climate().biomeShape.undergroundBiomeBanding = true;

		long seed = 543216789L;
		List<Pair<Climate.ParameterPoint, String>> entries = this.makeTestEntries();
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset, entries, seed,
			(point, value) -> UndergroundBiomeBanding.classify(point, value.contains("caves") || value.contains("dark"))
		);

		Climate.TargetPoint target = Climate.target(0.0F, 0.0F, 0.0F, -0.5F, 1.5F, 0.0F);
		java.util.Set<String> columnBiomes = new java.util.HashSet<>();
		for (int blockY = -96; blockY >= -448; blockY -= 64) {
			int quartY = QuartPos.fromBlock(blockY);
			columnBiomes.add(layout.findValue(target, 0, quartY, 0, 1.0F));
		}
		assertTrue(columnBiomes.size() >= 2, "Expected multiple stacked cave biomes down vertical steps, found: " + columnBiomes);
	}
}
