package etcodehome.freeterraforged.data.worldgen.preset.settings;

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
		ClimateSettings.BiomeShape shape = Presets.makeFTFDefault().climate().biomeShape;

		assertEquals(225, shape.biomeSize);
		assertEquals(225, shape.undergroundBiomeSize);
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

	private static ClimateSettings.BiomeShape decode(JsonObject json) {
		return ClimateSettings.BiomeShape.CODEC.parse(JsonOps.INSTANCE, json)
			.getOrThrow(message -> new AssertionError("Biome shape failed to decode: " + message));
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
