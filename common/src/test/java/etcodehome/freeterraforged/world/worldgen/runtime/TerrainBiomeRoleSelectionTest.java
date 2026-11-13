package etcodehome.freeterraforged.world.worldgen.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainBiomeRoleSelectionTest {

	@Test
	@DisplayName("Submerged check returns false when terrain is not a river")
	void testSubmergedCheckRequiresRiverTerrain() {
		Cell cell = new Cell();
		cell.terrain = TerrainType.FLATS;
		cell.height = 0.20F;
		cell.riverWaterLevel = 0.30F;

		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell), "Non-river terrain should never be considered a submerged river");
		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell, 0.25F), "Non-river terrain should never be considered a submerged river with sea level");

		cell.terrain = TerrainType.BEACH;
		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell), "Beach terrain should not match river submerged check");

		cell.terrain = TerrainType.NONE;
		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell), "None terrain should not match river submerged check");
	}

	@Test
	@DisplayName("Submerged check evaluates river height against riverWaterLevel")
	void testSubmergedCheckWithExplicitRiverWaterLevel() {
		Cell cell = new Cell();
		cell.terrain = TerrainType.RIVER;
		cell.riverWaterLevel = 0.30F;

		// Inside carved channel below river water level
		cell.height = 0.25F;
		assertTrue(TerrainBiomeRoleSelection.isSubmerged(cell), "River bed below water level must be submerged");
		assertTrue(TerrainBiomeRoleSelection.isSubmerged(cell, 0.24F), "River bed below water level must be submerged");

		// At exact waterline
		cell.height = 0.30F;
		assertTrue(TerrainBiomeRoleSelection.isSubmerged(cell), "River cell at exact waterline must be submerged");

		// Above river water level (dry river banks, valley floors, or uncarved land)
		cell.height = 0.3001F;
		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell), "River cell above water level must not be submerged");

		cell.height = 0.45F;
		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell), "Elevated dry river cell must not be submerged");
	}

	@Test
	@DisplayName("Submerged check falls back to default water level when riverWaterLevel is 0")
	void testSubmergedCheckFallbackToDefaultWaterLevel() {
		Cell cell = new Cell();
		cell.terrain = TerrainType.RIVER;
		cell.riverWaterLevel = 0.0F;

		// Height 0.20F with default seaLevel 0.24F
		cell.height = 0.20F;
		assertTrue(TerrainBiomeRoleSelection.isSubmerged(cell, 0.24F), "Should be submerged when below default water level");
		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell), "Without seaLevel, 0.20F > 0.0F should return false");

		// Height 0.28F with default seaLevel 0.24F
		cell.height = 0.28F;
		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell, 0.24F), "Should not be submerged when above default water level");
	}
}
