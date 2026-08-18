package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;

class SurfaceBiomeFilterTest {
	private static final Climate.Parameter FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);

	@Test
	void replacesClimateRegisteredCaveBiomesWithTheNearestSurfaceCandidate() {
		SurfaceBiomeFilter<String> filter = filter(
			List.of(entry(0.0F, "forest"), entry(0.2F, "lush_caves"), entry(1.1F, "deep_dark")),
			Set.of()
		);

		assertTrue(filter.isUnderground("lush_caves"));
		assertTrue(filter.isUnderground("deep_dark"));
		assertEquals("forest", filter.resolve(Climate.target(0, 0, 0, 0, 0, 0), "lush_caves"));
	}

	@Test
	void rejectsTaggedAndTerraBlenderDiscoveredCandidatesWithoutBiomeIds() {
		SurfaceBiomeFilter<String> filter = SurfaceBiomeFilter.create(
			List.of(entry(0.0F, "plains"), entry(0.05F, "tagged_mod_cave")),
			(point, value) -> UndergroundBiomeBanding.classify(point, value.equals("tagged_mod_cave")),
			value -> value.equals("tagged_mod_cave"),
			List.of("regional_mod_cave"),
			"plains"
		);

		assertTrue(filter.isUnderground("tagged_mod_cave"));
		assertTrue(filter.isUnderground("regional_mod_cave"));
		assertEquals("plains", filter.resolve(Climate.target(0, 0, 0, 0, 0, 0), "regional_mod_cave"));
	}

	@Test
	void preservesABiomeThatHasAnExplicitSurfaceRegistration() {
		SurfaceBiomeFilter<String> filter = filter(
			List.of(entry(0.0F, "dual_use"), entry(0.2F, "dual_use")),
			Set.of()
		);

		assertFalse(filter.isUnderground("dual_use"));
		assertEquals("dual_use", filter.resolve(Climate.target(0, 0, 0, 0, 0, 0), "dual_use"));
	}

	private static SurfaceBiomeFilter<String> filter(
		List<Pair<Climate.ParameterPoint, String>> entries,
		Set<String> tags
	) {
		return SurfaceBiomeFilter.create(
			entries,
			(point, value) -> UndergroundBiomeBanding.classify(point, tags.contains(value)),
			tags::contains,
			List.of(),
			"fallback"
		);
	}

	private static Pair<Climate.ParameterPoint, String> entry(float depth, String value) {
		Climate.Parameter depthParameter = depth == 0.2F
			? Climate.Parameter.span(0.2F, 0.9F)
			: Climate.Parameter.point(depth);
		return Pair.of(
			new Climate.ParameterPoint(
				FULL_RANGE,
				FULL_RANGE,
				FULL_RANGE,
				FULL_RANGE,
				depthParameter,
				FULL_RANGE,
				0L
			),
			value
		);
	}
}
