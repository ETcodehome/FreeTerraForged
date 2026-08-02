package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
