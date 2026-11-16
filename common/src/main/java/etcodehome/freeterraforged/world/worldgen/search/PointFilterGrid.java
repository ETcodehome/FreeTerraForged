package etcodehome.freeterraforged.world.worldgen.search;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid;
import etcodehome.freeterraforged.world.worldgen.cell.filter.FilterGrid;

/** A reusable one-cell filter target plus the same 12-block dependency halo used by chunk generation. */
final class PointFilterGrid implements FilterGrid {
	static final int BORDER = ChunkFilterGrid.BORDER;
	static final int TOTAL_SIZE = BORDER * 2 + 1;
	static final int TOTAL_CELLS = TOTAL_SIZE * TOTAL_SIZE;
	private static final ThreadLocal<PointFilterGrid> THREAD_LOCAL = ThreadLocal.withInitial(PointFilterGrid::new);

	private final Cell[] cells = new Cell[TOTAL_CELLS];
	private int blockX;
	private int blockZ;

	private PointFilterGrid() {
		for (int i = 0; i < this.cells.length; i++) {
			this.cells[i] = new Cell();
		}
	}

	/**
	 * Builds the exact filter dependency halo. Climate values are populated only at the query point when requested:
	 * the terrain-filter pipeline reads terrain, height, river and spatial-region state from halo cells, but never
	 * temperature, moisture or macro-biome values. Chunk generation computes those extra values everywhere for later
	 * consumers; a sparse locate query has no such consumer for its halo.
	 */
	static PointFilterGrid generate(
		int centerX,
		int centerZ,
		GeneratorContext context,
		boolean sampleCenterClimate
	) {
		PointFilterGrid grid = THREAD_LOCAL.get();
		grid.blockX = centerX - BORDER;
		grid.blockZ = centerZ - BORDER;
		for (int z = 0; z < TOTAL_SIZE; z++) {
			int worldZ = grid.blockZ + z;
			int row = z * TOTAL_SIZE;
			for (int x = 0; x < TOTAL_SIZE; x++) {
				context.heightmap.apply(
					grid.cells[row + x].reset(),
					grid.blockX + x,
					worldZ,
					sampleCenterClimate && x == BORDER && z == BORDER
				);
			}
		}
		if (context.filters != null) {
			context.filters.apply(grid);
		}
		return grid;
	}

	Cell center() {
		return this.cells[BORDER * TOTAL_SIZE + BORDER];
	}

	@Override
	public int getBlockX() {
		return this.blockX;
	}

	@Override
	public int getBlockZ() {
		return this.blockZ;
	}

	@Override
	public int getSize() {
		return 1;
	}

	@Override
	public int getBorder() {
		return BORDER;
	}

	@Override
	public int getTotalSize() {
		return TOTAL_SIZE;
	}

	@Override
	public Cell[] getBacking() {
		return this.cells;
	}

	@Override
	public Cell getCellRaw(int x, int z) {
		if (x < 0 || x >= TOTAL_SIZE || z < 0 || z >= TOTAL_SIZE) {
			return Cell.empty();
		}
		return this.cells[z * TOTAL_SIZE + x];
	}
}
