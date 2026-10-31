package etcodehome.freeterraforged.world.worldgen.search;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.FTFClimateSampler;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomePlacementPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenBiomeSelection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/** Final-biome column query backed by an immutable FTF runtime plan. */
public final class FTFBiomeLocateQuery implements BiomeLocateSearch.Query {
	private final WorldgenBiomeSelection.Executable selection;
	private final GeneratorContext context;
	private final Climate.Sampler climateSampler;
	private final BiomePlacementPlan placements;
	private final BiomeLocateCellOptimization cellOptimization;
	private final boolean directSource;
	private final BooleanSupplier valid;

	public FTFBiomeLocateQuery(
		WorldgenBiomeSelection.Executable selection,
		GeneratorContext context,
		Climate.Sampler climateSampler,
		BiomePlacementPlan placements,
		BooleanSupplier valid
	) {
		this.selection = Objects.requireNonNull(selection, "selection");
		this.context = Objects.requireNonNull(context, "context");
		Objects.requireNonNull(climateSampler, "climateSampler");
		this.placements = Objects.requireNonNull(placements, "placements");
		if (placements.plan() != selection.plan()) {
			throw new IllegalArgumentException("Biome placement metadata belongs to a different plan");
		}
		this.climateSampler = isolateSampler(climateSampler, selection);
		this.cellOptimization = BiomeLocateCellOptimization.compile(selection, context, this.climateSampler);
		this.directSource = selection.plan().providerSelection().directInput().isPresent();
		this.valid = Objects.requireNonNull(valid, "valid");
	}

	@Override
	public BiomeLocateSearch.SearchPlan plan(Predicate<Holder<Biome>> target) {
		BiomePlacementPlan.TargetRoles roles = this.placements.classify(target);
		return new BiomeLocateSearch.SearchPlan(
			roles,
			BiomeSearchResolution.derive(this.placements.searchScales(), roles)
		);
	}

	@Override
	public boolean supportsParallelQueries() {
		return this.selection.supportsIsolatedParallelRead();
	}

	@Override
	public ParallelFirstSearch.TileEvaluator<BiomeLocateSearch.Match> createTileSampler(
		Predicate<Holder<Biome>> target,
		BiomeLocateSearch.SearchPlan plan,
		BlockPos origin
	) {
		int latticeOriginX = QuartPos.toBlock(QuartPos.fromBlock(origin.getX()));
		int latticeOriginZ = QuartPos.toBlock(QuartPos.fromBlock(origin.getZ()));
		int horizontalStep = plan.resolution().horizontalStep();
		return (tile, control) -> {
			this.requireValid();
			return AuthoritativeTileSearch.findFirst(
				this.context,
				tile,
				latticeOriginX,
				latticeOriginZ,
				horizontalStep,
				true,
				this.cellOptimization::canUseRaw,
				control,
				(cell, blockX, blockZ) -> this.findInColumn(
					target,
					plan,
					origin,
					cell,
					blockX,
					blockZ
				)
			);
		};
	}

	/** Samples the authoritative final surface biome at one coordinate using the same path as locate. */
	public Holder<Biome> sampleSurface(int blockX, int blockZ) {
		this.requireValid();
		try (AuthoritativeSample sample = AuthoritativeSample.open(
			this.context,
			blockX,
			blockZ,
			true,
			this.cellOptimization::canUseRaw
		)) {
			Cell cell = sample.cell();
			return this.resolveAt(cell, blockX, this.surfaceY(cell), blockZ);
		}
	}

	@Nullable
	private BiomeLocateSearch.Match findInColumn(
		Predicate<Holder<Biome>> target,
		BiomeLocateSearch.SearchPlan plan,
		BlockPos origin,
		Cell cell,
		int blockX,
		int blockZ
	) {
		this.requireValid();
		int surfaceY = this.surfaceY(cell);
		int originY = origin.getY();
		BiomePlacementPlan.TargetRoles roles = plan.roles();
		boolean surfaceFirst = roles.searchesSurface() && originY >= surfaceY;
		if (surfaceFirst) {
			BiomeLocateSearch.Match surface = this.matchAt(target, origin, cell, blockX, surfaceY, blockZ);
			if (surface != null) {
				return surface;
			}
		}

		if (roles.searchesUnderground()) {
			int minY = alignUpToQuart(-this.context.levels.worldDepth);
			int maxY = alignDownToQuart(Math.min(surfaceY - 1, this.context.levels.worldHeight - 1));
			if (minY <= maxY) {
				int startY = alignDownToQuart(Mth.clamp(originY, minY, maxY));
				startY = Mth.clamp(startY, minY, maxY);
				int step = plan.resolution().verticalStep();
				for (int offset = 0; ; offset += step) {
					boolean lowerValid = startY - offset >= minY;
					boolean upperValid = offset != 0 && startY + offset <= maxY;
					if (!lowerValid && !upperValid) {
						break;
					}
					if (lowerValid) {
						BiomeLocateSearch.Match match = this.matchUndergroundAt(
							target, roles, origin, cell, blockX, startY - offset, blockZ
						);
						if (match != null) {
							return match;
						}
					}
					if (upperValid) {
						BiomeLocateSearch.Match match = this.matchUndergroundAt(
							target, roles, origin, cell, blockX, startY + offset, blockZ
						);
						if (match != null) {
							return match;
						}
					}
				}
			}
		}

		if (roles.searchesSurface() && !surfaceFirst) {
			return this.matchAt(target, origin, cell, blockX, surfaceY, blockZ);
		}
		return null;
	}

	@Nullable
	private BiomeLocateSearch.Match matchUndergroundAt(
		Predicate<Holder<Biome>> target,
		BiomePlacementPlan.TargetRoles roles,
		BlockPos origin,
		Cell cell,
		int blockX,
		int blockY,
		int blockZ
	) {
		if (this.directSource) {
			return this.matchAt(target, origin, cell, blockX, blockY, blockZ);
		}
		int quartX = QuartPos.fromBlock(blockX);
		int quartY = QuartPos.fromBlock(blockY);
		int quartZ = QuartPos.fromBlock(blockZ);
		Climate.TargetPoint climate = this.selection.plan().samplerDecoration().sample(
			this.climateSampler, quartX, quartY, quartZ
		);
		if (!roles.unknown()) {
			UndergroundBiomeBanding.CandidateRole stage = UndergroundBiomeBanding.classifyTarget(climate);
			if (stage == UndergroundBiomeBanding.CandidateRole.SHALLOW_CAVE && !roles.searchesShallow()
				|| stage == UndergroundBiomeBanding.CandidateRole.DEEP_CAVE && !roles.searchesDeep()
				|| stage == UndergroundBiomeBanding.CandidateRole.SURFACE) {
				return null;
			}
		}
		Holder<Biome> biome = this.selection.resolveSampledInCell(
			quartX,
			quartY,
			quartZ,
			this.climateSampler,
			climate,
			cell.biomeRegionX,
			cell.biomeRegionZ
		);
		this.requireValid();
		return target.test(biome)
			? new BiomeLocateSearch.Match(
				new BlockPos(blockX, blockY, blockZ), biome, distanceSq(origin, blockX, blockZ)
			)
			: null;
	}

	@Nullable
	private BiomeLocateSearch.Match matchAt(
		Predicate<Holder<Biome>> target,
		BlockPos origin,
		Cell cell,
		int blockX,
		int blockY,
		int blockZ
	) {
		Holder<Biome> biome = this.resolveAt(cell, blockX, blockY, blockZ);
		return target.test(biome)
			? new BiomeLocateSearch.Match(
				new BlockPos(blockX, blockY, blockZ), biome, distanceSq(origin, blockX, blockZ)
			)
			: null;
	}

	private static long distanceSq(BlockPos origin, int blockX, int blockZ) {
		long dx = (long) blockX - origin.getX();
		long dz = (long) blockZ - origin.getZ();
		return dx * dx + dz * dz;
	}

	private Holder<Biome> resolveAt(Cell cell, int blockX, int blockY, int blockZ) {
		Holder<Biome> biome = this.selection.resolveInCell(
			QuartPos.fromBlock(blockX),
			QuartPos.fromBlock(blockY),
			QuartPos.fromBlock(blockZ),
			this.climateSampler,
			cell.biomeRegionX,
			cell.biomeRegionZ
		);
		this.requireValid();
		return biome;
	}

	private int surfaceY(Cell cell) {
		int minY = -this.context.levels.worldDepth;
		int maxY = Math.max(minY, this.context.levels.worldHeight - 1);
		return Math.max(minY, Math.min(maxY, this.context.levels.scale(cell.height)));
	}

	private void requireValid() {
		if (!this.valid.getAsBoolean()) {
			throw new IllegalStateException("FTF biome plan changed while a locate search was running");
		}
	}

	private static int alignDownToQuart(int blockY) {
		return QuartPos.toBlock(QuartPos.fromBlock(blockY));
	}

	private static int alignUpToQuart(int blockY) {
		int aligned = alignDownToQuart(blockY);
		return aligned == blockY ? aligned : Math.addExact(aligned, QuartPos.SIZE);
	}

	private static Climate.Sampler isolateSampler(
		Climate.Sampler source,
		WorldgenBiomeSelection.Executable selection
	) {
		Climate.Sampler isolated = new Climate.Sampler(
			source.temperature(),
			source.humidity(),
			source.continentalness(),
			source.erosion(),
			source.depth(),
			source.weirdness(),
			source.spawnTarget()
		);
		// Runtime and preview samplers are already decorated for this plan. Reapplying contributed
		// stages here could compound their transformations, so isolate the functions and copy only
		// the FTF ownership/query metadata needed to evaluate those published functions.
		FTFClimateSampler sourceState = (FTFClimateSampler) (Object) source;
		FTFClimateSampler isolatedState = (FTFClimateSampler) (Object) isolated;
		var semantics = sourceState.climateQuerySemantics();
		isolatedState.setClimateQuerySemantics(
			semantics.policy(),
			semantics.preset(),
			semantics.seed(),
			semantics.surfaceContext()
		);
		isolatedState.setSpawnSearch(sourceState.getSpawnSearch());
		isolatedState.setWorldgenPlan(selection.plan());
		return isolated;
	}
}
