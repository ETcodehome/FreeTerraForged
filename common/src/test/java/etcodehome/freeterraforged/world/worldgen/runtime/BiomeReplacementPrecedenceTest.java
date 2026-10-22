package etcodehome.freeterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BiomeReplacementPrecedenceTest {
	@Test
	void tagRulesFillOnlyUnclaimedSourcesAndDirectRulesOverwrite() {
		Map<String, String> scope = new LinkedHashMap<>();
		BiomeReplacementPrecedence.FillResult<String> firstTag =
			BiomeReplacementPrecedence.fillUnclaimed(
				scope, List.of("forest", "birch_forest"), "desert"
			);
		assertEquals(List.of("forest", "birch_forest"), firstTag.added());
		assertEquals(0, firstTag.shadowed());

		BiomeReplacementPrecedence.FillResult<String> overlappingTag =
			BiomeReplacementPrecedence.fillUnclaimed(
				scope, List.of("birch_forest", "dark_forest"), "badlands"
			);
		assertEquals(List.of("dark_forest"), overlappingTag.added());
		assertEquals(1, overlappingTag.shadowed());
		assertEquals("desert", scope.get("birch_forest"));

		assertEquals(
			"desert",
			BiomeReplacementPrecedence.overwrite(scope, "forest", "plains")
		);
		assertEquals("plains", scope.get("forest"));
		assertNull(BiomeReplacementPrecedence.overwrite(scope, "meadow", "grove"));
	}

	@Test
	void dimensionScopeOverlaysGlobalScopeWithoutMutatingEither() {
		Map<String, String> global = new LinkedHashMap<>();
		global.put("plains", "desert");
		global.put("forest", "taiga");
		Map<String, String> dimension = new LinkedHashMap<>();
		dimension.put("forest", "badlands");

		Map<String, String> effective = BiomeReplacementPrecedence.overlay(
			LinkedHashMap::new, global, dimension
		);
		assertEquals(Map.of("plains", "desert", "forest", "badlands"), effective);
		assertEquals("taiga", global.get("forest"));
	}
}
