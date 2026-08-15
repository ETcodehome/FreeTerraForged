package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PreviewBiomeQueryContextTest {
    @Test
    void compositionIsReusedOnlyForTheMatchingQueryAndResult() {
        Object original = new Object();
        Object banded = new Object();

        PreviewBiomeQueryContext.begin(1, 2, 3);
        PreviewBiomeQueryContext.record(1, 2, 3, original, banded);
        assertTrue(PreviewBiomeQueryContext.matches(1, 2, 3, banded));
        assertFalse(PreviewBiomeQueryContext.matches(1, 2, 3, original));
        assertFalse(PreviewBiomeQueryContext.matches(1, 2, 4, banded));
        assertSame(original, PreviewBiomeQueryContext.original());
        PreviewBiomeQueryContext.end();

        assertFalse(PreviewBiomeQueryContext.matches(1, 2, 3, banded));
    }
}
