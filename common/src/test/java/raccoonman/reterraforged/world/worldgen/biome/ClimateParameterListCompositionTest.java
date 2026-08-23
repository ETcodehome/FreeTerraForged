package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;

class ClimateParameterListCompositionTest {
	private static final Climate.Parameter FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);

	@Test
	void findsLateEntriesWithoutTreatingReorderingAsAnAddition() {
		Pair<Climate.ParameterPoint, String> first = entry(0.0F, "first");
		Pair<Climate.ParameterPoint, String> second = entry(0.1F, "second");
		Pair<Climate.ParameterPoint, String> late = entry(0.2F, "late");

		assertEquals(
			List.of(late),
			ClimateParameterListComposition.additions(List.of(first, second), List.of(second, late, first))
		);
	}

	@Test
	void preservesDuplicateOccurrenceCounts() {
		Pair<Climate.ParameterPoint, String> repeated = entry(0.0F, "repeated");

		assertEquals(
			List.of(repeated),
			ClimateParameterListComposition.additions(List.of(repeated), List.of(repeated, repeated))
		);
	}

	@Test
	void appendsGlobalAdditionsInRegistrationOrder() {
		Pair<Climate.ParameterPoint, String> regional = entry(0.0F, "regional");
		Pair<Climate.ParameterPoint, String> firstLate = entry(0.1F, "first_late");
		Pair<Climate.ParameterPoint, String> secondLate = entry(0.2F, "second_late");

		assertEquals(
			List.of(regional, firstLate, secondLate),
			ClimateParameterListComposition.append(List.of(regional), List.of(firstLate, secondLate))
		);
	}

	@Test
	void canonicalSnapshotDeduplicatesExactEntriesAndRetainsProvenance() {
		Pair<Climate.ParameterPoint, String> vanilla = entry(0.0F, "vanilla");
		Pair<Climate.ParameterPoint, String> regional = entry(0.1F, "regional");
		Pair<Climate.ParameterPoint, String> late = entry(0.2F, "late");

		ClimateParameterListComposition.Snapshot<String> snapshot = ClimateParameterListComposition.snapshot(
			List.of(vanilla),
			List.of(vanilla, late),
			List.of(List.of(vanilla), List.of(vanilla, regional)),
			value -> false
		);

		assertTrue(snapshot.usable());
		assertEquals(List.of(vanilla, late, regional), snapshot.canonicalEntries());
		assertEquals(2, snapshot.duplicateEntryCount());
		assertEquals(
			List.of(0, 1),
			snapshot.registrations().getFirst().sourceRegions().stream().sorted().toList()
		);
		assertTrue(snapshot.registrations().get(1).lateGlobal());
	}

	@Test
	void aNullRegionFallsBackLocallyWithoutErasingOtherRegions() {
		Pair<Climate.ParameterPoint, String> base = entry(0.0F, "base");
		ClimateParameterListComposition.Snapshot<String> snapshot = ClimateParameterListComposition.snapshot(
			List.of(base),
			List.of(base),
			java.util.Arrays.asList(List.of(base), null, List.of(entry(0.1F, "third"))),
			value -> false
		);

		assertTrue(snapshot.usableForRegion(0));
		assertFalse(snapshot.usableForRegion(1));
		assertTrue(snapshot.usableForRegion(2));
		assertEquals(List.of(1), snapshot.invalidRegions().stream().toList());
	}

	@Test
	void aMalformedEntryIsSkippedWithoutErasingValidCandidates() {
		Pair<Climate.ParameterPoint, String> base = entry(0.0F, "base");
		Pair<Climate.ParameterPoint, String> malformed = Pair.of(null, "malformed");
		ClimateParameterListComposition.Snapshot<String> snapshot = ClimateParameterListComposition.snapshot(
			List.of(base),
			List.of(base),
			List.of(List.of(base), List.of(entry(0.1F, "regional"), malformed)),
			value -> false
		);

		assertTrue(snapshot.usable());
		assertEquals(1, snapshot.invalidEntryCount());
		assertFalse(snapshot.canonicalEntries().contains(malformed));
	}

	@Test
	void excludedPlaceholdersCannotWinTheGlobalIndex() {
		Pair<Climate.ParameterPoint, String> base = entry(0.0F, "base");
		Pair<Climate.ParameterPoint, String> deferred = entry(0.2F, "deferred");
		ClimateParameterListComposition.Snapshot<String> snapshot = ClimateParameterListComposition.snapshot(
			List.of(base),
			List.of(base),
			List.of(List.of(base), List.of(deferred)),
			"deferred"::equals
		);

		assertEquals(List.of(base), snapshot.canonicalEntries());
		assertEquals(1, snapshot.excludedEntryCount());
	}

	@Test
	void canonicalSnapshotRetainsDistinctRegionalPointsForDiagnostics() {
		Pair<Climate.ParameterPoint, String> cold = temperatureEntry(-0.8F, "cold");
		Pair<Climate.ParameterPoint, String> warm = temperatureEntry(0.8F, "warm");
		ClimateParameterListComposition.Snapshot<String> snapshot = ClimateParameterListComposition.snapshot(
			List.of(cold),
			List.of(cold),
			List.of(List.of(cold), List.of(warm)),
			value -> false
		);
		assertEquals(List.of(cold, warm), snapshot.canonicalEntries());
	}

	@Test
	void regionalCaveValueReplacesTheDefaultValueAtTheSameLogicalSlot() {
		Pair<Climate.ParameterPoint, String> dripstone = entry(0.2F, "dripstone");
		Pair<Climate.ParameterPoint, String> spiderNest = Pair.of(dripstone.getFirst(), "spider_nest");
		Pair<Climate.ParameterPoint, String> lush = entry(0.3F, "lush");

		ClimateParameterListComposition.CandidateOverlay<String> overlay =
			ClimateParameterListComposition.overlayUndergroundCandidates(
				List.of(dripstone, lush),
				List.of(spiderNest),
				List.of(),
				(point, value) -> UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE,
				value -> false
			);

		assertTrue(overlay.usable());
		assertEquals(List.of(spiderNest, lush), overlay.entries());
		assertEquals(1, overlay.replacedDefaultSlotCount());
		assertFalse(overlay.entries().contains(dripstone));
	}

	@Test
	void distinctRegionalCaveSlotIsAdditive() {
		Pair<Climate.ParameterPoint, String> dripstone = entry(0.2F, "dripstone");
		Pair<Climate.ParameterPoint, String> sulfur = entry(0.25F, "sulfur");

		ClimateParameterListComposition.CandidateOverlay<String> overlay =
			ClimateParameterListComposition.overlayUndergroundCandidates(
				List.of(dripstone),
				List.of(sulfur),
				List.of(),
				(point, value) -> UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE,
				value -> false
			);

		assertEquals(List.of(dripstone, sulfur), overlay.entries());
		assertEquals(0, overlay.replacedDefaultSlotCount());
	}

	@Test
	void lateGlobalCaveAdditionSurvivesARegionalSlotReplacement() {
		Pair<Climate.ParameterPoint, String> dripstone = entry(0.2F, "dripstone");
		Pair<Climate.ParameterPoint, String> spiderNest = Pair.of(dripstone.getFirst(), "spider_nest");
		Pair<Climate.ParameterPoint, String> global = Pair.of(dripstone.getFirst(), "global_cave");

		ClimateParameterListComposition.CandidateOverlay<String> overlay =
			ClimateParameterListComposition.overlayUndergroundCandidates(
				List.of(dripstone, global),
				List.of(spiderNest),
				List.of(global),
				(point, value) -> UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE,
				value -> false
			);

		assertEquals(List.of(spiderNest, global), overlay.entries());
	}

	private static Pair<Climate.ParameterPoint, String> entry(float depth, String value) {
		return Pair.of(
			new Climate.ParameterPoint(
				FULL_RANGE,
				FULL_RANGE,
				FULL_RANGE,
				FULL_RANGE,
				Climate.Parameter.point(depth),
				FULL_RANGE,
				0L
			),
			value
		);
	}

	private static Pair<Climate.ParameterPoint, String> temperatureEntry(float temperature, String value) {
		return Pair.of(
			new Climate.ParameterPoint(
				Climate.Parameter.point(temperature),
				FULL_RANGE,
				FULL_RANGE,
				FULL_RANGE,
				Climate.Parameter.point(0.0F),
				FULL_RANGE,
				0L
			),
			value
		);
	}
}
