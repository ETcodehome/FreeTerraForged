package etcodehome.freeterraforged.world.worldgen.search;

import java.util.List;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/** The canonical terrain targets supported by FTF locate and preview searches. */
public final class TerrainSearchTargets {
	/** Island beaches report as beaches, so the internal island-beach terrain is intentionally omitted. */
	private static final List<Terrain> VALUES = List.of(
		TerrainType.DEEP_OCEAN,
		TerrainType.SHALLOW_OCEAN,
		TerrainType.COAST,
		TerrainType.BEACH,
		TerrainType.RIVER,
		TerrainType.WETLAND,
		TerrainType.FLATS,
		TerrainType.BADLANDS,
		TerrainType.PLATEAU,
		TerrainType.HILLS,
		TerrainType.MOUNTAINS_1,
		TerrainType.MOUNTAINS_2,
		TerrainType.MOUNTAINS_3,
		TerrainType.MOUNTAIN_CHAIN,
		TerrainType.VOLCANO,
		TerrainType.VOLCANO_PIPE,
		TerrainType.ISLAND,
		TerrainType.ISLAND_MOUNTAINS
	);

	private TerrainSearchTargets() {
	}

	public static Stream<String> names() {
		return VALUES.stream().map(Terrain::getName);
	}

	public static boolean contains(Terrain terrain) {
		return VALUES.contains(terrain);
	}

	@Nullable
	public static Terrain get(String name) {
		Terrain terrain = TerrainType.get(name);
		return terrain != null && contains(terrain) ? terrain : null;
	}
}
