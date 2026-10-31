package etcodehome.freeterraforged.world.worldgen.search;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/** Conservative horizontal terrain sampling derived from the active preset and target geometry. */
public record TerrainSearchResolution(int horizontalStep) {
	private static final int MOUNTAIN_WARP_SCALE = 333;
	private static final int MODERN_MOUNTAIN_SHAPE_SCALE = 1_000;
	private static final int LEGACY_MOUNTAIN_SHAPE_SCALE = 1_000;
	private static final float MODERN_MOUNTAIN_HORIZONTAL_MULTIPLIER = 2.25F;

	public TerrainSearchResolution {
		if (horizontalStep < 4 || horizontalStep % 4 != 0) {
			throw new IllegalArgumentException("Terrain search step must be positive and quart-aligned");
		}
	}

	public static TerrainSearchResolution derive(Preset preset, Terrain target) {
		int scale;
		if (target == TerrainType.DEEP_OCEAN || target == TerrainType.SHALLOW_OCEAN) {
			scale = Math.max(1, preset.world().continent.continentScale);
		} else if (target == TerrainType.ISLAND || target == TerrainType.ISLAND_MOUNTAINS) {
			/*
			 * Archipelago footprints are independent from terrain-region and continent scales, but Heightmap feeds the
			 * island blender coordinates divided by globalHorizontalScale. Convert the preset's island noise size back
			 * to its physical block scale before deriving the locate lattice.
			 */
			float globalScale = Math.max(0.01F, preset.terrain().general.globalHorizontalScale);
			scale = Math.max(1, Math.round(preset.island().size * globalScale));
		} else {
			/*
			 * Land identities are selected by terrain regions and can then be replaced by the mountain-chain
			 * blender. Use the finer of those two preset-controlled structures. The mountain domain warp is
			 * itself a meaningful lower scale, especially when global horizontal scale is small.
			 */
			var terrain = preset.terrain();
			int regionScale = Math.max(1, terrain.general.terrainRegionSize);
			float globalScale = Math.max(0.01F, terrain.general.globalHorizontalScale);
			float shapeScale = terrain.general.legacyMountainScaling
				? LEGACY_MOUNTAIN_SHAPE_SCALE
				: MODERN_MOUNTAIN_SHAPE_SCALE
					* Math.max(0.0F, terrain.mountains.horizontalScale)
					* MODERN_MOUNTAIN_HORIZONTAL_MULTIPLIER;
			int mountainScale = Math.max(
				1,
				Math.round(Math.min(shapeScale, MOUNTAIN_WARP_SCALE) * globalScale)
			);
			scale = Math.min(regionScale, mountainScale);
		}

		int step = BiomeSearchResolution.fromScale(scale);
		if (target == TerrainType.RIVER) {
			int narrowestBed = Math.min(
				Math.max(1, preset.rivers().mainRivers.bedWidth),
				Math.max(1, preset.rivers().branchRivers.bedWidth)
			);
			step = Math.min(step, BiomeSearchResolution.fromScale(narrowestBed));
		} else if (target == TerrainType.WETLAND) {
			step = Math.min(
				step,
				BiomeSearchResolution.fromScale(Math.max(1, preset.rivers().wetlands.sizeMin))
			);
		} else if (target == TerrainType.COAST || target == TerrainType.BEACH
			|| target == TerrainType.VOLCANO_PIPE) {
			// These can collapse to only a few blocks regardless of the containing terrain-region scale.
			step = 4;
		}
		return new TerrainSearchResolution(step);
	}
}
