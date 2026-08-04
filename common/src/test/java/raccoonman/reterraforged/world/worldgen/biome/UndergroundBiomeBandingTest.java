package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;

class UndergroundBiomeBandingTest {
	private static final float DEPTH_UNITS_PER_BLOCK = 1.0F / 128.0F;
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

		assertEquals(SURFACE_DEPTH + 24.0F * DEPTH_UNITS_PER_BLOCK, startDepth, 0.0001F);
		assertEquals(
			UndergroundBiomeBanding.MAX_SURFACE_BUFFER_BLOCKS,
			(startDepth - SURFACE_DEPTH) / DEPTH_UNITS_PER_BLOCK,
			0.02F
		);
		assertFalse(banding.appliesAt(target(startDepth - 0.0001F, 0.0F)));
		assertTrue(banding.appliesAt(target(startDepth, 0.0F)));
	}

	@Test
	void compressesTheSurfaceBufferInAnExtremelyShallowWorld() {
		Preset preset = preset(0, 16, 225);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());
		float startDepth = Climate.unquantizeCoord(banding.bandingStart());
		float bufferBlocks = (startDepth - SURFACE_DEPTH) / DEPTH_UNITS_PER_BLOCK;

		assertTrue(bufferBlocks > 0.0F);
		assertTrue(bufferBlocks < UndergroundBiomeBanding.MAX_SURFACE_BUFFER_BLOCKS);
		assertTrue(startDepth < UndergroundBiomeBanding.endDepth(preset));
		assertTrue(banding.appliesAt(target((startDepth + UndergroundBiomeBanding.endDepth(preset)) * 0.5F, 0.0F)));
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
	void usesOnlyShallowRegisteredCandidatesBeforeBottomDepth() {
		Preset preset = preset(1024, 640, 50);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());

		assertEquals(Set.of("dripstone", "lush"), fallbackValuesAt(banding.parameters().values(), 0.3F));
		assertEquals("dripstone", banding.findValue(target(0.3F, -0.8F)));
		assertEquals("lush", banding.findValue(target(0.3F, 0.8F)));
		for (float depth = Climate.unquantizeCoord(banding.bandingStart()); depth < BOTTOM_DEPTH; depth += 0.025F) {
			float sampleDepth = depth;
			for (float weirdness : List.of(-0.9F, 0.0F, 0.9F)) {
				String value = banding.findValue(target(sampleDepth, weirdness));
				assertTrue(Set.of("dripstone", "lush").contains(value), () -> "unexpected shallow value " + value + " at depth " + sampleDepth);
			}
		}
	}

	@Test
	void introducesBottomRegisteredCandidatesAtBottomDepth() {
		Preset preset = preset(1024, 640, 50);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());

		assertEquals(VANILLA_CAVES, fallbackValuesAt(banding.parameters().values(), BOTTOM_DEPTH));
		assertEquals("dripstone", banding.findValue(target(1.2F, -0.8F)));
		assertEquals("lush", banding.findValue(target(1.2F, 0.0F)));
		assertEquals("deep_dark", banding.findValue(target(1.2F, 0.8F)));
	}

	@Test
	void fullyCoversEveryDynamicDepthWithoutSurfaceBiomeBleed() {
		Preset preset = preset(1024, 640, 50);
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());
		float startDepth = Climate.unquantizeCoord(banding.bandingStart());
		float endDepth = UndergroundBiomeBanding.endDepth(preset);

		for (float depth = startDepth; depth <= endDepth; depth += 0.025F) {
			float sampleDepth = depth;
			for (float weirdness : List.of(-0.9F, 0.0F, 0.9F)) {
				String value = banding.findValue(target(sampleDepth, weirdness));
				assertTrue(VANILLA_CAVES.contains(value), () -> "surface value " + value + " at depth " + sampleDepth);
			}
		}
	}

	@Test
	void rotatesCandidatesBetweenDeepBands() {
		Preset preset = preset(1024, 640, 50);
		float endDepth = UndergroundBiomeBanding.endDepth(preset);
		int deepBandCount = UndergroundBiomeBanding.bandCount(preset, 3, BOTTOM_DEPTH, endDepth);
		float deepBandWidth = (endDepth - BOTTOM_DEPTH) / deepBandCount;
		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, vanillaLikeEntries());

		assertEquals(16, deepBandCount);
		assertEquals("lush", banding.findValue(target(BOTTOM_DEPTH + deepBandWidth * 0.5F, 0.0F)));
		assertEquals("deep_dark", banding.findValue(target(BOTTOM_DEPTH + deepBandWidth * 1.5F, 0.0F)));
	}

	@Test
	void classifiesModdedCandidatesByRegistrationShapeRatherThanBiomeId() {
		Preset preset = preset(1024, 640, 50);
		List<Pair<Climate.ParameterPoint, String>> entries = List.of(
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.point(0.0F), FULL_RANGE, "surface"),
			entry(FULL_RANGE, FULL_RANGE, Climate.Parameter.span(0.8F, 1.0F), FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "dripstone"),
			entry(FULL_RANGE, Climate.Parameter.span(0.7F, 1.0F), FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "lush"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.span(-1.0F, -0.375F), BOTTOM_CAVE_DEPTH, FULL_RANGE, "deep_dark"),
			entry(FULL_RANGE, Climate.Parameter.span(-1.0F, -0.7F), FULL_RANGE, FULL_RANGE, SHALLOW_CAVE_DEPTH, FULL_RANGE, "mod_shallow"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, BOTTOM_CAVE_DEPTH, FULL_RANGE, "mod_bottom"),
			entry(FULL_RANGE, FULL_RANGE, FULL_RANGE, FULL_RANGE, Climate.Parameter.span(0.4F, 0.6F), Climate.Parameter.span(-0.5F, 0.5F), "custom")
		);

		UndergroundBiomeBanding.Layout<String> banding = UndergroundBiomeBanding.apply(preset, entries);

		assertEquals(Set.of("dripstone", "lush", "mod_shallow"), fallbackValuesAt(banding.parameters().values(), 0.3F));
		assertEquals(Set.of("dripstone", "lush", "deep_dark", "mod_shallow", "mod_bottom"), fallbackValuesAt(banding.parameters().values(), 1.2F));
		assertTrue(banding.parameters().values().stream().anyMatch(entry -> entry.getSecond().equals("custom")));
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
		assertEquals(entries, banding.parameters().values());
		assertFalse(banding.appliesAt(target(1.3F, 0.0F)));
	}

	private static Preset preset(int worldDepth, int worldHeight, int biomeSize) {
		Preset preset = Presets.makeRTFDefault();
		preset.world().properties.worldDepth = worldDepth;
		preset.world().properties.worldHeight = worldHeight;
		preset.climate().biomeShape.biomeSize = biomeSize;
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
			new Climate.ParameterPoint(
				temperature,
				humidity,
				continentalness,
				erosion,
				depth,
				weirdness,
				0L
			),
			value
		);
	}

	private static Set<String> fallbackValuesAt(
		List<Pair<Climate.ParameterPoint, String>> entries,
		float depth
	) {
		long quantizedDepth = Climate.quantizeCoord(depth);
		return entries.stream()
			.filter(entry -> isFullClimate(entry.getFirst()))
			.filter(entry -> entry.getFirst().depth().min() <= quantizedDepth && entry.getFirst().depth().max() >= quantizedDepth)
			.map(Pair::getSecond)
			.collect(Collectors.toSet());
	}

	private static Climate.TargetPoint target(float depth, float weirdness) {
		return Climate.target(0.0F, 0.0F, 0.0F, 0.0F, depth, weirdness);
	}

	private static boolean isFullClimate(Climate.ParameterPoint point) {
		return point.temperature().equals(FULL_RANGE)
			&& point.humidity().equals(FULL_RANGE)
			&& point.continentalness().equals(FULL_RANGE)
			&& point.erosion().equals(FULL_RANGE);
	}
}
