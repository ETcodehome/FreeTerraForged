package etcodehome.freeterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BiomeReplacementConfigTest {
	@Test
	void parsesLegacyGrammarWithLineAndScopeInformation() {
		BiomeReplacementConfig config = BiomeReplacementConfig.parse("""
			! ordinary comment
			# legacy comment with a space
			minecraft:plains > minecraft:desert
			[minecraft:the_nether]
			#minecraft:is_nether > null
			[]
			minecraft:forest > minecraft:birch_forest
			format_version=1
			validation=strict
			placement=preserve
			""");

		assertFalse(config.hasErrors());
		assertEquals(BiomeReplacementConfig.ValidationMode.STRICT, config.validationMode());
		assertEquals(3, config.rules().size());

		BiomeReplacementConfig.Rule global = config.rules().get(0);
		assertEquals(3, global.line());
		assertEquals(BiomeReplacementConfig.SelectorKind.BIOME, global.selectorKind());
		assertEquals("minecraft:plains", global.selector());
		assertEquals("minecraft:desert", global.target());

		BiomeReplacementConfig.Rule nether = config.rules().get(1);
		assertEquals("minecraft:the_nether", nether.dimension());
		assertEquals(BiomeReplacementConfig.SelectorKind.TAG, nether.selectorKind());
		assertEquals(BiomeReplacementConfig.ActionKind.REMOVE, nether.actionKind());

		BiomeReplacementConfig.Rule resetGlobal = config.rules().get(2);
		assertEquals(null, resetGlobal.dimension());
	}

	@Test
	void reportsMalformedRulesAndUnsupportedProbability() {
		BiomeReplacementConfig config = BiomeReplacementConfig.parse("""
			validation=strict
			minecraft:plains minecraft:desert
			minecraft:forest > minecraft:desert 0.5
			#> minecraft:plains
			""");

		assertTrue(config.hasErrors());
		assertEquals(
			java.util.List.of("malformed_rule", "unsupported_probability", "empty_selector"),
			config.diagnostics().stream().map(BiomeReplacementConfig.Diagnostic::code).toList()
		);
	}

	@Test
	void compatibilityModeWarnsForUnknownLegacyOptions() {
		BiomeReplacementConfig config = BiomeReplacementConfig.parse("""
			unknown_legacy_option=true
			validation=compatibility
			minecraft:plains > minecraft:desert
			""");

		assertEquals(BiomeReplacementConfig.ValidationMode.COMPATIBILITY, config.validationMode());
		assertFalse(config.hasErrors());
		assertEquals(BiomeReplacementConfig.Severity.WARNING, config.diagnostics().getFirst().severity());
	}

	@Test
	void strictModeRejectsUnknownOptionsAndInvalidPlacement() {
		BiomeReplacementConfig config = BiomeReplacementConfig.parse("""
			validation=strict
			unknown=true
			placement=relocate
			""");

		assertTrue(config.hasErrors());
		assertEquals(
			java.util.Set.of("unknown_option", "invalid_placement"),
			config.diagnostics().stream()
				.map(BiomeReplacementConfig.Diagnostic::code)
				.collect(java.util.stream.Collectors.toSet())
		);
		assertEquals(
			java.util.Map.of("unknown_option", 2, "invalid_placement", 3),
			config.diagnostics().stream().collect(java.util.stream.Collectors.toMap(
				BiomeReplacementConfig.Diagnostic::code,
				BiomeReplacementConfig.Diagnostic::line
			))
		);
	}
}
