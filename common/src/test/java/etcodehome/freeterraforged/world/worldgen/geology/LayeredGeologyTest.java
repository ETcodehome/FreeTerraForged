package etcodehome.freeterraforged.world.worldgen.geology;

import etcodehome.freeterraforged.data.worldgen.preset.settings.GeologySettings.Layers;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class LayeredGeologyTest {
    @Test
    void bedScaleResizesTheSameSequenceAtFractionalElevations() {
        int seaLevel = 64;
        var layers = new Layers(1, 0, 0, 0, 0);
        var standard = new LayeredGeology(42, 1000, seaLevel, 0, 0, layers);
        var thicker = new LayeredGeology(42, 1000, seaLevel, 0, 0, layers.withBedThicknessScale(1.75F));
        for (double offset = -128.25; offset < 128; offset += 0.5) {
            RockSample original = standard.sample(40, seaLevel + offset, -80);
            RockSample scaled = thicker.sample(40, seaLevel + offset * 1.75, -80);
            assertEquals(original.type(), scaled.type());
            assertEquals(seaLevel + (original.lowerBoundaryY() - seaLevel) * 1.75, scaled.lowerBoundaryY(), 1e-8);
        }
    }

    @Test
    void gravelSeamsCanBeThickenedOrDisabled() {
        var layers = new Layers(2, 0, 0, 1, 0);
        var standard = new LayeredGeology(42, 1000, 64, 0, 0, layers);
        var thicker = new LayeredGeology(42, 1000, 64, 0, 0, layers.withGravelThickness(4));
        var disabled = new LayeredGeology(42, 1000, 64, 0, 0, layers.withGravelThickness(0));
        int standardGravel = 0;
        int thickerGravel = 0;
        for (int y = -128; y < 512; y++) {
            if (standard.sample(40, y, -80).type() == RockType.GRAVEL) standardGravel++;
            if (thicker.sample(40, y, -80).type() == RockType.GRAVEL) thickerGravel++;
            assertNotEquals(RockType.GRAVEL, disabled.sample(40, y, -80).type());
        }
        assertTrue(standardGravel > 0);
        assertTrue(thickerGravel > standardGravel);
    }

    @Test
    void mountainAndTallWorldElevationsHaveFiniteVariedBeds() {
        GeologySampler geology = new LayeredGeology(42L, 1000, 64);
        Set<RockType> types = EnumSet.noneOf(RockType.class);
        for (int y = -1024; y <= 4096; y++) {
            RockSample rock = geology.sample(25, y, -25);
            types.add(rock.type());
            assertTrue(Double.isFinite(rock.lowerBoundaryY()));
            assertTrue(rock.lowerBoundaryY() <= y);
            assertTrue(y - rock.lowerBoundaryY() < 6 + 1e-8);
        }
        assertEquals(EnumSet.allOf(RockType.class), types);
    }

    @Test
    void bedsAndThinGravelLensesHaveExactBoundaryOwnership() {
        GeologySampler geology = new LayeredGeology(42L, 1000, 64);
        double boundary = geology.sample(25, 1024, -25).lowerBoundaryY();
        for (int i = 0; i < 100; i++) {
            RockSample above = geology.sample(25, boundary, -25);
            RockSample below = geology.sample(25, Math.nextDown(boundary), -25);
            assertEquals(boundary, above.lowerBoundaryY());
            double thickness = boundary - below.lowerBoundaryY();
            if (below.type() == RockType.GRAVEL) {
                assertTrue(thickness > 0 && thickness <= 1 + 1e-8, "Gravel thickness " + thickness);
            } else {
                assertTrue(thickness > 0 && thickness <= 6 + 1e-8, "Thickness " + thickness);
            }
            assertTrue(below.lowerBoundaryY() < above.lowerBoundaryY());
            boundary = below.lowerBoundaryY();
        }
    }

    @Test
    void seededRegionsAreIndependentOfQueryOrderAndUseNegativeCoordinatesCorrectly() {
        GeologySampler first = new LayeredGeology(42L, 1000, 64);
        GeologySampler second = new LayeredGeology(42L, 1000, 64);
        boolean seedChangesBeds = false;
        for (int x : new int[] {-2001, -1000, -1, 0, 999, 1000, 2001}) {
            RockSample expected = first.sample(x, 1000, -1700);
            second.sample(9000, -100, 10000);
            assertEquals(expected, second.sample(x, 1000, -1700));
            seedChangesBeds |= !expected.equals(new LayeredGeology(43L, 1000, 64).sample(x, 1000, -1700));
        }
        assertTrue(seedChangesBeds);
        assertEquals(first.sample(-1, 1000, -1), second.sample(-1, 1000, -1));
        assertEquals(first.sample(-999, 1000, -999), second.sample(-999, 1000, -999));
    }

    @Test
    void verticalBedSequencesDoNotRepeatEveryThirtyTwoBlocks() {
        LayeredGeology geology = new LayeredGeology(42, 1000, 64, 0, 0);
        Set<String> sequences = new HashSet<>();
        for (int band = 0; band < 32; band++) {
            StringBuilder signature = new StringBuilder();
            for (int y = 0; y < 32; y++) {
                RockSample rock = geology.sample(100, 64 + band * 32 + y, 100);
                signature.append(rock.type()).append(';');
            }
            sequences.add(signature.toString());
        }
        assertTrue(sequences.size() >= 28);
    }

    @Test
    void provinceContactsDoNotFollowTheOldSquareGrid() {
        LayeredGeology geology = new LayeredGeology(42, 1000, 64);
        int changes = 0, samples = 0;
        for (int z = -3000; z <= 3000; z += 20) {
            if (geology.regionAt(999, z) != geology.regionAt(1000, z)) changes++;
            samples++;
        }
        assertTrue(changes < samples / 10, "A whole grid edge must not be a contact");
        Set<Long> provinces = new HashSet<>();
        for (int x = -3000; x < 3000; x += 200) {
            for (int z = -3000; z < 3000; z += 200) provinces.add(geology.regionAt(x, z));
        }
        assertTrue(provinces.size() > 20);
    }

    @Test
    void tiltProducesAnInclineAndFoldingProducesCurvature() {
        LayeredGeology tilted = new LayeredGeology(42, 1000, 64, 0.06F, 0);
        double slopeX = (tilted.referenceY(1000, 0) - tilted.referenceY(0, 0)) / 1000;
        double slopeZ = (tilted.referenceY(0, 1000) - tilted.referenceY(0, 0)) / 1000;
        assertEquals(0.06, Math.hypot(slopeX, slopeZ), 1e-7);
        assertEquals(tilted.referenceY(0, 0) + tilted.referenceY(1000, 0), 2 * tilted.referenceY(500, 0), 1e-7);
        LayeredGeology folded = new LayeredGeology(42, 1000, 64, 0, 1);
        assertTrue(Math.abs(folded.referenceY(0, 0) + folded.referenceY(1000, 0)
            - 2 * folded.referenceY(500, 0)) > 1);
        for (int x = -2000; x <= 2000; x += 100) {
            assertTrue(Math.abs(folded.referenceY(x + 1, 300) - folded.referenceY(x, 300)) < 1.0);
        }
        LayeredGeology flat = new LayeredGeology(42, 1000, 64, 0, 0);
        assertEquals(64, flat.referenceY(1200, -2300));
    }

    @Test
    void strongerTiltAndWarpedFoldsRemainContinuousAndScaleWithControl() {
        LayeredGeology steep = new LayeredGeology(42, 1000, 64, 1, 0);
        double slopeX = (steep.referenceY(1000, 0) - steep.referenceY(0, 0)) / 1000;
        double slopeZ = (steep.referenceY(0, 1000) - steep.referenceY(0, 0)) / 1000;
        assertEquals(1, Math.hypot(slopeX, slopeZ), 1e-7);
        for (int size : new int[] {64, 1000, 10000}) {
            LayeredGeology folded = new LayeredGeology(42, size, 64, 0, 1);
            LayeredGeology doubled = new LayeredGeology(42, size, 64, 0, 2);
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int x = -size * 2; x <= size * 2; x += Math.max(1, size / 100)) {
                double y = folded.referenceY(x, size / 3);
                min = Math.min(min, y);
                max = Math.max(max, y);
                assertEquals((y - 64) * 2, doubled.referenceY(x, size / 3) - 64, 1e-7);
                assertTrue(Math.abs(doubled.referenceY(x + 1, size / 3) - doubled.referenceY(x, size / 3)) < 3);
            }
            assertTrue(max - min > Math.min(96, size * 0.12), "Folds must have visible relief");
        }
    }

    @Test
    void provincesHaveDominantColorFamiliesAndThinForeignSeams() {
        Set<Set<RockType>> families = new HashSet<>();
        Set<RockType> all = EnumSet.noneOf(RockType.class);
        for (int z = -3000; z <= 3000 && families.size() < 4; z += 500) {
            for (int x = -5000; x <= 5000; x += 256) {
                LayeredGeology geology = new LayeredGeology(42, 1000, 64, 0, 0);
                long province = geology.regionAt(x, z);
                boolean interior = true;
                for (int dx : new int[] {-192, 0, 192}) {
                    for (int dz : new int[] {-192, 0, 192}) interior &= geology.regionAt(x + dx, z + dz) == province;
                }
                if (!interior) continue;
                Map<RockType, Integer> counts = new HashMap<>();
                for (int y = -1024; y < 4096; y++) {
                    RockType type = geology.sample(x, y, z).type();
                    all.add(type);
                    counts.merge(type, 1, Integer::sum);
                }
                Set<RockType> dominant = EnumSet.noneOf(RockType.class);
                for (RockType type : RockType.values()) {
                    if (type != RockType.GRAVEL && counts.getOrDefault(type, 0) > 512) dominant.add(type);
                }
                assertEquals(paletteFor(province), dominant);
                families.add(dominant);
                int local = dominant.stream().mapToInt(type -> counts.getOrDefault(type, 0)).sum();
                assertTrue(local > 4096, "At least 80% of the column should belong to its province palette");
                double boundary = geology.sample(x, 4096, z).lowerBoundaryY();
                for (int i = 0; i < 500; i++) {
                    RockSample below = geology.sample(x, Math.nextDown(boundary), z);
                    if (below.type() != RockType.GRAVEL && !dominant.contains(below.type())) {
                        assertTrue(boundary - below.lowerBoundaryY() <= 2 + 1e-8);
                    }
                    boundary = below.lowerBoundaryY();
                }
            }
        }
        assertEquals(4, families.size());
        assertEquals(EnumSet.allOf(RockType.class), all);
    }


    static Set<RockType> paletteFor(long province) {
        return switch (LayeredGeology.paletteIndex(province)) {
            case 0 -> Set.of(RockType.STONE, RockType.ANDESITE, RockType.TUFF);
            case 1 -> Set.of(RockType.DIORITE, RockType.CALCITE);
            case 2 -> Set.of(RockType.GRANITE, RockType.DRIPSTONE, RockType.PACKED_MUD);
            case 3 -> Set.of(RockType.BLACKSTONE, RockType.DEEPSLATE, RockType.BASALT);
            default -> throw new AssertionError("Unexpected province palette");
        };
    }

    @Test
    void provinceBoundaryMixesBothPalettesOnBothSidesInCoherentPatches() {
        LayeredGeology geology = new LayeredGeology(42, 1000, 64, 0, 0);
        int border = Integer.MIN_VALUE;
        for (int x = -3000; x < 3000; x++) {
            long left = geology.regionAt(x, 400), right = geology.regionAt(x + 1, 400);
            if (left != right && !paletteFor(left).equals(paletteFor(right))
                && geology.regionAt(x - 192, 400) == left && geology.regionAt(x + 192, 400) == right) {
                border = x;
                break;
            }
        }
        assertNotEquals(Integer.MIN_VALUE, border);
        Set<RockType> left = paletteFor(geology.regionAt(border, 400));
        Set<RockType> right = paletteFor(geology.regionAt(border + 1, 400));
        for (int x : new int[] {border - 12, border + 12}) {
            int leftCount = 0, rightCount = 0;
            for (int y = -1024; y < 4096; y++) {
                RockType type = geology.sample(x, y, 400).type();
                if (left.contains(type)) leftCount++;
                if (right.contains(type)) rightCount++;
            }
            assertTrue(leftCount > 768 && rightCount > 768, "Both sides must mix neighboring palettes");
        }
        // The top of a flat band always belongs to its host bed. Count horizontal patch edges.
        int changes = 0;
        for (int band = 0; band < 32; band++) {
            RockType previous = geology.sample(border - 96, 64 + band * 32 + 31, 400).type();
            for (int x = border - 95; x <= border + 96; x++) {
                RockType current = geology.sample(x, 64 + band * 32 + 31, 400).type();
                if (current != previous) changes++;
                previous = current;
            }
        }
        assertTrue(changes > 32, "Transition should have interleaving patches");
        assertTrue(changes < 32 * 192 / 10, "Mixing must not produce block-by-block speckles");
    }

    @Test
    void blendedBedHeightsAndSharedSeamsStayContinuousAcrossContactsAndSmallRegions() {
        int contacts = 0;
        for (int size : new int[] {64, 1000}) {
            LayeredGeology geology = new LayeredGeology(42, size, 64, 0, 0);
            for (int z = -size; z <= size; z += Math.max(1, size / 10)) {
                for (int x = -size * 2; x < size * 2; x++) {
                    // Global gravel/contrast thickness makes this a probe of the interpolated bed height.
                    double left = geology.sample(x, 95.9, z).lowerBoundaryY();
                    double right = geology.sample(x + 1, 95.9, z).lowerBoundaryY();
                    assertTrue(Math.abs(left - right) < 1, "A bed must not jump at a province or site-window edge");
                    if (geology.regionAt(x, z) == geology.regionAt(x + 1, z)) continue;
                    contacts++;
                    for (int y = -32; y < 512; y += 32) {
                        // Even beds have a fixed lower boundary, so any inserted seam is shared exactly.
                        RockSample a = geology.sample(x, y, z), b = geology.sample(x + 1, y, z);
                        if (a.type() == RockType.GRAVEL && b.type() == RockType.GRAVEL) {
                            assertEquals(a.lowerBoundaryY(), b.lowerBoundaryY(),
                                "A shared gravel lens must carry through the contact");
                        }
                    }
                }
            }
        }
        assertTrue(contacts > 50);
    }

    @Test
    void fractionalBlendedBoundariesOwnTheirExactElevationRegardlessOfQueryOrder() {
        LayeredGeology geology = new LayeredGeology(42, 1000, 64);
        int fractional = 0;
        for (int x = -1000; x < 1000; x += 7) {
            double boundary = geology.sample(x, 1024, 400).lowerBoundaryY();
            for (int i = 0; i < 32; i++) {
                RockSample above = geology.sample(x, boundary, 400);
                geology.sample(x + 1, -100, 401);
                RockSample below = geology.sample(x, Math.nextDown(boundary), 400);
                assertEquals(boundary, above.lowerBoundaryY());
                assertTrue(below.lowerBoundaryY() < boundary);
                assertTrue(boundary - below.lowerBoundaryY() <= 6 + 1e-7);
                assertEquals(above, geology.sample(x, boundary, 400));
                if (boundary != Math.rint(boundary)) fractional++;
                boundary = below.lowerBoundaryY();
            }
        }
        assertTrue(fractional > 100);
    }

    @Test
    void samplingCacheIsConfinedToEachThread() {
        GeologySampler geology = new LayeredGeology(42L, 1000, 64);
        java.util.List<java.util.concurrent.CompletableFuture<RockSample>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 64; i++) {
            int x = i * 997 - 32000, y = i * 19 - 300, z = i * -1023;
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                RockSample expected = new LayeredGeology(42L, 1000, 64).sample(x, y, z);
                RockSample actual = geology.sample(x, y, z);
                assertEquals(expected, actual);
                return actual;
            }));
        }
        futures.forEach(java.util.concurrent.CompletableFuture::join);
    }

}
