package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.biome.Climate;

class ClimatePointCacheTest {
	@Test
	void cacheIsScopedBySamplerIdentityAndCoordinates() {
		Object sampler = new Object();
		Climate.TargetPoint target = target(1L);

		ClimatePointCache.store(sampler, 11, -7, 23, target);

		assertSame(target, ClimatePointCache.find(sampler, 11, -7, 23));
		assertNull(ClimatePointCache.find(new Object(), 11, -7, 23));
		assertNull(ClimatePointCache.find(sampler, 12, -7, 23));
	}

	@Test
	void cacheDoesNotLeakAcrossThreads() throws InterruptedException {
		Object sampler = new Object();
		Climate.TargetPoint target = target(2L);
		AtomicReference<Climate.TargetPoint> otherThreadResult = new AtomicReference<>();

		ClimatePointCache.store(sampler, 3, 5, 7, target);
		Thread thread = new Thread(
			() -> otherThreadResult.set(ClimatePointCache.find(sampler, 3, 5, 7)),
			"climate-point-cache-test"
		);
		thread.start();
		thread.join();

		assertNull(otherThreadResult.get());
		assertSame(target, ClimatePointCache.find(sampler, 3, 5, 7));
	}

	private static Climate.TargetPoint target(long value) {
		return new Climate.TargetPoint(value, value, value, value, value, value);
	}
}
