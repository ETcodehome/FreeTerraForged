package etcodehome.freeterraforged.world.worldgen.search;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.runtime.BiomePlacementPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/** Outward, first-match biome search across the placement domains selected by a compiled FTF plan. */
public final class BiomeLocateSearch {
	private BiomeLocateSearch() {
	}

	public static CompletableFuture<Result> findFirst(
		Query query,
		Predicate<Holder<Biome>> target,
		BlockPos origin
	) {
		return findFirst(query, target, origin, LocateSearchSettings.defaults());
	}

	public static CompletableFuture<Result> findFirst(
		Query query,
		Predicate<Holder<Biome>> target,
		BlockPos origin,
		LocateSearchSettings settings
	) {
		Objects.requireNonNull(query, "query");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(settings, "settings");
		SearchPlan plan = query.plan(target);
		if (!plan.roles().possible()) {
			return CompletableFuture.completedFuture(new Result(
				null, 0L, false, false, plan
			));
		}

		boolean parallel = query.supportsParallelQueries();
		int parallelism = parallel ? settings.workerThreads() : 1;
		Executor executor = parallel ? LocateSearchExecutors.parallel() : LocateSearchExecutors.serial();
		CompletableFuture<ParallelFirstSearch.Result<Match>> search = ParallelFirstSearch.findFirst(
			origin.getX(),
			origin.getZ(),
			() -> query.createTileSampler(target, plan, origin),
			executor,
			parallelism,
			settings.timeoutSeconds()
		);
		CompletableFuture<Result> transformed = search.thenApply(result -> new Result(
			result.match(),
			result.samples(),
			result.timedOut(),
			true,
			plan
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

	public interface Query {
		SearchPlan plan(Predicate<Holder<Biome>> target);

		boolean supportsParallelQueries();

		ParallelFirstSearch.TileEvaluator<Match> createTileSampler(
			Predicate<Holder<Biome>> target,
			SearchPlan plan,
			BlockPos origin
		);
	}

	public record SearchPlan(
		BiomePlacementPlan.TargetRoles roles,
		BiomeSearchResolution resolution
	) {
		public SearchPlan {
			roles = Objects.requireNonNull(roles, "roles");
			resolution = Objects.requireNonNull(resolution, "resolution");
		}
	}

	public record Match(BlockPos position, Holder<Biome> biome) {
		public Match {
			position = Objects.requireNonNull(position, "position");
			biome = Objects.requireNonNull(biome, "biome");
		}
	}

	public record Result(
		@Nullable Match match,
		long columns,
		boolean timedOut,
		boolean targetPossible,
		SearchPlan plan
	) {
	}
}
