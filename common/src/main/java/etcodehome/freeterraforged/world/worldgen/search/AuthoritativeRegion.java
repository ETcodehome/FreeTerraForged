package etcodehome.freeterraforged.world.worldgen.search;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.RegionFilterGrid;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

/** One transient, fully filtered terrain region used by a locate worker. */
final class AuthoritativeRegion implements AutoCloseable {
	private final RegionFilterGrid grid;
	private final CellSampler.LookupScope lookupScope;
	private boolean closed;

	private AuthoritativeRegion(
		GeneratorContext context,
		ParallelFirstSearch.Tile tile
	) {
		this.grid = RegionFilterGrid.THREAD_LOCAL.get();
		this.grid.init(tile.regionChunkX(), tile.regionChunkZ(), context);
		if (context.filters != null) {
			context.filters.apply(this.grid);
		}
		this.lookupScope = CellSampler.pushLookupOverride(
			context.lookup,
			(blockX, blockZ, sampleClimate) -> this.lookup(blockX, blockZ)
		);
	}

	static AuthoritativeRegion open(
		GeneratorContext context,
		ParallelFirstSearch.Tile tile
	) {
		return new AuthoritativeRegion(context, tile);
	}

	Cell cell(int blockX, int blockZ) {
		Cell cell = this.lookup(blockX, blockZ);
		if (cell == null) {
			throw new IllegalArgumentException(
				"Coordinate " + blockX + "," + blockZ + " is outside the filtered locate region"
			);
		}
		return cell;
	}

	@Nullable
	private Cell lookup(int blockX, int blockZ) {
		int localX = blockX - this.grid.getBlockX();
		int localZ = blockZ - this.grid.getBlockZ();
		if (localX < 0 || localX >= this.grid.getTotalSize()
			|| localZ < 0 || localZ >= this.grid.getTotalSize()) {
			return null;
		}
		return this.grid.getCellRaw(localX, localZ);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.lookupScope.close();
		}
	}
}
