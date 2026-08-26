package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;

class UndergroundBiomeBandingTest {
	private static final float SURFACE_DEPTH = NoiseRouterData.GLOBAL_OFFSET + 0.5F;
	private static final float BOTTOM_DEPTH = 1.1F;
	private static final Climate.Parameter FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);
	private static final Climate.Parameter SHALLOW_CAVE_DEPTH = Climate.Parameter.span(0.2F, 0.9F);
	private static final Climate.Parameter BOTTOM_CAVE_DEPTH = Climate.Parameter.point(BOTTOM_DEPTH);
	private static final Set<String> VANILLA_CAVES = Set.of("dripstone", "lush", "deep_dark");

	@Test
	void protectsOneQuartThenFadesAcrossATerrainRelativeTwentyFourBlockTransition() {
		Preset preset = preset(1.0F, 1.0F, true, 225, 64);
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries(), 31L);
		float start = Climate.unquantizeCoord(layout.bandingStart());
		float fullDensity = Climate.unquantizeCoord(layout.fullDensityStart());

		assertEquals(SURFACE_DEPTH, start, 0.0001F);
		assertEquals(28.0F, (fullDensity - start) * 128.0F, 0.02F);
		assertEquals("surface", layout.findValue(target(start, 0.0F), 0, 0, 0));
		assertEquals("surface", layout.findValue(target(start + 4.0F / 128.0F, 0.0F), 0, 0, 0));
		assertTrue(VANILLA_CAVES.contains(layout.findValue(target(fullDensity, 0.0F), 0, 0, 0)));
	}

	@Test
	void zeroDensityProducesOnlyOrdinaryBackgroundBelowTheTransition() {
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset(0.0F, 0.75F, true, 225, 64),
			vanillaLikeEntries(),
			9L
		);

		for (int quartY = -64; quartY <= 64; quartY += 4) {
			for (int quartX = -128; quartX <= 128; quartX += 8) {
				assertEquals("surface", layout.findValue(target(1.5F, 0.0F), quartX, quartY, 0));
			}
		}
	}

	@Test
	void threeDimensionalSurfaceProtectionCanSuppressADeepLocalCaveWinner() {
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset(1.0F, 1.0F, true, 225, 64),
			vanillaLikeEntries(),
			31L
		);

		assertEquals(
			"surface",
			layout.findValue(target(0.9F, 0.0F), 0, 0, 0, 0.0F)
		);
	}

	@Test
	void fullDensityProducesOnlyCaveCandidatesBelowTheTransition() {
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset(1.0F, 0.75F, true, 225, 64),
			vanillaLikeEntries(),
			9L
		);

		for (int quartY = -64; quartY <= 64; quartY += 4) {
			for (int quartX = -128; quartX <= 128; quartX += 8) {
				assertTrue(VANILLA_CAVES.contains(layout.findValue(target(1.5F, 0.0F), quartX, quartY, 0)));
			}
		}
	}

	@Test
	void configuredDensityControlsOwnershipIndependentlyOfCandidateFitness() {
		float requestedDensity = 0.25F;
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset(requestedDensity, 1.0F, true, 225, 64),
			vanillaLikeEntries(),
			3216933670L
		);
		int cave = 0;
		int total = 0;

		for (int quartY = -64; quartY <= 64; quartY += 4) {
			for (int quartZ = -256; quartZ <= 256; quartZ += 8) {
				for (int quartX = -256; quartX <= 256; quartX += 8) {
					if (VANILLA_CAVES.contains(layout.findValue(target(1.5F, 0.0F), quartX, quartY, quartZ))) {
						cave++;
					}
					total++;
				}
			}
		}

		assertEquals(requestedDensity, (float) cave / total, 0.035F);
	}

	@Test
	void providerFacingOwnershipMatchesTheBiomeLayout() {
		Preset preset = preset(0.35F, 0.75F, true, 100, 48);
		long seed = 71L;
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset,
			vanillaLikeEntries(),
			seed
		);
		Climate.TargetPoint target = target(1.5F, 0.0F);

		for (int quartY = -32; quartY <= 32; quartY += 4) {
			for (int quartZ = -64; quartZ <= 64; quartZ += 4) {
				for (int quartX = -64; quartX <= 64; quartX += 4) {
					boolean selectedCave = VANILLA_CAVES.contains(
						layout.findValue(target, quartX, quartY, quartZ, 1.0F)
					);
					assertEquals(
						selectedCave,
						UndergroundBiomeBanding.allowsCaveBiome(
							preset,
							seed,
							target,
							quartX,
							quartY,
							quartZ,
							1.0F
						)
					);
				}
			}
		}
	}

	@Test
	void oneCandidateStillFormsFiniteRegionsSeparatedByBackground() {
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "lush")
		);
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset(0.25F, 1.0F, true, 100, 48),
			entries,
			17L
		);
		Set<String> selected = new HashSet<>();

		for (int quartZ = -128; quartZ <= 128; quartZ += 4) {
			for (int quartX = -128; quartX <= 128; quartX += 4) {
				selected.add(layout.findValue(target(0.9F, 0.0F), quartX, -8, quartZ));
			}
		}

		assertTrue(layout.appliesAt(target(0.9F, 0.0F)));
		assertEquals(Set.of("surface", "lush"), selected);
	}

	@Test
	void horizontalSizeChangesRegionFrequencyWithoutMateriallyChangingDensity() {
		LineStatistics small = horizontalStatistics(preset(0.35F, 0.75F, true, 50, 64), 4L);
		LineStatistics large = horizontalStatistics(preset(0.35F, 0.75F, true, 900, 64), 4L);

		assertTrue(small.transitions() > large.transitions() * 3);
		assertEquals(small.density(), large.density(), 0.08F);
	}

	@Test
	void verticalSizeChangesVerticalFrequencyWithoutMateriallyChangingDensity() {
		LineStatistics thin = verticalStatistics(preset(0.35F, 0.75F, true, 225, 16), 8L);
		LineStatistics thick = verticalStatistics(preset(0.35F, 0.75F, true, 225, 256), 8L);

		assertTrue(thin.transitions() > thick.transitions() * 3);
		assertEquals(thin.density(), thick.density(), 0.10F);
	}

	@Test
	void bandingToggleControlsVerticalIdentityButNotOwnership() {
		List<Pair<Climate.ParameterPoint, String>> entries = equalCaveEntries();
		UndergroundBiomeBanding.Layout<String> banded = UndergroundBiomeBanding.apply(
			preset(1.0F, 0.0F, true, 100, 16),
			entries,
			44L
		);
		UndergroundBiomeBanding.Layout<String> columnar = UndergroundBiomeBanding.apply(
			preset(1.0F, 0.0F, false, 100, 16),
			entries,
			44L
		);
		Set<String> bandedValues = new HashSet<>();
		Set<String> columnarValues = new HashSet<>();

		for (int quartY = -256; quartY <= 256; quartY++) {
			bandedValues.add(banded.findValue(target(0.9F, 0.0F), 11, quartY, -7));
			columnarValues.add(columnar.findValue(target(0.9F, 0.0F), 11, quartY, -7));
		}

		assertEquals(Set.of("first", "second"), bandedValues);
		assertEquals(1, columnarValues.size());
	}

	@Test
	void climateInfluenceRangesFromEqualParticipationToNearestCandidateOnly() {
		Climate.Parameter preferred = Climate.Parameter.span(0.7F, 1.0F);
		Climate.Parameter alternative = Climate.Parameter.span(0.4F, 0.6F);
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, preferred, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "preferred"),
			entry(FULL_RANGE, alternative, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "alternative")
		);
		UndergroundBiomeBanding.Layout<String> uninfluenced = UndergroundBiomeBanding.apply(
			preset(1.0F, 0.0F, true, 100, 48), entries, 13L
		);
		UndergroundBiomeBanding.Layout<String> calibrated = UndergroundBiomeBanding.apply(
			preset(1.0F, 0.75F, true, 100, 48), entries, 13L
		);
		UndergroundBiomeBanding.Layout<String> nearlyFull = UndergroundBiomeBanding.apply(
			preset(1.0F, 0.99F, true, 100, 48), entries, 13L
		);
		UndergroundBiomeBanding.Layout<String> full = UndergroundBiomeBanding.apply(
			preset(1.0F, 1.0F, true, 100, 48), entries, 13L
		);
		int uninfluencedAlternatives = 0;
		int calibratedAlternatives = 0;
		int nearlyFullAlternatives = 0;
		int total = 0;

		for (int quartZ = -128; quartZ <= 128; quartZ += 4) {
			for (int quartX = -128; quartX <= 128; quartX += 4) {
				Climate.TargetPoint target = target(0.9F, 0.0F, 0.0F, 0.9F, 0.0F);
				uninfluencedAlternatives += "alternative".equals(uninfluenced.findValue(target, quartX, 0, quartZ)) ? 1 : 0;
				calibratedAlternatives += "alternative".equals(calibrated.findValue(target, quartX, 0, quartZ)) ? 1 : 0;
				nearlyFullAlternatives += "alternative".equals(nearlyFull.findValue(target, quartX, 0, quartZ)) ? 1 : 0;
				assertEquals("preferred", full.findValue(target, quartX, 0, quartZ));
				total++;
			}
		}

		assertEquals(0.5F, (float) uninfluencedAlternatives / total, 0.1F);
		assertTrue(uninfluencedAlternatives > calibratedAlternatives);
		assertTrue(calibratedAlternatives > nearlyFullAlternatives);
	}

	@Test
	void climateInfluenceChangesIdentityWithoutChangingDensityOwnership() {
		List<Pair<Climate.ParameterPoint, String>> entries = vanillaLikeEntries();
		UndergroundBiomeBanding.Layout<String> uninfluenced = UndergroundBiomeBanding.apply(
			preset(0.35F, 0.0F, true, 100, 48), entries, 71L
		);
		UndergroundBiomeBanding.Layout<String> fullyInfluenced = UndergroundBiomeBanding.apply(
			preset(0.35F, 1.0F, true, 100, 48), entries, 71L
		);
		boolean foundDifferentCaveIdentity = false;

		for (int quartY = -32; quartY <= 32; quartY += 4) {
			for (int quartZ = -128; quartZ <= 128; quartZ += 4) {
				for (int quartX = -128; quartX <= 128; quartX += 4) {
					Climate.TargetPoint target = target(0.9F, 0.0F, 0.0F, 0.9F, 0.0F);
					String withoutClimate = uninfluenced.findValue(target, quartX, quartY, quartZ);
					String withClimate = fullyInfluenced.findValue(target, quartX, quartY, quartZ);
					boolean caveOwned = VANILLA_CAVES.contains(withoutClimate);
					assertEquals(caveOwned, VANILLA_CAVES.contains(withClimate));
					foundDifferentCaveIdentity |= caveOwned && !withoutClimate.equals(withClimate);
				}
			}
		}

		assertTrue(foundDifferentCaveIdentity);
	}

	@Test
	void bottomOnlyCandidatesRemainOutOfTheShallowStage() {
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset(1.0F, 0.0F, true, 100, 32),
			vanillaLikeEntries(),
			27L
		);

		for (int quartZ = -64; quartZ <= 64; quartZ += 4) {
			for (int quartX = -64; quartX <= 64; quartX += 4) {
				assertFalse("deep_dark".equals(layout.findValue(target(0.9F, 0.0F), quartX, 0, quartZ)));
			}
		}
	}

	@Test
	void candidateOverlayUsesTheRegionalSourceForBackground() {
		List<Pair<Climate.ParameterPoint, String>> source = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "regional_surface"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "regional_cave")
		);
		List<Pair<Climate.ParameterPoint, String>> candidates = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "regional_cave")
		);
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(
			preset(0.0F, 1.0F, true, 225, 64),
			source,
			candidates,
			5L,
			(point, value) -> UndergroundBiomeBanding.classify(point, false)
		);

		assertEquals("regional_surface", layout.findValue(target(0.9F, 0.0F), 0, 0, 0));
	}

	@Test
	void zeroCandidatesPreserveTheOriginalSelector() {
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface")
		);
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(preset(0.25F, 0.75F, true, 225, 64), entries);

		assertFalse(layout.appliesAt(target(1.5F, 0.0F)));
		assertEquals("surface", layout.findValue(target(1.5F, 0.0F), 0, 0, 0));
	}

	@Test
	void selectionIsSeededDeterministicAndSeedSensitive() {
		UndergroundBiomeBanding.Layout<String> first = UndergroundBiomeBanding.apply(
			preset(0.5F, 0.5F, true, 225, 64), vanillaLikeEntries(), 3L
		);
		UndergroundBiomeBanding.Layout<String> same = UndergroundBiomeBanding.apply(
			preset(0.5F, 0.5F, true, 225, 64), vanillaLikeEntries(), 3L
		);
		UndergroundBiomeBanding.Layout<String> differentSeed = UndergroundBiomeBanding.apply(
			preset(0.5F, 0.5F, true, 225, 64), vanillaLikeEntries(), 4L
		);
		boolean foundSeedDifference = false;

		for (int quartZ = -64; quartZ <= 64; quartZ += 4) {
			for (int quartX = -64; quartX <= 64; quartX += 4) {
				String expected = first.findValue(target(1.5F, 0.0F), quartX, -8, quartZ);
				assertEquals(expected, first.findValue(target(1.5F, 0.0F), quartX, -8, quartZ));
				assertEquals(expected, same.findValue(target(1.5F, 0.0F), quartX, -8, quartZ));
				foundSeedDifference |= !expected.equals(differentSeed.findValue(target(1.5F, 0.0F), quartX, -8, quartZ));
			}
		}

		assertTrue(foundSeedDifference);
	}

	@Test
	void classifiesConventionalTaggedAndMalformedRegistrationsStructurally() {
		Climate.ParameterPoint narrowWeirdness = entry(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			SHALLOW_CAVE_DEPTH, Climate.Parameter.span(-1.1F, -0.85F), "sulfur"
		).getFirst();
		Climate.ParameterPoint tagged = entry(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			Climate.Parameter.span(0.4F, 0.6F), FULL_RANGE, "tagged"
		).getFirst();
		Climate.ParameterPoint surface = entry(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			Climate.Parameter.point(0.0F), FULL_RANGE, "surface"
		).getFirst();
		Climate.ParameterPoint malformed = new Climate.ParameterPoint(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			new Climate.Parameter(10L, -10L), FULL_RANGE, 0L
		);

		assertEquals(UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE, UndergroundBiomeBanding.classify(narrowWeirdness, false));
		assertEquals(UndergroundBiomeBanding.CandidateRole.UNKNOWN, UndergroundBiomeBanding.classify(tagged, false));
		assertEquals(UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE, UndergroundBiomeBanding.classify(tagged, true));
		assertEquals(UndergroundBiomeBanding.CandidateRole.SURFACE, UndergroundBiomeBanding.classify(surface, true));
		assertEquals(UndergroundBiomeBanding.CandidateRole.UNKNOWN, UndergroundBiomeBanding.classify(malformed, true));
	}

	private static LineStatistics horizontalStatistics(Preset preset, long seed) {
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries(), seed);
		int cave = 0;
		int total = 0;
		int transitions = 0;
		for (int quartZ = -128; quartZ <= 128; quartZ += 8) {
			Boolean previous = null;
			for (int quartX = -1024; quartX <= 1024; quartX++) {
				boolean current = VANILLA_CAVES.contains(layout.findValue(target(1.5F, 0.0F), quartX, -8, quartZ));
				cave += current ? 1 : 0;
				total++;
				if (previous != null && current != previous) {
					transitions++;
				}
				previous = current;
			}
		}
		return new LineStatistics((float) cave / total, transitions);
	}

	private static LineStatistics verticalStatistics(Preset preset, long seed) {
		UndergroundBiomeBanding.Layout<String> layout = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries(), seed);
		int cave = 0;
		int total = 0;
		int transitions = 0;
		for (int quartZ = -64; quartZ <= 64; quartZ += 8) {
			for (int quartX = -64; quartX <= 64; quartX += 8) {
				Boolean previous = null;
				for (int quartY = -256; quartY <= 256; quartY++) {
					boolean current = VANILLA_CAVES.contains(layout.findValue(target(1.5F, 0.0F), quartX, quartY, quartZ));
					cave += current ? 1 : 0;
					total++;
					if (previous != null && current != previous) {
						transitions++;
					}
					previous = current;
				}
			}
		}
		return new LineStatistics((float) cave / total, transitions);
	}

	private static Preset preset(float density, float climateInfluence, boolean banding, int horizontalSize, int verticalSize) {
		Preset preset = Presets.makeRTFDefault();
		preset.world().properties.worldDepth = 1024;
		preset.world().properties.worldHeight = 1024;
		preset.climate().biomeShape.undergroundBiomeSize = horizontalSize;
		preset.climate().biomeShape.undergroundBiomeVerticalSize = verticalSize;
		preset.climate().biomeShape.undergroundBiomeCoverage = density;
		preset.climate().biomeShape.undergroundBiomeClimateInfluence = climateInfluence;
		preset.climate().biomeShape.undergroundBiomeBanding = banding;
		return preset;
	}

	private static List<Pair<Climate.ParameterPoint, String>> vanillaLikeEntries() {
		return List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, FULL_RANGE, Climate.Parameter.span(0.8F, 1.0F), FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "dripstone"),
			entry(FULL_RANGE, Climate.Parameter.span(0.7F, 1.0F), FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "lush"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.span(-1.0F, -0.375F), BOTTOM_CAVE_DEPTH, FULL_RANGE, "deep_dark")
		);
	}

	private static List<Pair<Climate.ParameterPoint, String>> equalCaveEntries() {
		return List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "first"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "second")
		);
	}

	private static Pair<Climate.ParameterPoint, String> entry(
		Climate.Parameter temperature,
		Climate.Parameter humidity,
		Climate.Parameter continentalness,
		Climate.Parameter erosion,
		Climate.Parameter depth,
		Climate.Parameter weirdness,
		String value
	) {
		return Pair.of(
			new Climate.ParameterPoint(temperature, humidity, continentalness, erosion, depth, weirdness, 0L),
			value
		);
	}

	private static Climate.TargetPoint target(float depth, float weirdness) {
		return target(0.0F, 0.0F, 0.0F, depth, weirdness);
	}

	private static Climate.TargetPoint target(float humidity, float continentalness, float erosion, float depth, float weirdness) {
		return Climate.target(0.0F, humidity, continentalness, erosion, depth, weirdness);
	}

	private record LineStatistics(float density, int transitions) {
	}
}
