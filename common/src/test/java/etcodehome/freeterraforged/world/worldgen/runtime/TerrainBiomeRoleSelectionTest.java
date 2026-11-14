package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.List;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

	@Test
	@DisplayName("Lookup override ensures river submerged check uses authoritative pixel cell rather than floored quart corner")
	void testLookupOverridePreventsQuartCornerSteppingOnRiverBanks() {
		WorldLookup lookup = new WorldLookup(null, null) {
			@Override
			public boolean applyCell(Cell cell, int x, int z, boolean load, boolean applyClimate) {
				// Simulates a submerged river cell at the quart NW corner (x, z)
				cell.terrain = TerrainType.RIVER;
				cell.height = 0.20F;
				cell.riverWaterLevel = 0.30F;
				return true;
			}
		};

		int quartX = 10;
		int quartZ = 20;

		// 1. Without lookup override, querying the quart block coordinate resolves the submerged corner
		Cell unoverridden = CellSampler.getCachedOrLookup(lookup, QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ), false);
		assertTrue(TerrainBiomeRoleSelection.isSubmerged(unoverridden, 0.25F),
			"Without override, quart NW corner cell is submerged");

		// 2. With lookup override installed (as done during BiomePreviewResolver surface tile resolution),
		// the dry bank cell for the current pixel is returned instead of the submerged corner
		Cell dryBankCell = new Cell();
		dryBankCell.terrain = TerrainType.RIVER;
		dryBankCell.height = 0.35F; // Above water level 0.30F
		dryBankCell.riverWaterLevel = 0.30F;

		try (CellSampler.LookupScope scope = CellSampler.pushLookupOverride(
			lookup,
			(bx, bz, sampleClimate) -> dryBankCell
		)) {
			Cell overridden = CellSampler.getCachedOrLookup(lookup, QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ), false);
			assertEquals(dryBankCell, overridden, "Lookup override must supply authoritative pixel cell");
			assertFalse(TerrainBiomeRoleSelection.isSubmerged(overridden, 0.25F),
				"Override prevents dry bank pixel from falsely evaluating as submerged river");
		}

		// 3. After closing override scope, normal lookup resumes
		Cell restored = CellSampler.getCachedOrLookup(lookup, QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ), false);
		assertTrue(TerrainBiomeRoleSelection.isSubmerged(restored, 0.25F),
			"Normal lookup resumes once override scope closes");
	}

	@Test
	@DisplayName("Submerged check respects tolerance parameter")
	void testSubmergedCheckWithTolerance() {
		Cell cell = new Cell();
		cell.terrain = TerrainType.RIVER;
		cell.riverWaterLevel = 0.30F;

		// Exactly at water level + 0.01F
		cell.height = 0.31F;
		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell, 0.24F, 0.0F),
			"Without tolerance, height above water level is not submerged");
		assertTrue(TerrainBiomeRoleSelection.isSubmerged(cell, 0.24F, 0.02F),
			"With tolerance >= 0.01F, height within tolerance is submerged");

		// Above water level + tolerance
		cell.height = 0.35F;
		assertFalse(TerrainBiomeRoleSelection.isSubmerged(cell, 0.24F, 0.02F),
			"Height exceeding water level + tolerance is not submerged");
	}

	@Test
	@DisplayName("taggedSurfaceCandidates correctly separates river and non-river candidates")
	void testTaggedSurfaceCandidatesSeparation() {
		Climate.ParameterPoint surfacePoint = Climate.parameters(0, 0, 0, 0, 0, 0, 0);
		Climate.ParameterPoint cavePoint = Climate.parameters(0, 0, 0, 0, 1.0F, 0, 0);

		Climate.ParameterList<String> list = new Climate.ParameterList<>(List.of(
			Pair.of(surfacePoint, "river"),
			Pair.of(surfacePoint, "plains"),
			Pair.of(surfacePoint, "forest"),
			Pair.of(cavePoint, "lush_caves")
		));

		Climate.ParameterList<String> rivers = TerrainBiomeRoleSelection.taggedSurfaceCandidates(
			list, "river"::equals
		);
		assertNotNull(rivers, "River list should not be null");
		assertEquals(1, rivers.values().size(), "Only surface river candidate should be included");
		assertEquals("river", rivers.values().get(0).getSecond());

		Climate.ParameterList<String> nonRivers = TerrainBiomeRoleSelection.taggedSurfaceCandidates(
			list, s -> !"river".equals(s)
		);
		assertNotNull(nonRivers, "Non-river list should not be null");
		assertEquals(2, nonRivers.values().size(), "Plains and forest should be included; cave excluded");
		assertTrue(nonRivers.values().stream().anyMatch(p -> "plains".equals(p.getSecond())));
		assertTrue(nonRivers.values().stream().anyMatch(p -> "forest".equals(p.getSecond())));
		assertFalse(nonRivers.values().stream().anyMatch(p -> "lush_caves".equals(p.getSecond())));
	}
}
