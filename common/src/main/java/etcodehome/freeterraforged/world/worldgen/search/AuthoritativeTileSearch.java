package etcodehome.freeterraforged.world.worldgen.search;

import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.RegionFilterGrid;

/**
 * Evaluates a locate lattice against the same filtered terrain state used by chunk generation.
 *
 * <p>A sparse lattice is cheaper to filter as independent cells plus their required dependency halos. A dense lattice
 * is cheaper to filter once as a complete 64x64 region. The crossover is based on the actual points in each tile and
 * the number of raw cells each strategy evaluates, rather than on a duplicated, target-specific step threshold.
 */
final class AuthoritativeTileSearch {
	private static final int MAX_POINT_SAMPLES = Math.max(
		1,
		RegionFilterGrid.TOTAL_CELLS / PointFilterGrid.TOTAL_CELLS
	);

	private AuthoritativeTileSearch() {
	}

	static <M> ParallelFirstSearch.TileResult<M> findFirst(
		GeneratorContext context,
		ParallelFirstSearch.Tile tile,
		int latticeOriginX,
		int latticeOriginZ,
		int step,
		boolean sampleClimate,
		@Nullable Predicate<Cell> rawEquivalent,
		ParallelFirstSearch.Control control,
		CellEvaluator<M> evaluator
	) {
		return findFirst(
			context,
			tile,
			latticeOriginX,
			latticeOriginZ,
			step,
			sampleClimate,
			rawEquivalent,
			null,
			control,
			evaluator
		);
	}

	static <M> ParallelFirstSearch.TileResult<M> findFirst(
		GeneratorContext context,
		ParallelFirstSearch.Tile tile,
		int latticeOriginX,
		int latticeOriginZ,
		int step,
		boolean sampleClimate,
		@Nullable Predicate<Cell> rawEquivalent,
		@Nullable BiomeLocateCellOptimization.TargetFilter targetFilter,
		ParallelFirstSearch.Control control,
		CellEvaluator<M> evaluator
	) {
		long samples = tile.sampleCount(latticeOriginX, latticeOriginZ, step);
		if (samples == 0L) {
			return new ParallelFirstSearch.TileResult<>(null, 0L);
		}
		if (targetFilter != null && targetFilter.canSkipTile(tile)) {
			return new ParallelFirstSearch.TileResult<>(null, samples);
		}
		if (samples <= MAX_POINT_SAMPLES) {
			return tile.findFirst(
				latticeOriginX,
				latticeOriginZ,
				step,
				control,
				(blockX, blockZ) -> {
					try (AuthoritativeSample sample = AuthoritativeSample.openIfCandidate(
						context, blockX, blockZ, sampleClimate, rawEquivalent, targetFilter
					)) {
						return sample != null ? evaluator.test(sample.cell(), blockX, blockZ) : null;
					}
				}
			);
		}
		if (rawEquivalent == null && targetFilter == null) {
			try (AuthoritativeRegion region = AuthoritativeRegion.open(context, tile)) {
				return tile.findFirst(
					latticeOriginX,
					latticeOriginZ,
					step,
					control,
					(blockX, blockZ) -> evaluator.test(region.cell(blockX, blockZ), blockX, blockZ)
				);
			}
		}

		AuthoritativeRegion[] lazyRegion = new AuthoritativeRegion[1];
		try {
			return tile.findFirst(
				latticeOriginX,
				latticeOriginZ,
				step,
				control,
				(blockX, blockZ) -> {
					if (lazyRegion[0] != null) {
						return evaluator.test(lazyRegion[0].cell(blockX, blockZ), blockX, blockZ);
					}
					Cell generated = context.filteredChunkCache.getExistingFilteredCell(blockX, blockZ);
					if (generated != null) {
						try (AuthoritativeSample sample = AuthoritativeSample.wrapResolved(
							context, blockX, blockZ, generated, false
						)) {
							return evaluator.test(sample.cell(), blockX, blockZ);
						}
					}
					Cell rawCell = AuthoritativeSample.threadRawCell();
					if (targetFilter != null && !targetFilter.requiresClimateForRawPrecheck()) {
						context.heightmap.apply(rawCell.reset(), blockX, blockZ, false);
						if (!targetFilter.canMatchRaw(rawCell)) {
							return null;
						}
						if (sampleClimate) {
							context.heightmap.applyClimate(rawCell, blockX, blockZ, true);
						}
					} else {
						context.heightmap.apply(rawCell.reset(), blockX, blockZ, sampleClimate);
						if (targetFilter != null && !targetFilter.canMatchRaw(rawCell)) {
							return null;
						}
					}
					if (rawEquivalent != null && rawEquivalent.test(rawCell)) {
						try (AuthoritativeSample sample = AuthoritativeSample.wrapResolved(
							context, blockX, blockZ, rawCell, true
						)) {
							return evaluator.test(sample.cell(), blockX, blockZ);
						}
					}
					lazyRegion[0] = AuthoritativeRegion.open(context, tile);
					return evaluator.test(lazyRegion[0].cell(blockX, blockZ), blockX, blockZ);
				}
			);
		} finally {
			if (lazyRegion[0] != null) {
				lazyRegion[0].close();
			}
		}
	}

	@FunctionalInterface
	interface CellEvaluator<M> {
		@Nullable M test(Cell cell, int blockX, int blockZ);
	}
}
