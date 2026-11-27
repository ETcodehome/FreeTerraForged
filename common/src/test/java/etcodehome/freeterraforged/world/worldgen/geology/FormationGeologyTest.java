package etcodehome.freeterraforged.world.worldgen.geology;

import static org.junit.jupiter.api.Assertions.*;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormationGeologyTest {
    private Map<LayeredGeology.FormationStyle, int[]> interiors(LayeredGeology geology) {
        Map<LayeredGeology.FormationStyle, int[]> result = new EnumMap<>(LayeredGeology.FormationStyle.class);
        for (int z = -4000; z <= 4000 && result.size() < 3; z += 256) {
            for (int x = -4000; x <= 4000; x += 256) {
                long province = geology.regionAt(x, z);
                boolean interior = true;
                for (int dx : new int[] {-192, 0, 192}) {
                    for (int dz : new int[] {-192, 0, 192}) interior &= geology.regionAt(x + dx, z + dz) == province;
                }
                if (interior) result.putIfAbsent(LayeredGeology.formationFor(province), new int[] {x, z});
            }
        }
        assertEquals(3, result.size());
        return result;
    }

    private int changes(LayeredGeology geology, int[] column) {
        int changes = 0;
        RockType previous = geology.sample(column[0], 0, column[1]).type();
        for (int y = 1; y < 2048; y++) {
            RockType current = geology.sample(column[0], y, column[1]).type();
            if (current != previous) changes++;
            previous = current;
        }
        return changes;
    }

    @Test
    void massiveProvincesHaveFewerVisibleLayerChangesThanBeddedProvinces() {
        LayeredGeology geology = new LayeredGeology(42, 1000, 64, 0, 0);
        var columns = interiors(geology);
        int massive = changes(geology, columns.get(LayeredGeology.FormationStyle.MASSIVE));
        int bedded = changes(geology, columns.get(LayeredGeology.FormationStyle.BEDDED));
        assertTrue(massive < bedded * 0.75, "Massive formations must have longer continuous rock units");
    }

    @Test
    void relatedStonesVaryInsideProvinceInteriorsWithoutBlockSpeckling() {
        LayeredGeology geology = new LayeredGeology(42, 1000, 64, 0, 0);
        int[] center = interiors(geology).get(LayeredGeology.FormationStyle.BEDDED);
        int changes = 0;
        for (int band = 0; band < 64; band++) {
            RockType previous = geology.sample(center[0] - 64, 64 + band * 32 + 31, center[1]).type();
            for (int x = center[0] - 63; x <= center[0] + 64; x++) {
                RockType current = geology.sample(x, 64 + band * 32 + 31, center[1]).type();
                if (current != previous) changes++;
                previous = current;
            }
        }
        assertTrue(changes > 0);
        assertTrue(changes < 64 * 128 / 10);
    }

    @Test
    void foldedRegionsHaveStrongerLocalDeformationThanMassiveRegions() {
        LayeredGeology flat = new LayeredGeology(42, 1000, 64, 0, 0);
        LayeredGeology folded = new LayeredGeology(42, 1000, 64, 0, 1);
        var columns = interiors(flat);
        double massive = foldEnergy(folded, columns.get(LayeredGeology.FormationStyle.MASSIVE));
        double strong = foldEnergy(folded, columns.get(LayeredGeology.FormationStyle.FOLDED));
        assertTrue(strong > massive * 2);
    }

    private double foldEnergy(LayeredGeology geology, int[] center) {
        double energy = 0;
        for (int dx = -64; dx <= 64; dx += 8) {
            for (int dz = -64; dz <= 64; dz += 8) {
                int x = center[0] + dx, z = center[1] + dz;
                double y = geology.referenceY(x, z);
                double sx = geology.referenceY(x + 1, z) - y;
                double sz = geology.referenceY(x, z + 1) - y;
                energy += sx * sx + sz * sz;
            }
        }
        return energy;
    }

    @Test
    void accentsRemainVisibleWhenSamplingWholeBlocksOnCliffs() {
        LayeredGeology geology = new LayeredGeology(42, 1000, 64, 0, 0);
        int sampled = 0, accents = 0, thickAccents = 0;
        for (int[] center : interiors(geology).values()) {
            long province = geology.regionAt(center[0], center[1]);
            var host = LayeredGeologyTest.paletteFor(province);
            for (int y = -1024; y < 4096; y++) {
                RockType type = geology.sample(center[0], y, center[1]).type();
                sampled++;
                if (type == RockType.GRAVEL || host.contains(type)) continue;
                accents++;
                if (geology.sample(center[0], y + 1, center[1]).type() == type) thickAccents++;
            }
        }
        assertTrue(accents > sampled * 0.025, "Accents should occupy a measurable fraction of block samples");
        assertTrue(thickAccents > 20, "Some accents must survive as two-block strips");
    }

    @Test
    void gravelLensesThinContinuouslyAndDisappearAlongABed() {
        LayeredGeology geology = new LayeredGeology(42, 1000, 64, 0, 0);
        boolean pinchesOut = false;
        int fractional = 0;
        for (int band = 0; band < 32; band++) {
            boolean present = false, absent = false;
            double base = 64 + band * 32;
            for (int x = -512; x <= 512; x += 8) {
                boolean found = false;
                double upper = base + 32;
                while (upper > base) {
                    RockSample rock = geology.sample(x, Math.nextDown(upper), 400);
                    assertTrue(rock.lowerBoundaryY() < upper);
                    if (rock.type() == RockType.GRAVEL) {
                        double thickness = upper - rock.lowerBoundaryY();
                        assertTrue(thickness <= 1 + 1e-7);
                        if (thickness < 0.99) fractional++;
                        found = true;
                    }
                    upper = rock.lowerBoundaryY();
                }
                present |= found;
                absent |= !found;
            }
            pinchesOut |= present && absent;
        }
        assertTrue(pinchesOut, "A lens must actually terminate horizontally");
        assertTrue(fractional > 100);
    }
}
