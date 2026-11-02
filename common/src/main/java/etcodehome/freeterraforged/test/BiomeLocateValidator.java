package etcodehome.freeterraforged.test;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.mojang.datafixers.util.Pair;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomePlacementPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;
import etcodehome.freeterraforged.world.worldgen.search.BiomeLocateSearch;
import etcodehome.freeterraforged.world.worldgen.search.BiomeSearchResolution;
import etcodehome.freeterraforged.world.worldgen.search.FTFBiomeLocateQuery;
import etcodehome.freeterraforged.world.worldgen.search.LocateSearchValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.RandomState;

/** Live-plan smoke benchmark for the chunk-free biome locator. */
public final class BiomeLocateValidator {
	private static final int CAPTURE_EXTENT = 4096;
	private static final int CAPTURE_STEP = 128;

	private BiomeLocateValidator() {
	}

	public static Result validate(MinecraftServer server) throws Exception {
		ServerLevel level = server.overworld();
		RandomState randomState = level.getChunkSource().randomState();
		if (!((Object) randomState instanceof FTFRandomState state)) {
			throw new AssertionError("Biome locate validation requires an FTF overworld");
		}
		WorldgenRuntimeBinding binding = state.binding();
		if (binding == null || state.generatorContext() == null) {
			throw new AssertionError("Biome locate validation has no active FTF runtime binding");
		}
		WorldgenRuntimeBinding.BiomeRuntime runtime = binding.biomeRuntime();
		WorldgenPlan expected = runtime.plan();
		java.util.function.BooleanSupplier valid = () -> {
			try {
				return binding.plan() == expected;
			} catch (IllegalStateException closed) {
				return false;
			}
		};

		BlockPos origin = level.getSharedSpawnPos();
		String authoritativeSampling = LocateSearchValidator.validateAuthoritativeSampling(
			state.generatorContext(), origin.getX(), origin.getZ()
		);
		FTFCommon.LOGGER.info("[FTF-Test] Locate filtered-sampling validation: {}", authoritativeSampling);
		FTFBiomeLocateQuery query = new FTFBiomeLocateQuery(
			runtime.selection(), state.generatorContext(), randomState.sampler(),
			runtime.placement(), valid
		);
		Holder<Biome> cherryGrove = level.registryAccess().lookupOrThrow(Registries.BIOME)
			.getOrThrow(Biomes.CHERRY_GROVE);
		BiomeLocateSearch.SearchPlan cherryPlan = query.plan(cherryGrove::equals);
		if (!cherryPlan.roles().searchesSurface() || cherryPlan.roles().searchesUnderground()) {
			throw new AssertionError("Cherry grove filler registrations leaked into underground locate roles: "
				+ cherryPlan.roles().roles());
		}
		Map<Holder<Biome>, Capture> captures = new IdentityHashMap<>();
		for (int dx = -CAPTURE_EXTENT; dx <= CAPTURE_EXTENT; dx += CAPTURE_STEP) {
			for (int dz = -CAPTURE_EXTENT; dz <= CAPTURE_EXTENT; dz += CAPTURE_STEP) {
				if ((long) dx * dx + (long) dz * dz > (long) CAPTURE_EXTENT * CAPTURE_EXTENT) {
					continue;
				}
				int x = origin.getX() + dx;
				int z = origin.getZ() + dz;
				Holder<Biome> biome = query.sampleSurface(x, z);
				if (biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) {
					continue;
				}
				long distanceSq = (long) dx * dx + (long) dz * dz;
				Capture current = captures.get(biome);
				if (current == null || distanceSq < current.nearestDistanceSq()) {
					captures.put(biome, new Capture(new BlockPos(x, origin.getY(), z), distanceSq));
				}
			}
		}
		Map.Entry<Holder<Biome>, Capture> selected = captures.entrySet().stream()
			.filter(entry -> entry.getValue().nearestDistanceSq() > 0L)
			.filter(entry -> {
				BiomePlacementPlan.TargetRoles roles = query.plan(entry.getKey()::equals).roles();
				return roles.searchesSurface() && !roles.searchesUnderground();
			})
			.max(Map.Entry.comparingByValue((first, second) -> Long.compare(
				first.nearestDistanceSq(), second.nearestDistanceSq()
			)))
			.orElseThrow(() -> new AssertionError("Captured region exposed no distant surface biome"));
		Holder<Biome> target = selected.getKey();

		long vanillaStart = System.nanoTime();
		Pair<BlockPos, Holder<Biome>> vanilla = level.findClosestBiome3d(
			target::equals, origin, 6400, 32, 64
		);
		long vanillaNanos = System.nanoTime() - vanillaStart;
		if (vanilla == null || !target.equals(vanilla.getSecond())) {
			throw new AssertionError("Vanilla locate did not recover captured biome " + biomeName(target));
		}

		long ftfStart = System.nanoTime();
		BiomeLocateSearch.Result ftf = BiomeLocateSearch.findFirst(query, target::equals, origin)
			.get(65L, TimeUnit.SECONDS);
		long ftfNanos = System.nanoTime() - ftfStart;
		if (ftf.match() == null || !target.equals(ftf.match().biome())) {
			throw new AssertionError("FTF locate did not recover captured biome " + biomeName(target));
		}
		if (ftf.plan().roles().searchesUnderground()) {
			throw new AssertionError("Surface biome filler registrations leaked into underground locate roles: "
				+ ftf.plan().roles().roles());
		}
		assertFinishedChunkBiome(level, target, ftf.match());

		Holder<Biome> caveTarget = level.registryAccess().lookupOrThrow(Registries.BIOME)
			.getOrThrow(Biomes.LUSH_CAVES);
		BiomeLocateSearch.SearchPlan cavePlan = query.plan(caveTarget::equals);
		if (cavePlan.roles().searchesSurface() || !cavePlan.roles().searchesShallow()
			|| !cavePlan.roles().searchesDeep()) {
			throw new AssertionError("Lush caves did not classify as shallow-plus-deep cave placement: "
				+ cavePlan.roles().roles());
		}
		long vanillaCaveStart = System.nanoTime();
		Pair<BlockPos, Holder<Biome>> vanillaCave = level.findClosestBiome3d(
			caveTarget::equals, origin, 6400, 32, 64
		);
		long vanillaCaveNanos = System.nanoTime() - vanillaCaveStart;
		long ftfCaveStart = System.nanoTime();
		BiomeLocateSearch.Result ftfCave = BiomeLocateSearch.findFirst(query, caveTarget::equals, origin)
			.get(65L, TimeUnit.SECONDS);
		long ftfCaveNanos = System.nanoTime() - ftfCaveStart;
		if (ftfCave.match() == null || !caveTarget.equals(ftfCave.match().biome())) {
			throw new AssertionError("FTF locate did not recover minecraft:lush_caves");
		}
		assertFinishedChunkBiome(level, caveTarget, ftfCave.match());
		int benchmarkTrials = Math.max(0, Integer.getInteger(
			"freeterraforged.test.locateBenchmarkTrials", 0
		));
		if (benchmarkTrials > 0) {
			Comparison surfaceBenchmark = compare(
				level, query, target, origin, benchmarkTrials
			);
			Comparison caveBenchmark = compare(
				level, query, caveTarget, origin, benchmarkTrials
			);
			Comparison unknownBenchmark = compareUnknownOnce(
				level, forceUnknownPlacement(query, runtime.placement()), target, origin
			);
			FTFCommon.LOGGER.info(
				"[FTF-Benchmark] trials={} surface[{}] {} | underground[{}] {} | unknown[{} forced] {}",
				benchmarkTrials,
				biomeName(target),
				surfaceBenchmark.describe(),
				biomeName(caveTarget),
				caveBenchmark.describe(),
				biomeName(target),
				unknownBenchmark.describe()
			);
		}
		String rawOptimization = LocateSearchValidator.validateRawBiomeOptimization(
			state.generatorContext(), runtime.selection(), randomState.sampler(), origin.getX(), origin.getZ()
		);
		FTFCommon.LOGGER.info("[FTF-Test] Locate raw-equivalence validation: {}", rawOptimization);
		return new Result(
			biomeName(target),
			captures.size(),
			selected.getValue().position(),
			vanilla.getFirst(),
			ftf.match().position(),
			TimeUnit.NANOSECONDS.toMillis(vanillaNanos),
			TimeUnit.NANOSECONDS.toMillis(ftfNanos),
			ftf.columns(),
			ftf.plan().resolution().horizontalStep(),
			ftf.plan().resolution().verticalStep(),
			ftf.plan().roles().roles(),
			vanillaCave == null ? null : vanillaCave.getFirst(),
			ftfCave.match().position(),
			TimeUnit.NANOSECONDS.toMillis(vanillaCaveNanos),
			TimeUnit.NANOSECONDS.toMillis(ftfCaveNanos),
			ftfCave.columns(),
			ftfCave.plan().resolution().horizontalStep(),
			ftfCave.plan().resolution().verticalStep(),
			ftfCave.plan().roles().roles()
		);
	}

	private static Comparison compare(
		ServerLevel level,
		BiomeLocateSearch.Query query,
		Holder<Biome> target,
		BlockPos origin,
		int trials
	) throws Exception {
		// Untimed warmup gives both paths the same opportunity to initialize query-local caches.
		vanillaLocate(level, target, origin);
		ftfLocate(query, target, origin);
		long[] vanillaNanos = new long[trials];
		long[] ftfNanos = new long[trials];
		long[] columns = new long[trials];
		Pair<BlockPos, Holder<Biome>> vanilla = null;
		BiomeLocateSearch.Result ftf = null;
		for (int trial = 0; trial < trials; trial++) {
			if ((trial & 1) == 0) {
				TimedVanilla timedVanilla = timeVanilla(level, target, origin);
				vanilla = timedVanilla.result();
				vanillaNanos[trial] = timedVanilla.nanos();
				TimedFtf timedFtf = timeFtf(query, target, origin);
				ftf = timedFtf.result();
				ftfNanos[trial] = timedFtf.nanos();
			} else {
				TimedFtf timedFtf = timeFtf(query, target, origin);
				ftf = timedFtf.result();
				ftfNanos[trial] = timedFtf.nanos();
				TimedVanilla timedVanilla = timeVanilla(level, target, origin);
				vanilla = timedVanilla.result();
				vanillaNanos[trial] = timedVanilla.nanos();
			}
			columns[trial] = ftf.columns();
		}
		return new Comparison(
			Timing.of(vanillaNanos),
			Timing.of(ftfNanos),
			vanilla == null ? null : vanilla.getFirst(),
			ftf == null || ftf.match() == null ? null : ftf.match().position(),
			median(columns),
			ftf == null ? null : ftf.plan()
		);
	}

	private static Comparison compareUnknownOnce(
		ServerLevel level,
		BiomeLocateSearch.Query query,
		Holder<Biome> target,
		BlockPos origin
	) throws Exception {
		TimedVanilla vanilla = timeVanilla(level, target, origin);
		long start = System.nanoTime();
		BiomeLocateSearch.Result ftf = BiomeLocateSearch.findFirst(query, target::equals, origin)
			.get(65L, TimeUnit.SECONDS);
		long ftfNanos = System.nanoTime() - start;
		return new Comparison(
			Timing.of(new long[] { vanilla.nanos() }),
			Timing.of(new long[] { ftfNanos }),
			vanilla.result().getFirst(),
			ftf.match() == null ? null : ftf.match().position(),
			ftf.columns(),
			ftf.plan(),
			ftf.timedOut()
		);
	}

	private static Pair<BlockPos, Holder<Biome>> vanillaLocate(
		ServerLevel level,
		Holder<Biome> target,
		BlockPos origin
	) {
		Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(
			target::equals, origin, 6400, 32, 64
		);
		if (result == null || !target.equals(result.getSecond())) {
			throw new AssertionError("Vanilla locate did not recover " + biomeName(target));
		}
		return result;
	}

	private static BiomeLocateSearch.Result ftfLocate(
		BiomeLocateSearch.Query query,
		Holder<Biome> target,
		BlockPos origin
	) throws Exception {
		BiomeLocateSearch.Result result = BiomeLocateSearch.findFirst(query, target::equals, origin)
			.get(65L, TimeUnit.SECONDS);
		if (result.match() == null || !target.equals(result.match().biome())) {
			throw new AssertionError("FTF locate did not recover " + biomeName(target));
		}
		return result;
	}

	private static TimedVanilla timeVanilla(
		ServerLevel level,
		Holder<Biome> target,
		BlockPos origin
	) {
		long start = System.nanoTime();
		Pair<BlockPos, Holder<Biome>> result = vanillaLocate(level, target, origin);
		return new TimedVanilla(result, System.nanoTime() - start);
	}

	private static TimedFtf timeFtf(
		BiomeLocateSearch.Query query,
		Holder<Biome> target,
		BlockPos origin
	) throws Exception {
		long start = System.nanoTime();
		BiomeLocateSearch.Result result = ftfLocate(query, target, origin);
		return new TimedFtf(result, System.nanoTime() - start);
	}

	private static BiomeLocateSearch.Query forceUnknownPlacement(
		FTFBiomeLocateQuery delegate,
		BiomePlacementPlan placements
	) {
		return new BiomeLocateSearch.Query() {
			@Override
			public BiomeLocateSearch.SearchPlan plan(Predicate<Holder<Biome>> target) {
				BiomeLocateSearch.SearchPlan actual = delegate.plan(target);
				BiomePlacementPlan.TargetRoles unknown = new BiomePlacementPlan.TargetRoles(
					actual.roles().possible(),
					Set.of(BiomePlacementPlan.Role.UNKNOWN),
					actual.roles().bandingEnabled(),
					false
				);
				return new BiomeLocateSearch.SearchPlan(
					unknown,
					BiomeSearchResolution.derive(placements.searchScales(), unknown)
				);
			}

			@Override
			public boolean supportsParallelQueries() {
				return delegate.supportsParallelQueries();
			}

			@Override
			public etcodehome.freeterraforged.world.worldgen.search.ParallelFirstSearch.TileEvaluator<BiomeLocateSearch.Match> createTileSampler(
				Predicate<Holder<Biome>> target,
				BiomeLocateSearch.SearchPlan plan,
				BlockPos origin
			) {
				return delegate.createTileSampler(target, plan, origin);
			}
		};
	}

	private static long median(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[sorted.length / 2];
	}

	private static String biomeName(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> key.location().toString()).orElse("<unregistered>");
	}

	private static void assertFinishedChunkBiome(
		ServerLevel level,
		Holder<Biome> expected,
		BiomeLocateSearch.Match match
	) {
		BlockPos position = match.position();
		// Force FULL promotion, then read the exact stored quart. Level#getBiome uses BiomeManager's zoom/fiddle
		// algorithm and may intentionally select an adjacent quart, which is not the coordinate locate evaluated.
		var chunk = level.getChunk(position.getX() >> 4, position.getZ() >> 4);
		Holder<Biome> generated = chunk.getNoiseBiome(
			QuartPos.fromBlock(position.getX()),
			QuartPos.fromBlock(position.getY()),
			QuartPos.fromBlock(position.getZ())
		);
		if (!expected.equals(generated)) {
			throw new AssertionError(
				"FTF locate predicted " + biomeName(expected) + " at " + position
					+ " but the finished chunk contains " + biomeName(generated)
			);
		}
	}

	private record Capture(BlockPos position, long nearestDistanceSq) {
	}

	private record TimedVanilla(Pair<BlockPos, Holder<Biome>> result, long nanos) {
	}

	private record TimedFtf(BiomeLocateSearch.Result result, long nanos) {
	}

	private record Timing(double medianMillis, double minMillis, double maxMillis) {
		private static Timing of(long[] nanos) {
			long[] sorted = nanos.clone();
			Arrays.sort(sorted);
			return new Timing(
				sorted[sorted.length / 2] / 1_000_000.0D,
				sorted[0] / 1_000_000.0D,
				sorted[sorted.length - 1] / 1_000_000.0D
			);
		}

		private String describe() {
			return String.format(
				Locale.ROOT, "median %.2f ms [%.2f..%.2f]", this.medianMillis, this.minMillis, this.maxMillis
			);
		}
	}

	private record Comparison(
		Timing vanilla,
		Timing ftf,
		BlockPos vanillaPosition,
		BlockPos ftfPosition,
		long ftfColumns,
		BiomeLocateSearch.SearchPlan plan,
		boolean timedOut
	) {
		private Comparison(
			Timing vanilla,
			Timing ftf,
			BlockPos vanillaPosition,
			BlockPos ftfPosition,
			long ftfColumns,
			BiomeLocateSearch.SearchPlan plan
		) {
			this(vanilla, ftf, vanillaPosition, ftfPosition, ftfColumns, plan, false);
		}

		private String describe() {
			return "vanilla " + this.vanilla.describe() + " @ " + this.vanillaPosition
				+ ", FTF " + this.ftf.describe() + " @ " + this.ftfPosition
				+ (this.timedOut ? " (timed out)" : "")
				+ ", columns " + this.ftfColumns
				+ ", step " + this.plan.resolution().horizontalStep() + "x"
				+ this.plan.resolution().verticalStep()
				+ ", roles " + this.plan.roles().roles();
		}
	}

	public record Result(
		String biome,
		int capturedBiomes,
		BlockPos capturedPosition,
		BlockPos vanillaPosition,
		BlockPos ftfPosition,
		long vanillaMillis,
		long ftfMillis,
		long ftfColumns,
		int horizontalStep,
		int verticalStep,
		Set<BiomePlacementPlan.Role> roles,
		BlockPos vanillaCavePosition,
		BlockPos ftfCavePosition,
		long vanillaCaveMillis,
		long ftfCaveMillis,
		long ftfCaveColumns,
		int caveHorizontalStep,
		int caveVerticalStep,
		Set<BiomePlacementPlan.Role> caveRoles
	) {
	}
}
