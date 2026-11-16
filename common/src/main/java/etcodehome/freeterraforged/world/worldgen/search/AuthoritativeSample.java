package etcodehome.freeterraforged.world.worldgen.search;

import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

/** One independently filtered cell and its dependency halo, scoped to the current locate worker. */
final class AuthoritativeSample implements AutoCloseable {
	private static final ThreadLocal<Cell> RAW_CELL = ThreadLocal.withInitial(Cell::new);

	private final GeneratorContext context;
	@Nullable
	private final PointFilterGrid grid;
	private final Cell cell;
	private final int blockX;
	private final int blockZ;
	private final boolean raw;
	private final CellSampler.LookupScope lookupScope;
	private boolean closed;

	private AuthoritativeSample(
		GeneratorContext context,
		int blockX,
		int blockZ,
		boolean sampleClimate,
		@Nullable Predicate<Cell> rawEquivalent
	) {
		this.context = context;
		this.blockX = blockX;
		this.blockZ = blockZ;
		Cell generated = context.filteredChunkCache.getExistingFilteredCell(blockX, blockZ);
		Cell rawCell = null;
		if (generated == null && rawEquivalent != null) {
			rawCell = RAW_CELL.get();
			context.heightmap.apply(rawCell.reset(), blockX, blockZ, sampleClimate);
			if (!rawEquivalent.test(rawCell)) {
				rawCell = null;
			}
		}
		this.raw = rawCell != null;
		this.grid = generated == null && rawCell == null
			? PointFilterGrid.generate(blockX, blockZ, context, sampleClimate)
			: null;
		this.cell = generated != null ? generated : rawCell != null ? rawCell : this.grid.center();
		this.lookupScope = CellSampler.pushLookupOverride(
			context.lookup,
			(x, z, ignoredClimate) -> this.lookup(x, z)
		);
	}

	static AuthoritativeSample open(
		GeneratorContext context,
		int blockX,
		int blockZ,
		boolean sampleClimate,
		@Nullable Predicate<Cell> rawEquivalent
	) {
		return new AuthoritativeSample(context, blockX, blockZ, sampleClimate, rawEquivalent);
	}

	Cell cell() {
		return this.cell;
	}

	@Nullable
	private Cell lookup(int blockX, int blockZ) {
		if (this.grid == null) {
			if (this.raw && blockX == this.blockX && blockZ == this.blockZ) {
				return this.cell;
			}
			return this.context.filteredChunkCache.getExistingFilteredCell(blockX, blockZ);
		}
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
