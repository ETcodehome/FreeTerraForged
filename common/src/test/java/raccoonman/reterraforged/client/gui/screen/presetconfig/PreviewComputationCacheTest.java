package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class PreviewComputationCacheTest {
    @Test
    void sidecarResultsAreComputedOnceForAnExactViewKey() {
        PreviewComputationCache cache = new PreviewComputationCache();
		BiomePreview.CacheKey revision = new BiomePreview.CacheKey(
			123L, "preset", "data", "selected-graph", "tags", 1L, List.of("minecraft:plains")
		);
        PreviewComputationCache.SidecarKey key = new PreviewComputationCache.SidecarKey(revision, 10, -20, 4, 256);
        AtomicInteger computations = new AtomicInteger();
        assertNull(cache.sidecar(key, () -> {
            computations.incrementAndGet();
            return null;
        }).join());
        assertNull(cache.sidecar(key, () -> {
            computations.incrementAndGet();
            return null;
        }).join());
        assertEquals(1, computations.get());
        cache.close();
    }
}
