package etcodehome.freeterraforged.world.worldgen.biome;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.mojang.datafixers.util.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenMemoryLeakTest {

	@BeforeAll
	static void setUpRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Climate.Parameter FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);

	private static Climate.ParameterPoint point(float depthMin, float depthMax) {
		return new Climate.ParameterPoint(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			Climate.Parameter.span(depthMin, depthMax), FULL_RANGE, 0L
		);
	}

	@Test
	void testCoverageFactorDoesNotRetainSamplerStrongly() throws Exception {
		Climate.Sampler[] samplerHolder = new Climate.Sampler[]{ Climate.empty() };
		WeakReference<Climate.Sampler> samplerRef = new WeakReference<>(samplerHolder[0]);

		Climate.TargetPoint target = Climate.target(0.0F, 0.0F, 0.0F, -0.5F, 1.5F, 0.0F);

		int threadCount = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);

		for (int t = 0; t < threadCount; t++) {
			executor.submit(() -> {
				try {
					for (int i = 0; i < 100; i++) {
						UndergroundBiomeSurfaceProtection.coverageFactor(samplerHolder[0], target, i * 4, -50, i * 4);
					}
				} finally {
					latch.countDown();
				}
			});
		}

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Threads should finish queries promptly");

		// Null out strong reference while worker threads are STILL ALIVE and idle!
		samplerHolder[0] = null;

		// Trigger GC to ensure WeakReference is collected while threads are still alive
		assertGarbageCollected(samplerRef, "Climate.Sampler must be garbage collected and not retained by COVERAGE_MRU while threads are alive");

		executor.shutdown();
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate");
	}

	@Test
	@SuppressWarnings("unchecked")
	void testUndergroundBandingLayoutDoesNotRetainCandidatesInThreadLocal() throws Exception {
		Preset preset = Presets.makeFTFDefault();
		preset.climate().biomeShape.undergroundBiomeClimateInfluence = 0.75F;
		preset.climate().biomeShape.undergroundBiomeBanding = true;

		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			Pair.of(point(-1.0F, 0.0F), "surface_plains"),
			Pair.of(point(0.2F, 0.9F), "lush_caves"),
			Pair.of(point(0.2F, 0.9F), "dripstone_caves"),
			Pair.of(point(1.1F, 1.1F), "deep_dark")
		);

		UndergroundBiomeBanding.Layout<String>[] layoutHolder = new UndergroundBiomeBanding.Layout[]{
			UndergroundBiomeBanding.apply(
				preset, entries, 42L,
				(point, value) -> UndergroundBiomeBanding.classify(point, value.contains("caves") || value.contains("dark")),
				value -> value.contains("caves") || value.contains("dark")
			)
		};
		WeakReference<UndergroundBiomeBanding.Layout<String>> layoutRef = new WeakReference<>(layoutHolder[0]);

		Climate.TargetPoint target = Climate.target(0.0F, 0.0F, 0.0F, -0.5F, 1.5F, 0.0F);

		int threadCount = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);

		for (int t = 0; t < threadCount; t++) {
			executor.submit(() -> {
				try {
					for (int i = 0; i < 100; i++) {
						String val = layoutHolder[0].findValue(target, i * 4, -100, i * 4, 1.0F);
						assertNotNull(val);
					}
				} finally {
					latch.countDown();
				}
			});
		}

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Threads should finish queries promptly");

		// Null out strong reference while worker threads are STILL ALIVE and idle!
		layoutHolder[0] = null;

		// Trigger GC to ensure WeakReference is collected while threads are still alive
		assertGarbageCollected(layoutRef, "Layout must be garbage collected and not retained in ThreadLocal or caches while threads are alive");

		executor.shutdown();
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate");
	}

	private static void assertGarbageCollected(WeakReference<?> ref, String message) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			if (ref.get() == null) {
				return;
			}
			System.gc();
			Thread.sleep(50);
		}
		assertNull(ref.get(), message);
	}
}
