package etcodehome.freeterraforged.world.worldgen.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeReplacementConfigTest {

	@Test
	void testParseValidRules() {
		String content = """
			# Comment line
			! Another comment
			format_version=1
			minecraft:plains > minecraft:desert
			minecraft:deep_dark > null
			#minecraft:is_forest > minecraft:plains
			""";

		BiomeReplacementConfig config = BiomeReplacementConfig.parse(content);
		assertEquals(1, config.formatVersion());
		assertFalse(config.legacyImport());
		assertFalse(config.hasErrors());
		assertEquals(3, config.rules().size());

		BiomeReplacementConfig.Rule r1 = config.rules().get(0);
		assertEquals(BiomeReplacementConfig.SelectorKind.BIOME, r1.selectorKind());
		assertEquals("minecraft:plains", r1.selector());
		assertEquals(BiomeReplacementConfig.ActionKind.REPLACE, r1.actionKind());
		assertEquals("minecraft:desert", r1.target());
		assertNull(r1.dimension());

		BiomeReplacementConfig.Rule r2 = config.rules().get(1);
		assertEquals(BiomeReplacementConfig.SelectorKind.BIOME, r2.selectorKind());
		assertEquals("minecraft:deep_dark", r2.selector());
		assertEquals(BiomeReplacementConfig.ActionKind.REMOVE, r2.actionKind());
		assertNull(r2.target());

		BiomeReplacementConfig.Rule r3 = config.rules().get(2);
		assertEquals(BiomeReplacementConfig.SelectorKind.TAG, r3.selectorKind());
		assertEquals("minecraft:is_forest", r3.selector());
		assertEquals(BiomeReplacementConfig.ActionKind.REPLACE, r3.actionKind());
		assertEquals("minecraft:plains", r3.target());
	}

	@Test
	void testDimensionHeaders() {
		String content = """
			minecraft:plains > minecraft:desert
			[minecraft:the_nether]
			minecraft:crimson_forest > minecraft:warped_forest
			[]
			minecraft:forest > minecraft:savanna
			""";

		BiomeReplacementConfig config = BiomeReplacementConfig.parse(content);
		assertEquals(3, config.rules().size());
		assertNull(config.rules().get(0).dimension());
		assertEquals("minecraft:the_nether", config.rules().get(1).dimension());
		assertNull(config.rules().get(2).dimension());
	}

	@Test
	void testUnsupportedFormatVersion() {
		String content = "format_version=2\nminecraft:plains > minecraft:desert";
		BiomeReplacementConfig config = BiomeReplacementConfig.parse(content);
		assertTrue(config.hasErrors());
		assertTrue(config.diagnostics().stream().anyMatch(d -> "unsupported_format_version".equals(d.code())));
	}

	@Test
	void testInvalidFormatVersion() {
		String content = "format_version=xyz\nminecraft:plains > minecraft:desert";
		BiomeReplacementConfig config = BiomeReplacementConfig.parse(content);
		assertTrue(config.hasErrors());
		assertTrue(config.diagnostics().stream().anyMatch(d -> "invalid_format_version".equals(d.code())));
	}

	@Test
	void testMigrationSourceLegacyImport() {
		String content = """
			format_version=1
			migration_source=biome_replacer
			unknown_legacy_setting=true
			minecraft:plains > minecraft:desert
			""";

		BiomeReplacementConfig config = BiomeReplacementConfig.parse(content);
		assertTrue(config.legacyImport());
		assertFalse(config.hasErrors()); // unknown_legacy_setting is demoted to WARNING in legacy mode
		assertTrue(config.diagnostics().stream().anyMatch(d -> "unknown_option".equals(d.code())
			&& d.severity() == BiomeReplacementConfig.Severity.WARNING));
	}

	@Test
	void testUnknownOptionNativeModeIsError() {
		String content = """
			format_version=1
			unknown_setting=true
			minecraft:plains > minecraft:desert
			""";

		BiomeReplacementConfig config = BiomeReplacementConfig.parse(content);
		assertFalse(config.legacyImport());
		assertTrue(config.hasErrors());
		assertTrue(config.diagnostics().stream().anyMatch(d -> "unknown_option".equals(d.code())
			&& d.severity() == BiomeReplacementConfig.Severity.ERROR));
	}

	@Test
	void testMalformedRuleDetected() {
		String content = "minecraft:plains without arrow";
		BiomeReplacementConfig config = BiomeReplacementConfig.parse(content);
		assertTrue(config.hasErrors());
		assertTrue(config.diagnostics().stream().anyMatch(d -> "malformed_rule".equals(d.code())));
	}

	@Test
	void testProbabilitySuffixRejected() {
		String content = "minecraft:plains > minecraft:desert 0.5";
		BiomeReplacementConfig config = BiomeReplacementConfig.parse(content);
		assertTrue(config.hasErrors());
		assertTrue(config.diagnostics().stream().anyMatch(d -> "unsupported_probability".equals(d.code())));
	}
}
