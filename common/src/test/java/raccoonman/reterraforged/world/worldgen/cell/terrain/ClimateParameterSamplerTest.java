package raccoonman.reterraforged.world.worldgen.cell.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClimateParameterSamplerTest {
	@Test
	void normalizationGainIsCompensatedInTheSourceScale() {
		assertEquals(125, ClimateParameterSampler.sourceScale(50, 1.0F));
		assertEquals(1465, ClimateParameterSampler.sourceScale(586, 1.0F));
		assertEquals(2250, ClimateParameterSampler.sourceScale(900, 1.0F));
		assertEquals(2930, ClimateParameterSampler.sourceScale(586, 0.5F));
		assertEquals(733, ClimateParameterSampler.sourceScale(586, 2.0F));
	}

	@Test
	void terrainCoordinateScalingPreservesThePhysicalBiomeScale() {
		int halfTerrainCoordinates = ClimateParameterSampler.sourceScale(586, 0.5F);
		int normalTerrainCoordinates = ClimateParameterSampler.sourceScale(586, 1.0F);
		int doubleTerrainCoordinates = ClimateParameterSampler.sourceScale(586, 2.0F);

		assertEquals(normalTerrainCoordinates, Math.round(halfTerrainCoordinates * 0.5F));
		assertEquals(normalTerrainCoordinates, Math.round(doubleTerrainCoordinates * 2.0F), 1);
	}
}
