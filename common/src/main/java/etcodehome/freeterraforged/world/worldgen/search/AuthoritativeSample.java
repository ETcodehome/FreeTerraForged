package etcodehome.freeterraforged.world.worldgen.search;

import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

/** One independently filtered cell and its dependency halo, scoped to the current locate worker. */
final class AuthoritativeSample implements AutoCloseable {
	private static final ThreadLocal<Cell> RAW_CELL = ThreadLocal.withInitial(Cell::new);
	private static final ThreadLocal<Cell> NEIGHBOR_RAW_CELL = ThreadLocal.withInitial(Cell::new);

	private final GeneratorContext context;
	@Nullable
	private final PointFilterGrid grid;
	private final Cell cell;
	private final int blockX;
	private final int blockZ;
	private final boolean raw;
	@Nullable
	private final CellSampler.LookupScope lookupScope;
	private boolean closed;

	private AuthoritativeSample(
		GeneratorContext context,
		int blockX,
		int blockZ,
		Cell cell,
		boolean raw,
		@Nullable PointFilterGrid grid,
		boolean installLookupScope
	) {
		this.context = context;
		this.blockX = blockX;
		this.blockZ = blockZ;
		this.cell = cell;
		this.raw = raw;
		this.grid = grid;
		this.lookupScope = installLookupScope
			? CellSampler.pushLookupOverride(
				context.lookup,
				(x, z, ignoredClimate) -> this.lookup(x, z)
			)
			: null;
	}

	private static AuthoritativeSample create(
		GeneratorContext context,
		int blockX,
		int blockZ,
		boolean sampleClimate,
		@Nullable Predicate<Cell> rawEquivalent,
		boolean neighbor
	) {
		Cell activeRegionCell = neighbor ? AuthoritativeRegion.findActiveInteriorCell(context, blockX, blockZ) : null;
		if (activeRegionCell != null) {
			return new AuthoritativeSample(context, blockX, blockZ, activeRegionCell, false, null, false);
		}
		Cell generated = context.filteredChunkCache.getExistingFilteredCell(blockX, blockZ);
		if (generated != null) {
			return new AuthoritativeSample(context, blockX, blockZ, generated, false, null, true);
		}
		Cell rawCell = null;
		if (rawEquivalent != null) {
			rawCell = (neighbor ? NEIGHBOR_RAW_CELL : RAW_CELL).get();
			context.heightmap.apply(rawCell.reset(), blockX, blockZ, sampleClimate);
			if (!rawEquivalent.test(rawCell)) {
				rawCell = null;
			}
		}
		if (rawCell != null) {
			return new AuthoritativeSample(context, blockX, blockZ, rawCell, true, null, true);
		}
		PointFilterGrid grid = neighbor
			? PointFilterGrid.generateNeighbor(blockX, blockZ, context, sampleClimate)
			: PointFilterGrid.generate(blockX, blockZ, context, sampleClimate);
		return new AuthoritativeSample(context, blockX, blockZ, grid.center(), false, grid, true);
	}

	static AuthoritativeSample open(
		GeneratorContext context,
		int blockX,
		int blockZ,
		boolean sampleClimate,
		@Nullable Predicate<Cell> rawEquivalent
	) {
		return create(context, blockX, blockZ, sampleClimate, rawEquivalent, false);
	}

	static AuthoritativeSample openNeighbor(
		GeneratorContext context,
		int blockX,
		int blockZ,
		boolean sampleClimate,
		@Nullable Predicate<Cell> rawEquivalent
	) {
		return create(context, blockX, blockZ, sampleClimate, rawEquivalent, true);
	}

	@Nullable
	static AuthoritativeSample openIfCandidate(
		GeneratorContext context,
		int blockX,
		int blockZ,
		boolean sampleClimate,
		@Nullable Predicate<Cell> rawEquivalent,
		@Nullable BiomeLocateCellOptimization.TargetFilter targetFilter
	) {
		if (targetFilter == null) {
			return open(context, blockX, blockZ, sampleClimate, rawEquivalent);
		}
		Cell generated = context.filteredChunkCache.getExistingFilteredCell(blockX, blockZ);
		if (generated != null) {
			return new AuthoritativeSample(context, blockX, blockZ, generated, false, null, true);
		}
		Cell rawCell = RAW_CELL.get();
		if (!targetFilter.requiresClimateForRawPrecheck()) {
			context.heightmap.apply(rawCell.reset(), blockX, blockZ, false);
			if (!targetFilter.canMatchRaw(rawCell)) {
				return null;
			}
			if (sampleClimate) {
				context.heightmap.applyClimate(rawCell, blockX, blockZ, true);
			}
		} else {
			context.heightmap.apply(rawCell.reset(), blockX, blockZ, sampleClimate);
			if (!targetFilter.canMatchRaw(rawCell)) {
				return null;
			}
		}
		if (rawEquivalent != null && rawEquivalent.test(rawCell)) {
			return new AuthoritativeSample(context, blockX, blockZ, rawCell, true, null, true);
		}
		PointFilterGrid grid = PointFilterGrid.generate(blockX, blockZ, context, sampleClimate);
		return new AuthoritativeSample(context, blockX, blockZ, grid.center(), false, grid, true);
	}

	static AuthoritativeSample wrapResolved(
		GeneratorContext context,
		int blockX,
		int blockZ,
		Cell cell,
		boolean raw
	) {
		return new AuthoritativeSample(context, blockX, blockZ, cell, raw, null, true);
	}

	static Cell threadRawCell() {
		return RAW_CELL.get();
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
			if (this.lookupScope != null) {
				this.lookupScope.close();
			}
		}
	}
}
