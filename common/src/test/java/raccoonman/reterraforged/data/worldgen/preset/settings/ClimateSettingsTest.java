package raccoonman.reterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

class ClimateSettingsTest {
	@Test
	void newDefaultPresetsInitializeBothBiomeSizesToTheHistoricalDefault() {
		ClimateSettings.BiomeShape shape = Presets.makeRTFDefault().climate().biomeShape;

		assertEquals(225, shape.biomeSize);
		assertEquals(225, shape.undergroundBiomeSize);
		assertEquals(ClimateSettings.BiomeShape.DEFAULT_UNDERGROUND_VERTICAL_SIZE, shape.undergroundBiomeVerticalSize);
		assertEquals(ClimateSettings.BiomeShape.DEFAULT_UNDERGROUND_BIOME_COVERAGE, shape.undergroundBiomeCoverage);
		assertEquals(ClimateSettings.BiomeShape.DEFAULT_UNDERGROUND_BIOME_CLIMATE_INFLUENCE, shape.undergroundBiomeClimateInfluence);
		assertTrue(shape.undergroundBiomeBanding);
	}

	@Test
	void legacyJsonWithoutUndergroundSizeInheritsTheDefaultSurfaceSize() {
		ClimateSettings.BiomeShape shape = decode(shapeJson(225, null));

		assertEquals(225, shape.biomeSize);
		assertEquals(225, shape.undergroundBiomeSize);
	}

	@Test
	void legacyJsonWithoutUndergroundSizeInheritsACustomSurfaceSize() {
		ClimateSettings.BiomeShape shape = decode(shapeJson(900, null));

		assertEquals(900, shape.biomeSize);
		assertEquals(900, shape.undergroundBiomeSize);
	}

	@Test
	void explicitSurfaceAndUndergroundSizesRemainIndependent() {
		ClimateSettings.BiomeShape shape = decode(shapeJson(900, 50));

		assertEquals(900, shape.biomeSize);
		assertEquals(50, shape.undergroundBiomeSize);
		assertEquals(50, shape.copy().undergroundBiomeSize);
	}

	@Test
	void explicitUndergroundControlsRemainIndependentAndCopyExactly() {
		JsonObject json = shapeJson(900, 50);
		json.addProperty("undergroundBiomeVerticalSize", 32);
		json.addProperty("undergroundBiomeCoverage", 0.4F);
		json.addProperty("undergroundBiomeClimateInfluence", 0.6F);
		json.addProperty("undergroundBiomeBanding", false);

		ClimateSettings.BiomeShape shape = decode(json);
		ClimateSettings.BiomeShape copy = shape.copy();

		assertEquals(32, copy.undergroundBiomeVerticalSize);
		assertEquals(0.4F, copy.undergroundBiomeCoverage);
		assertEquals(0.6F, copy.undergroundBiomeClimateInfluence);
		assertEquals(false, copy.undergroundBiomeBanding);
	}

	@Test
	void codecRejectsBiomeSizesOutsideTheUiRange() {
		assertRejected(shapeJson(0, null));
		assertRejected(shapeJson(49, null));
		assertRejected(shapeJson(225, -1));
		assertRejected(shapeJson(225, 2001));

		JsonObject invalidVertical = shapeJson(225, 225);
		invalidVertical.addProperty("undergroundBiomeVerticalSize", 15);
		assertRejected(invalidVertical);
		JsonObject invalidCoverage = shapeJson(225, 225);
		invalidCoverage.addProperty("undergroundBiomeCoverage", 1.01F);
		assertRejected(invalidCoverage);
		JsonObject invalidInfluence = shapeJson(225, 225);
		invalidInfluence.addProperty("undergroundBiomeClimateInfluence", -0.01F);
		assertRejected(invalidInfluence);
	}

	@Test
	void directConstructionAndMutatedValuesCannotReachSizingCalculations() {
		assertThrows(IllegalArgumentException.class, () -> new ClimateSettings.BiomeShape(0, 8, 150, 80));

		ClimateSettings.BiomeShape shape = new ClimateSettings.BiomeShape(225, 225, 8, 150, 80);
		shape.undergroundBiomeSize = -1;
		assertThrows(IllegalArgumentException.class, shape::undergroundBiomeSize);
		shape.undergroundBiomeSize = 225;
		shape.undergroundBiomeCoverage = Float.NaN;
		assertThrows(IllegalArgumentException.class, shape::undergroundBiomeCoverage);
	}

	@Test
	void verticalSizeIsLimitedByTheFullWorldYAxis() {
		ClimateSettings.BiomeShape shape = new ClimateSettings.BiomeShape(
			225,
			225,
			512,
			0.25F,
			0.75F,
			true,
			8,
			150,
			80
		);

		assertEquals(448, ClimateSettings.BiomeShape.maximumUndergroundVerticalSize(384, 64));
		assertEquals(448, shape.undergroundBiomeVerticalSize(384, 64));
		assertEquals(512, shape.undergroundBiomeVerticalSize(512, 0));
		assertEquals(512, shape.undergroundBiomeVerticalSize(384, 256));
		assertEquals(2048, ClimateSettings.BiomeShape.maximumUndergroundVerticalSize(1024, 1024));
	}

	private static ClimateSettings.BiomeShape decode(JsonObject json) {
		return ClimateSettings.BiomeShape.CODEC.parse(JsonOps.INSTANCE, json)
			.getOrThrow(message -> new AssertionError("Biome shape failed to decode: " + message));
	}

	private static void assertRejected(JsonObject json) {
		assertTrue(ClimateSettings.BiomeShape.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
	}

	private static JsonObject shapeJson(int biomeSize, Integer undergroundBiomeSize) {
		JsonObject json = JsonParser.parseString("""
			{
			  "biomeSize": 225,
			  "macroNoiseSize": 8,
			  "biomeWarpScale": 150,
			  "biomeWarpStrength": 80
			}
			""").getAsJsonObject();
		json.addProperty("biomeSize", biomeSize);
		if (undergroundBiomeSize != null) {
			json.addProperty("undergroundBiomeSize", undergroundBiomeSize);
		}
		return json;
	}
}
