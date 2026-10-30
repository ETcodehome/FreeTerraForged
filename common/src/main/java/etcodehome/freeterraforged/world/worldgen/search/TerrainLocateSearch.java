package etcodehome.freeterraforged.world.worldgen.search;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import net.minecraft.core.BlockPos;

/** Preset-scaled, outward first-match search for final FTF terrain classifications. */
public final class TerrainLocateSearch {
	private TerrainLocateSearch() {
	}

	public static CompletableFuture<Result> findFirst(
		GeneratorContext context,
		Terrain target,
		BlockPos origin
	) {
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(origin, "origin");
		TerrainSearchResolution resolution = TerrainSearchResolution.derive(context.preset, target);
		CompletableFuture<ParallelFirstSearch.Result<Match>> search = ParallelFirstSearch.findFirst(
			origin.getX(),
			origin.getZ(),
			() -> evaluator(context, target, origin, resolution),
			LocateSearchExecutors.parallel(),
			LocateSearchExecutors.parallelism()
		);
		CompletableFuture<Result> transformed = search.thenApply(result -> new Result(
			result.match(),
			result.samples(),
			result.timedOut(),
			resolution
		));
		CompletableFuture<Result> cancellable = new CompletableFuture<>() {
			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				search.cancel(mayInterruptIfRunning);
				transformed.cancel(mayInterruptIfRunning);
				return super.cancel(mayInterruptIfRunning);
			}
		};
		transformed.whenComplete((result, failure) -> {
			if (failure == null) {
				cancellable.complete(result);
			} else {
				cancellable.completeExceptionally(failure);
			}
		});
		return cancellable;
	}

	private static ParallelFirstSearch.TileEvaluator<Match> evaluator(
		GeneratorContext context,
		Terrain target,
		BlockPos origin,
		TerrainSearchResolution resolution
	) {
		if (context.filters != null && context.filters.affectsTerrainIdentity(target)) {
			return (tile, control) -> AuthoritativeTileSearch.findFirst(
				context,
				tile,
				origin.getX(),
				origin.getZ(),
				resolution.horizontalStep(),
				false,
				null,
				control,
				(cell, x, z) -> cell.terrain == target
					? new Match(x, z, distanceSq(origin, x, z))
					: null
			);
		}

		Cell scratch = new Cell();
		return (tile, control) -> tile.findFirst(
			origin.getX(), origin.getZ(), resolution.horizontalStep(), control,
			(x, z) -> {
				// Raw terrain identity is fully determined by the heightmap path; climate fields are only
				// needed by biome selection and would add avoidable work to every terrain sample.
				context.heightmap.apply(scratch.reset(), x, z, false);
				return scratch.terrain == target ? new Match(x, z, distanceSq(origin, x, z)) : null;
			}
		);
	}

	private static long distanceSq(BlockPos origin, int x, int z) {
		long dx = (long) x - origin.getX();
		long dz = (long) z - origin.getZ();
		return dx * dx + dz * dz;
	}

	public record Match(int x, int z, long distanceSq) {
	}

	public record Result(
		@Nullable Match match,
		long samples,
		boolean timedOut,
		TerrainSearchResolution resolution
	) {
	}
}
