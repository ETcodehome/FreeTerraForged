package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseSettings;

class NoiseFillExtentTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void ownsTheCompleteConfiguredHeightIntersection() {
		NoiseSettings configured = NoiseSettings.create(-64, 2048, 1, 2);
		LevelHeightAccessor generation = LevelHeightAccessor.create(-32, 1024);

		NoiseFillExtent extent = NoiseFillExtent.fullConfiguredHeight(configured, generation);

		assertEquals(-32, extent.minY());
		assertEquals(1024, extent.height());
		assertEquals(8, extent.cellHeight());
		assertEquals(-4, extent.minCellY());
		assertEquals(128, extent.cellCountY());
		assertEquals(992, extent.maxYExclusive());
	}

	@Test
	void rejectsAnExtentWhoseCellsCannotDescribeItsRange() {
		IllegalArgumentException failure = assertThrows(
			IllegalArgumentException.class,
			() -> new NoiseFillExtent(-64, 2048, 8, -8, 255)
		);
		assertTrue(failure.getMessage().contains("cellCountY"));
	}

	@Test
	void disjointConfiguredAndGenerationRangesProduceTheVanillaEmptyRequest() {
		NoiseSettings configured = NoiseSettings.create(-64, 2048, 1, 2);
		LevelHeightAccessor generation = LevelHeightAccessor.create(3008, 256);

		NoiseFillExtent extent = NoiseFillExtent.fullConfiguredHeight(configured, generation);

		assertTrue(extent.empty());
		assertEquals(0, extent.height());
		assertEquals(3008, extent.minY());
	}

	@Test
	void rejectsUnalignedClampedSettingsInsteadOfRoundingAllocationAndTraversalDifferently() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new NoiseFillExtent(-63, 2048, 8, Math.floorDiv(-63, 8), 256)
		);
	}
}
