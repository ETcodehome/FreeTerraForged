package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
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
	void capsADeepWorldSurfaceBufferAtTwentyFourBlocks() {
		Preset preset = preset(1024, 640, 50);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());
		float startDepth = Climate.unquantizeCoord(banding.bandingStart());

		assertEquals(SURFACE_DEPTH + 24.0F / 128.0F, startDepth, 0.0001F);
		assertFalse(banding.appliesAt(target(startDepth - 0.0001F, 0.0F)));
		assertTrue(banding.appliesAt(target(startDepth, 0.0F)));
	}

	@Test
	void compressesTheSurfaceBufferInAnExtremelyShallowWorld() {
		Preset preset = preset(0, 16, 225);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());
		float startDepth = Climate.unquantizeCoord(banding.bandingStart());
		float bufferBlocks = (startDepth - SURFACE_DEPTH) * 128.0F;

		assertTrue(bufferBlocks > 0.0F);
		assertTrue(bufferBlocks < UndergroundBiomeBanding.MAX_SURFACE_BUFFER_BLOCKS);
		assertTrue(startDepth < UndergroundBiomeBanding.endDepth(preset));
	}

	@Test
	void leavesTheOriginalLookupUntouchedAboveTheSurfaceBuffer() {
		Preset preset = preset(1024, 640, 50);
		List<Pair<Climate.ParameterPoint, String>> entries = vanillaLikeEntries();
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, entries);
		Climate.TargetPoint target = target(0.1F, 0.0F);

		assertFalse(banding.appliesAt(target));
		assertEquals("surface", new Climate.ParameterList<>(entries).findValue(target));
	}

	@Test
	void usesOriginalClimateOnlyForTheFirstDynamicBand() {
		Preset preset = preset(1024, 640, 50);
		Climate.Parameter wet = Climate.Parameter.span(0.6F, 1.0F);
		Climate.Parameter dry = Climate.Parameter.span(-1.0F, -0.8F);
		Climate.Parameter negativeErosion = Climate.Parameter.span(-1.0F, 0.0F);
		Climate.Parameter positiveErosion = Climate.Parameter.span(0.0F, 1.0F);
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, wet, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "wet"),
			entry(FULL_RANGE, dry, FULL_RANGE, positiveErosion, SHALLOW_CAVE_DEPTH, FULL_RANGE, "prism"),
			entry(FULL_RANGE, dry, FULL_RANGE, negativeErosion, SHALLOW_CAVE_DEPTH, FULL_RANGE, "redstone")
		);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, entries);
		float start = Climate.unquantizeCoord(banding.bandingStart());
		float end = Math.min(BOTTOM_DEPTH, UndergroundBiomeBanding.endDepth(preset));
		int bands = UndergroundBiomeBanding.bandCount(preset, 3, start, end);

		assertTrue(bands > 1);
		float entryDepth = bandCenter(start, end, bands, 0);
		assertEquals("wet", banding.findValue(target(0.8F, 0.0F, 0.0F, entryDepth, -0.9F)));
		assertEquals("prism", banding.findValue(target(-0.9F, 0.0F, 0.5F, entryDepth, -0.9F)));
		assertEquals("redstone", banding.findValue(target(-0.9F, 0.0F, -0.5F, entryDepth, -0.9F)));

		float firstRotationDepth = bandCenter(start, end, bands, 1);
		assertEquals(
			"redstone",
			banding.findValue(target(-0.9F, 0.0F, -0.5F, firstRotationDepth, -0.9F)),
			"rotation must not discard the candidates' horizontal climate constraints"
		);
	}

	@Test
	void usesHorizontalPhaseToResolveEqualEntryFitnessWithoutChangingClimateAxes() {
		Preset preset = preset(1024, 640, 50);
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "first"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "second")
		);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, entries);
		float start = Climate.unquantizeCoord(banding.bandingStart());
		float end = Math.min(BOTTOM_DEPTH, UndergroundBiomeBanding.endDepth(preset));
		int bands = UndergroundBiomeBanding.bandCount(preset, 2, start, end);
		float entryDepth = bandCenter(start, end, bands, 0);

		assertEquals(
			banding.findValue(target(entryDepth, -0.9F), 100, 0),
			banding.findValue(target(entryDepth, 0.9F), 100, 0),
			"the phase must not synthesize a different weirdness axis"
		);
		Set<String> selected = new LinkedHashSet<>();
		for (int quartX = -256; quartX <= 256; quartX++) {
			selected.add(banding.findValue(target(entryDepth, 0.0F), quartX, 0));
		}
		assertEquals(Set.of("first", "second"), selected);
	}

	@Test
	void rotatesOnlyAmongCandidatesWhoseHorizontalClimateContainsTheTarget() {
		Preset preset = preset(1024, 640, 50);
		float end = UndergroundBiomeBanding.endDepth(preset);
		int bands = UndergroundBiomeBanding.bandCount(preset, 3, BOTTOM_DEPTH, end);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());

		assertEquals(19, bands);
		for (int band = 0; band < bands; band++) {
			float depth = bandCenter(BOTTOM_DEPTH, end, bands, band);
			assertEquals(
				"dripstone",
				banding.findValue(target(0.0F, 0.9F, 0.0F, depth, -0.9F)),
				"an ineligible candidate must not take ownership of deep band " + band
			);
		}
	}

	@Test
	void phasesHardRotationHorizontallyWithCoordinates() {
		Preset preset = preset(1024, 640, 50);
		float end = UndergroundBiomeBanding.endDepth(preset);
		int bands = UndergroundBiomeBanding.bandCount(preset, 3, BOTTOM_DEPTH, end);
		float depth = bandCenter(BOTTOM_DEPTH, end, bands, 0);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "first"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "second"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, BOTTOM_CAVE_DEPTH, FULL_RANGE, "bottom")
		));

		Set<String> selected = new LinkedHashSet<>();
		for (int quartX = -256; quartX <= 256; quartX++) {
			selected.add(banding.findValue(target(depth, 0.0F), quartX, 0));
		}
		assertEquals(Set.of("first", "second", "bottom"), selected);
	}

	@Test
	void excludesBottomOnlyCandidatesFromTheShallowStage() {
		Preset preset = preset(1024, 640, 50);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());
		float start = Climate.unquantizeCoord(banding.bandingStart());

		for (float depth = start; depth < BOTTOM_DEPTH; depth += 0.025F) {
			String value = banding.findValue(target(depth, 0.0F));
			assertTrue(Set.of("dripstone", "lush").contains(value));
		}
	}

	@Test
	void fullyCoversEveryDynamicDepthWithoutSurfaceBiomeBleed() {
		Preset preset = preset(1024, 640, 50);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());
		float start = Climate.unquantizeCoord(banding.bandingStart());
		float end = UndergroundBiomeBanding.endDepth(preset);

		for (float depth = start; depth <= end; depth += 0.025F) {
			for (float weirdness : List.of(-0.9F, 0.0F, 0.9F)) {
				assertTrue(VANILLA_CAVES.contains(banding.findValue(target(depth, weirdness))));
			}
		}
	}

	@Test
	void scalesBandCountWithoutWeakeningRotation() {
		assertEquals(21, UndergroundBiomeBanding.bandCount(preset(1024, 1024, 50), 3, BOTTOM_DEPTH, 16.0F));
		assertEquals(10, UndergroundBiomeBanding.bandCount(preset(1024, 1024, 225), 3, BOTTOM_DEPTH, 16.0F));
		assertEquals(9, UndergroundBiomeBanding.bandCount(preset(1024, 1024, 255), 3, BOTTOM_DEPTH, 16.0F));
	}

	@Test
	void undergroundSizeControlsVerticalBandsIndependentlyOfSurfaceSize() {
		Preset largeSurfaceSmallUnderground = preset(1024, 1024, 900, 50);
		Preset smallSurfaceLargeUnderground = preset(1024, 1024, 50, 900);

		int smallUndergroundBands = UndergroundBiomeBanding.bandCount(
			largeSurfaceSmallUnderground, 3, BOTTOM_DEPTH, 16.0F
		);
		int largeUndergroundBands = UndergroundBiomeBanding.bandCount(
			smallSurfaceLargeUnderground, 3, BOTTOM_DEPTH, 16.0F
		);

		assertTrue(smallUndergroundBands > largeUndergroundBands);
		assertEquals(
			smallUndergroundBands,
			UndergroundBiomeBanding.bandCount(preset(1024, 1024, 50, 50), 3, BOTTOM_DEPTH, 16.0F)
		);
	}

	@Test
	void horizontalPhaseIsDeterministicAndSeeded() {
		Preset preset = preset(1024, 640, 225);

		float first = UndergroundBiomeBanding.horizontalPhase(preset, 3L, -317, 91);
		assertEquals(first, UndergroundBiomeBanding.horizontalPhase(preset, 3L, -317, 91));
		assertFalse(first == UndergroundBiomeBanding.horizontalPhase(preset, 4L, -317, 91));
	}

	@Test
	void largerUndergroundSizesProduceSmootherHorizontalPhaseFields() {
		double smallRoughness = horizontalRoughness(preset(1024, 640, 900, 50), 3L);
		double defaultRoughness = horizontalRoughness(preset(1024, 640, 50, 225), 3L);
		double largeRoughness = horizontalRoughness(preset(1024, 640, 50, 900), 3L);

		assertTrue(smallRoughness > defaultRoughness);
		assertTrue(defaultRoughness > largeRoughness);
	}

	@Test
	void horizontalCoordinatesChangeEligibleCaveSelectionCoherently() {
		Preset preset = preset(1024, 640, 50);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(
			preset,
			List.of(
				entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "first"),
				entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "second")
			),
			3L
		);
		float end = UndergroundBiomeBanding.endDepth(preset);
		int bands = UndergroundBiomeBanding.bandCount(preset, 2, BOTTOM_DEPTH, end);
		Climate.TargetPoint target = target(bandCenter(BOTTOM_DEPTH, end, bands, 0), 0.0F);
		Set<String> selected = new LinkedHashSet<>();

		for (int quartX = -256; quartX <= 256; quartX++) {
			String value = banding.findValue(target, quartX, 0);
			assertEquals(value, banding.findValue(target, quartX, 0));
			selected.add(value);
		}

		assertEquals(Set.of("first", "second"), selected);
	}

	@Test
	void coversTheFullConfiguredSurfaceToBottomSpan() {
		Preset stress = preset(1024, 1024, 225);
		Preset veryDeep = preset(624, 384, 225);

		assertEquals(16.0F, UndergroundBiomeBanding.endDepth(stress), 0.0001F);
		assertEquals(7.875F, UndergroundBiomeBanding.endDepth(veryDeep), 0.0001F);
	}

	@Test
	void classifiesModdedCandidatesByRegistrationShapeRatherThanBiomeId() {
		Preset preset = preset(1024, 640, 50);
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "shallow_a"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "shallow_b"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "mod_shallow"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, BOTTOM_CAVE_DEPTH, FULL_RANGE, "mod_bottom"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.span(0.4F, 0.6F), Climate.Parameter.span(-0.5F, 0.5F), "custom")
		);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, entries);
		Set<String> selected = new LinkedHashSet<>();
		float end = UndergroundBiomeBanding.endDepth(preset);
		int bands = UndergroundBiomeBanding.bandCount(preset, 4, BOTTOM_DEPTH, end);

		for (int band = 0; band < bands; band++) {
			selected.add(banding.findValue(target(bandCenter(BOTTOM_DEPTH, end, bands, band), -0.9F)));
		}
		assertEquals(Set.of("shallow_a", "shallow_b", "mod_shallow", "mod_bottom"), selected);
		assertFalse(selected.contains("custom"));
	}

	@Test
	void acceptsNarrowWeirdnessForAConventionalShallowCave() {
		Climate.Parameter sulfurWeirdness = Climate.Parameter.span(-1.1F, -0.85F);
		Climate.ParameterPoint sulfur = entry(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			SHALLOW_CAVE_DEPTH, sulfurWeirdness, "sulfur"
		).getFirst();

		assertEquals(
			UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE,
			UndergroundBiomeBanding.classify(sulfur, false)
		);
	}

	@Test
	void narrowWeirdnessCandidateCanWinInsideItsRegisteredInterval() {
		Preset preset = preset(1024, 640, 50);
		Climate.Parameter sulfurWeirdness = Climate.Parameter.span(-1.1F, -0.85F);
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, sulfurWeirdness, "sulfur"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, Climate.Parameter.span(0.2F, 1.0F), "other")
		);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, entries);
		float start = Climate.unquantizeCoord(banding.bandingStart());
		float end = Math.min(BOTTOM_DEPTH, UndergroundBiomeBanding.endDepth(preset));
		int bands = UndergroundBiomeBanding.bandCount(preset, 2, start, end);

		assertEquals("sulfur", banding.findValue(target(bandCenter(start, end, bands, 0), -0.9F)));
	}

	@Test
	void usesACaveTagToCorroborateNonstandardPositiveDepthShapes() {
		Climate.ParameterPoint taggedShallow = entry(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			Climate.Parameter.span(0.4F, 0.6F), FULL_RANGE, "tagged_shallow"
		).getFirst();
		Climate.ParameterPoint taggedDeep = entry(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			Climate.Parameter.point(1.2F), FULL_RANGE, "tagged_deep"
		).getFirst();

		assertEquals(
			UndergroundBiomeBanding.CandidateRole.UNKNOWN,
			UndergroundBiomeBanding.classify(taggedShallow, false)
		);
		assertEquals(
			UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE,
			UndergroundBiomeBanding.classify(taggedShallow, true)
		);
		assertEquals(
			UndergroundBiomeBanding.CandidateRole.DEEP_CAVE,
			UndergroundBiomeBanding.classify(taggedDeep, true)
		);
	}

	@Test
	void keepsSurfaceAndMalformedShapesOutOfTheBandingPool() {
		Climate.ParameterPoint surface = entry(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE,
			Climate.Parameter.point(0.0F), FULL_RANGE, "surface"
		).getFirst();
		Climate.Parameter malformed = new Climate.Parameter(10L, -10L);
		Climate.ParameterPoint malformedPoint = new Climate.ParameterPoint(
			FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, malformed, FULL_RANGE, 0L
		);

		assertEquals(UndergroundBiomeBanding.CandidateRole.SURFACE, UndergroundBiomeBanding.classify(surface, true));
		assertEquals(UndergroundBiomeBanding.CandidateRole.UNKNOWN, UndergroundBiomeBanding.classify(malformedPoint, true));
	}

	@Test
	void oneUnknownEntryDoesNotDisableValidCandidates() {
		Preset preset = preset(1024, 640, 50);
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "shallow_a"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "shallow_b"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.span(0.4F, 0.6F), FULL_RANGE, "unknown")
		);

		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, entries);

		assertTrue(banding.appliesAt(target(1.0F, 0.0F)));
		assertEquals(2, banding.shallowCandidateCount());
		assertEquals(1, banding.unknownEntryCount());
	}

	@Test
	void leavesAParameterListWithOneRecognizedCandidateUnmodified() {
		Preset preset = preset(1024, 640, 50);
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "lush"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, BOTTOM_CAVE_DEPTH, FULL_RANGE, "lush")
		);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, entries);

		assertEquals(Long.MAX_VALUE, banding.bandingStart());
		assertFalse(banding.appliesAt(target(1.3F, 0.0F)));
		assertEquals("lush", banding.findValue(target(1.3F, 0.0F)));
	}

	private static Preset preset(int worldDepth, int worldHeight, int biomeSize) {
		return preset(worldDepth, worldHeight, biomeSize, biomeSize);
	}

	private static Preset preset(int worldDepth, int worldHeight, int biomeSize, int undergroundBiomeSize) {
		Preset preset = Presets.makeRTFDefault();
		preset.world().properties.worldDepth = worldDepth;
		preset.world().properties.worldHeight = worldHeight;
		preset.climate().biomeShape.biomeSize = biomeSize;
		preset.climate().biomeShape.undergroundBiomeSize = undergroundBiomeSize;
		return preset;
	}

	private static double horizontalRoughness(Preset preset, long seed) {
		double roughness = 0.0D;
		int samples = 0;
		for (int quartZ = -64; quartZ < 64; quartZ += 4) {
			for (int quartX = -64; quartX < 64; quartX++) {
				float current = UndergroundBiomeBanding.horizontalPhase(preset, seed, quartX, quartZ);
				float next = UndergroundBiomeBanding.horizontalPhase(preset, seed, quartX + 1, quartZ);
				roughness += Math.abs(next - current);
				samples++;
			}
		}
		return roughness / samples;
	}

	private static List<Pair<Climate.ParameterPoint, String>> vanillaLikeEntries() {
		return List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, FULL_RANGE, Climate.Parameter.span(0.8F, 1.0F), FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "dripstone"),
			entry(FULL_RANGE, Climate.Parameter.span(0.7F, 1.0F), FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "lush"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.span(-1.0F, -0.375F), BOTTOM_CAVE_DEPTH, FULL_RANGE, "deep_dark")
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

	private static float bandCenter(float start, float end, int bands, int band) {
		return start + (end - start) * (band + 0.5F) / bands;
	}

	private static Climate.TargetPoint target(float depth, float weirdness) {
		return target(0.0F, 0.0F, 0.0F, depth, weirdness);
	}

	private static Climate.TargetPoint target(float humidity, float continentalness, float erosion, float depth, float weirdness) {
		return Climate.target(0.0F, humidity, continentalness, erosion, depth, weirdness);
	}
}
