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
		long samples = tile.sampleCount(latticeOriginX, latticeOriginZ, step);
		if (samples <= MAX_POINT_SAMPLES) {
			return tile.findFirst(
				latticeOriginX,
				latticeOriginZ,
				step,
				control,
				(blockX, blockZ) -> {
					try (AuthoritativeSample sample = AuthoritativeSample.open(
						context, blockX, blockZ, sampleClimate, rawEquivalent
					)) {
						return evaluator.test(sample.cell(), blockX, blockZ);
					}
				}
			);
		}
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

	@FunctionalInterface
	interface CellEvaluator<M> {
		@Nullable M test(Cell cell, int blockX, int blockZ);
	}
}
