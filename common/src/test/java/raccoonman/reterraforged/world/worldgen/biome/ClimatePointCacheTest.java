package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.biome.Climate;

class ClimatePointCacheTest {
	@Test
	void cacheIsScopedByInstanceAndCoordinates() {
		ClimatePointCache cache = new ClimatePointCache();
		ClimatePointCache other = new ClimatePointCache();
		Climate.TargetPoint target = target(1L);
		Object sampler = new Object();
		cache.store(sampler, 11, -7, 23, target);

		assertSame(target, cache.find(sampler, 11, -7, 23));
		assertNull(other.find(sampler, 11, -7, 23));
		assertNull(cache.find(sampler, 12, -7, 23));
		assertNull(cache.find(new Object(), 11, -7, 23));
	}

	@Test
	void cacheIsSafeToShareWithSamplerAcrossThreads() throws InterruptedException {
		ClimatePointCache cache = new ClimatePointCache();
		Climate.TargetPoint target = target(2L);
		Object sampler = new Object();
		AtomicReference<Climate.TargetPoint> otherThreadResult = new AtomicReference<>();

		cache.store(sampler, 3, 5, 7, target);
		Thread thread = new Thread(
			() -> otherThreadResult.set(cache.find(sampler, 3, 5, 7)),
			"climate-point-cache-test"
		);
		thread.start();
		thread.join();

		assertSame(target, otherThreadResult.get());
		assertSame(target, cache.find(sampler, 3, 5, 7));
	}

	private static Climate.TargetPoint target(long value) {
		return new Climate.TargetPoint(value, value, value, value, value, value);
	}
}
