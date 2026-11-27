package etcodehome.freeterraforged.world.worldgen.geology;

import static org.junit.jupiter.api.Assertions.*;
import etcodehome.freeterraforged.data.worldgen.preset.settings.GeologySettings;
import org.junit.jupiter.api.Test;

class StructuredGeologyTest {
    private static final GeologySampler OLD = (x, y, z) -> new RockSample(RockType.STONE,
        Math.floor((y - 0.37) / 7) * 7 + 0.37);
    private static final GeologySampler YOUNG = (x, y, z) -> new RockSample(RockType.CALCITE,
        Math.floor((y - 0.125) / 11) * 11 + 0.125);

    @Test
    void disabledStructuresPreserveTheUnderlyingSampler() {
        var g = geology(GeologySettings.DISABLED);
        for (int x = -100; x < 100; x += 9) for (double y = -100.25; y < 200; y += 13.1) {
            assertEquals(OLD.sample(x, y, 47), g.sample(x, y, 47));
        }
    }

    @Test
    void faultThrowOffsetsBedsOnEitherSideOfTheFault() {
        var g = geology(GeologySettings.DISABLED.withFaultThrow(32));
        boolean up = false, down = false;
        for (int x = -2048; x < 2048; x += 17) {
            double y = 123.45;
            double observed = g.sample(x, y, 71).lowerBoundaryY();
            double raised = OLD.sample(x, y - 16, 71).lowerBoundaryY() + 16;
            double lowered = OLD.sample(x, y + 16, 71).lowerBoundaryY() - 16;
            assertTrue(Math.abs(observed - raised) < 1e-10 || Math.abs(observed - lowered) < 1e-10);
            up |= Math.abs(observed - raised) < 1e-10;
            down |= Math.abs(observed - lowered) < 1e-10;
        }
        assertTrue(up && down);
    }

    @Test
    void youngerBedsTerminateAtTheErosionSurfaceAndDikesCutBothSequences() {
        var cover = geology(GeologySettings.DISABLED.withUnconformities(true));
        int basinX = Integer.MIN_VALUE;
        int basementColumns = 0;
        for (int x = -4096; x <= 4096; x += 16) {
            if (cover.sample(x, 139, 0).type() == RockType.CALCITE) basinX = x;
            if (cover.sample(x, 300, 0).type() == RockType.STONE) basementColumns++;
        }
        assertNotEquals(Integer.MIN_VALUE, basinX, "Young cover must still exist in basins");
        assertTrue(basementColumns > 0, "Elevated terrain must also expose folded basement");
        assertEquals(RockType.STONE, cover.sample(basinX, -100, 0).type());
        double y = 300;
        while (cover.sample(basinX, y, 0).type() == RockType.CALCITE) {
            double boundary = cover.sample(basinX, y, 0).lowerBoundaryY();
            assertEquals(RockType.CALCITE, cover.sample(basinX, boundary, 0).type());
            y = Math.nextDown(boundary);
        }
        assertTrue(y > 64 && y < 140);
        var dikes = geology(GeologySettings.DEFAULT);
        int hits = 0;
        for (int x = -1024; x < 1024; x += 3) {
            RockSample rock = dikes.sample(x, 64, 37);
            if (rock.type() != RockType.GRANITE && rock.type() != RockType.ANDESITE) continue;
            assertEquals(rock.type(), dikes.sample(x, 150, 37).type());
            assertEquals(rock.type(), dikes.sample(x, 0, 37).type());
            assertNotEquals(rock.type(), dikes.sample(x, Math.nextDown(rock.lowerBoundaryY()), 37).type());
            hits++;
        }
        assertTrue(hits > 0);
    }

    @Test
    void realBedsTraverseFractionalContactsAndIntrusionsWithoutSkippingOrStalling() {
        var g = new StructuredGeology(new LayeredGeology(42, 256, 64), new LayeredGeology(47, 256, 64, 0.02F, 0.1F),
            42, 256, 64, GeologySettings.DEFAULT.withFaultThrow(23.7F));
        for (int x = -257; x < 300; x += 19) for (int z = -129; z < 200; z += 31) {
            double y = 350.125;
            int count = 0;
            while (y > -200) {
                RockSample rock = g.sample(x, y, z);
                double boundary = rock.lowerBoundaryY();
                assertTrue(Double.isFinite(boundary) && boundary <= y);
                RockSample at = g.sample(x, boundary, z);
                assertEquals(rock, at, "A boundary belongs to the material above it");
                RockSample below = g.sample(x, Math.nextDown(boundary), z);
                assertTrue(below.lowerBoundaryY() < boundary, "Every boundary must advance downward");
                y = Math.nextDown(boundary);
                assertTrue(++count < 1000);
            }
        }
    }

    @Test
    void queryOrderAndWorkerCachesCannotChangeStructures() {
        var g = geology(GeologySettings.DEFAULT);
        var task = (java.util.function.Supplier<RockSample[]>) () -> {
            RockSample[] samples = new RockSample[256];
            for (int i = 255; i >= 0; i--) samples[i] = g.sample(i * 7 - 800, i - 100.125, -i * 3);
            return samples;
        };
        var worker = java.util.concurrent.CompletableFuture.supplyAsync(task);
        RockSample[] a = task.get(), b = worker.join();
        assertArrayEquals(a, b);
        for (int i = 0; i < 256; i++) assertEquals(a[i], g.sample(i * 7 - 800, i - 100.125, -i * 3));
    }

    @Test
    void defaultOutcropsRetainVisibleDipInBothBasementAndYoungerCover() {
        var g = StructuredGeology.create(42, 1000, 64, 0.06F, 1,
            GeologySettings.DISABLED.withUnconformities(true));
        int comparable = 0, inclined = 0;
        for (int x = -2048; x <= 2048; x += 64) for (int z = -2048; z <= 2048; z += 64) {
            double y = g.sample(x, 200, z).lowerBoundaryY();
            double dx = g.sample(x + 1, 200, z).lowerBoundaryY() - y;
            double dz = g.sample(x, 200, z + 1).lowerBoundaryY() - y;
            // Skip changes of exposed bed and accent pinch-outs; measure continuous contacts.
            if (Math.abs(dx) > 1 || Math.abs(dz) > 1) continue;
            comparable++;
            if (Math.hypot(dx, dz) > 0.025) inclined++;
        }
        assertTrue(comparable > 2000);
        assertTrue(inclined > comparable * 0.7,
            "Default outcrops should rise more than one block per 40 blocks over most continuous contacts: "
                + inclined + "/" + comparable);
    }

    private static StructuredGeology geology(GeologySettings settings) {
        return new StructuredGeology(OLD, YOUNG, 42, 256, 64, settings);
    }
}
