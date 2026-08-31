package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class PreviewRequestPoolTest {
    @Test
    void pagesShareOneOwnerForTheCompleteSemanticKey() {
        PreviewRequestPool pool = new PreviewRequestPool();
        BiomePreview.CacheKey key = key(1L, "preset-a");
        AtomicInteger creations = new AtomicInteger();

        IPreviewHandler.PreparedContext.Lease first = pool.acquire(key, true, () -> owner(key, true, creations));
        IPreviewHandler.PreparedContext.Lease second = pool.acquire(key, true, () -> owner(key, true, creations));

        assertEquals(1, creations.get());
        assertSame(first.owner(), second.owner());
        first.close();
        second.close();
        pool.close();
    }

    @Test
    void semanticChangeClosesOnlyTheReplacedPipelineOwner() {
        PreviewRequestPool pool = new PreviewRequestPool();
        BiomePreview.CacheKey firstKey = key(1L, "preset-a");
        BiomePreview.CacheKey secondKey = key(2L, "preset-b");
        AtomicInteger creations = new AtomicInteger();
        IPreviewHandler.PreparedContext.Lease first = pool.acquire(
                firstKey, true, () -> owner(firstKey, true, creations)
        );
        IPreviewHandler.PreparedContext firstOwner = first.owner();

        IPreviewHandler.PreparedContext.Lease second = pool.acquire(
                secondKey, true, () -> owner(secondKey, true, creations)
        );

        assertEquals(2, creations.get());
        assertThrows(CancellationException.class, firstOwner::acquire);
        first.close();
        second.close();
        pool.close();
    }

    private static IPreviewHandler.PreparedContext owner(
            BiomePreview.CacheKey key,
            boolean biomePipeline,
            AtomicInteger creations
    ) {
        creations.incrementAndGet();
        return new IPreviewHandler.PreparedContext(key, null, null, null, biomePipeline);
    }

    private static BiomePreview.CacheKey key(long seed, String preset) {
		return new BiomePreview.CacheKey(
				seed, preset, "data", "graph", "tags", 1L, List.of("minecraft:plains")
		);
    }
}
