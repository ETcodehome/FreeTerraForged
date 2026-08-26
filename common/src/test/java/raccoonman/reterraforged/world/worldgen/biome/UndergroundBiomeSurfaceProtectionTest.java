package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UndergroundBiomeSurfaceProtectionTest {

	@Test
	void completeQuartShellIsCaveFree() {
		assertEquals(0.0F, UndergroundBiomeSurfaceProtection.coverageFactor(8.0F));
	}

	@Test
	void coverageFadesInBehindTheHardShell() {
		assertEquals(0.5F, UndergroundBiomeSurfaceProtection.coverageFactor(20.0F));
	}

	@Test
	void coverageIsFullAfterTheTransition() {
		assertEquals(1.0F, UndergroundBiomeSurfaceProtection.coverageFactor(32.0F));
	}

	@Test
	void exposedNeighborCanProtectADeepLocalColumn() {
		assertEquals(0.0F, UndergroundBiomeSurfaceProtection.coverageFactor(4.0F));
	}

	@Test
	void neighborhoodUsesTheLowestAdjacentTerrainColumn() {
		assertEquals(
			0.0F,
			UndergroundBiomeSurfaceProtection.coverageFactor(
				(x, z) -> x >= 4 ? 8.0F : 100.0F,
				0,
				0,
				0
			),
			0.0001F
		);
		assertEquals(
			1.0F,
			UndergroundBiomeSurfaceProtection.coverageFactor(
				(x, z) -> x >= 4 ? 8.0F : 100.0F,
				0,
				-6,
				0
			),
			0.0001F
		);
	}

	@Test
	void blockResolutionCatchesANarrowExposureInsideTheBiomeCell() {
		assertEquals(
			0.0F,
			UndergroundBiomeSurfaceProtection.coverageFactor(
				(x, z) -> x == 2 && z == 2 ? 8.0F : 100.0F,
				0,
				0,
				0
			),
			0.0001F
		);
	}
}
