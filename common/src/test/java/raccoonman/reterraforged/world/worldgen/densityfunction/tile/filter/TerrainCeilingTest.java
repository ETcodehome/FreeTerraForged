package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;

class TerrainCeilingTest {
	@Test
	void leavesTerrainBelowTheCompressionBandUnchanged() {
		TerrainCeiling ceiling = defaultCeiling();

		assertEquals(1.0F, compress(1.0F, ceiling));
	}

	@Test
	void smoothlyCompressesTerrainAboveTheCompressionBand() {
		TerrainCeiling ceiling = defaultCeiling();
		float lower = compress(1.5F, ceiling);
		float higher = compress(2.0F, ceiling);

		assertTrue(lower > ceiling.compressionStart());
		assertTrue(higher > lower);
		assertEquals(ceiling.tailStart(), higher);
	}

	@Test
	void keepsExtremeTerrainStrictlyBelowTheReservedSurfaceHeight() {
		TerrainCeiling ceiling = defaultCeiling();
		float compressed = compress(1000.0F, ceiling);

		assertTrue(compressed < ceiling.maximum());
		assertEquals(368.0F / 256.0F, ceiling.maximum());
	}

	@Test
	void preservesOrderingAndUsefulReliefAcrossTheObservedOverflowRange() {
		TerrainCeiling ceiling = defaultCeiling();
		float lower = compress(449.0F / 256.0F, ceiling);
		float higher = compress(486.0F / 256.0F, ceiling);

		assertTrue(higher > lower);
		assertTrue((higher - lower) * 256.0F > 12.0F);
	}

	@Test
	void givesTallWorldsEnoughRoomToLeaveOrdinaryMountainsUntouched() {
		WorldSettings.Properties properties = Presets.makeRTFDefault().world().properties;
		properties.worldHeight = 1024;
		TerrainCeiling ceiling = TerrainCeiling.make(properties);
		float mountainHeight = 486.0F / Math.min(properties.worldHeight, 256);

		assertEquals(mountainHeight, compress(mountainHeight, ceiling));
	}

	@Test
	void handlesAZeroHeightPresetWithoutNonFiniteValues() {
		WorldSettings.Properties properties = Presets.makeRTFDefault().world().properties;
		properties.worldHeight = 0;
		TerrainCeiling ceiling = TerrainCeiling.make(properties);

		assertTrue(Float.isFinite(compress(1.0F, ceiling)));
	}

	private static float compress(float height, TerrainCeiling ceiling) {
		return TerrainCeiling.compress(
			height,
			ceiling.compressionStart(),
			ceiling.linearEnd(),
			ceiling.tailStart(),
			ceiling.maximum()
		);
	}

	private static TerrainCeiling defaultCeiling() {
		return TerrainCeiling.make(Presets.makeRTFDefault().world().properties);
	}
}
