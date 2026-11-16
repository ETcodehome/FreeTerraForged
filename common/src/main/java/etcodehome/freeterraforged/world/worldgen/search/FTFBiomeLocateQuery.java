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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/** Final-biome column query backed by an immutable FTF runtime plan. */
public final class FTFBiomeLocateQuery implements BiomeLocateSearch.Query {
	public static final int MIN_BIOME_AREA_BLOCKS = 8;
	private static final int MIN_BIOME_AREA_QUARTS = MIN_BIOME_AREA_BLOCKS / QuartPos.SIZE;
	private static final int MIN_COORDINATE = -Level.MAX_LEVEL_SIZE;
	private static final int MAX_COORDINATE = Level.MAX_LEVEL_SIZE - 1;
	private static final int[][] AREA_ANCHOR_OFFSETS = {
		{ 1, 1 },
		{ 0, 1 },
		{ 1, 0 },
		{ 0, 0 }
	};

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
		BiomeLocateCellOptimization.TargetFilter targetFilter = this.cellOptimization.forTarget(target, plan);
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
				targetFilter,
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
		int quartBlockX = QuartPos.toBlock(QuartPos.fromBlock(blockX));
		int quartBlockZ = QuartPos.toBlock(QuartPos.fromBlock(blockZ));
		try (AuthoritativeSample sample = AuthoritativeSample.open(
			this.context,
			quartBlockX,
			quartBlockZ,
			true,
			this.cellOptimization::canUseRaw
		)) {
			Cell cell = sample.cell();
			return this.resolveAt(cell, quartBlockX, this.surfaceY(cell), quartBlockZ);
		}
	}

	/** Samples the authoritative final biome at a 3D block coordinate using the same path as locate. */
	public Holder<Biome> sampleAt(int blockX, int blockY, int blockZ) {
		this.requireValid();
		int quartBlockX = QuartPos.toBlock(QuartPos.fromBlock(blockX));
		int quartBlockZ = QuartPos.toBlock(QuartPos.fromBlock(blockZ));
		try (AuthoritativeSample sample = AuthoritativeSample.open(
			this.context,
			quartBlockX,
			quartBlockZ,
			true,
			this.cellOptimization::canUseRaw
		)) {
			Cell cell = sample.cell();
			return this.resolveAt(cell, quartBlockX, blockY, quartBlockZ);
		}
	}

	/**
	 * Verifies that the 8x8 block area anchored at {@code match.position()} is composed of {@code match.biome()}.
	 */
	public boolean hasMinimumBiomeArea(BiomeLocateSearch.Match match, BiomeLocateSearch.SearchPlan plan) {
		Objects.requireNonNull(match, "match");
		Objects.requireNonNull(plan, "plan");
		this.requireValid();
		BlockPos pos = match.position();
		Holder<Biome> expected = match.biome();
		int anchorX = QuartPos.toBlock(QuartPos.fromBlock(pos.getX()));
		int anchorY = pos.getY();
		int anchorZ = QuartPos.toBlock(QuartPos.fromBlock(pos.getZ()));
		boolean checkSurface = plan.roles().searchesSurface() && !plan.roles().searchesUnderground();
		for (int dz = 0; dz < MIN_BIOME_AREA_BLOCKS; dz += QuartPos.SIZE) {
			for (int dx = 0; dx < MIN_BIOME_AREA_BLOCKS; dx += QuartPos.SIZE) {
				int x = anchorX + dx;
				int z = anchorZ + dz;
				if (!inWorldBounds(x, z)) {
					return false;
				}
				try (AuthoritativeSample sample = AuthoritativeSample.open(
					this.context,
					x,
					z,
					true,
					this.cellOptimization::canUseRaw
				)) {
					Cell cell = sample.cell();
					if (!expected.equals(this.resolveAt(cell, x, anchorY, z))) {
						return false;
					}
					if (checkSurface) {
						int surfaceY = this.surfaceY(cell);
						if (QuartPos.fromBlock(surfaceY) != QuartPos.fromBlock(anchorY)
							&& !expected.equals(this.resolveAt(cell, x, surfaceY, z))) {
							return false;
						}
					}
				}
			}
		}
		return true;
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
			BiomeLocateSearch.Match surface = this.matchSurfaceAt(target, origin, cell, blockX, surfaceY, blockZ);
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
			return this.matchSurfaceAt(target, origin, cell, blockX, surfaceY, blockZ);
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
		Holder<Biome> biome = this.resolveUndergroundCandidateAt(roles, cell, blockX, blockY, blockZ);
		if (biome == null || !target.test(biome)) {
			return null;
		}
		return this.findUndergroundAreaMatch(biome, roles, origin, blockX, blockY, blockZ);
	}

	@Nullable
	private Holder<Biome> resolveUndergroundCandidateAt(
		BiomePlacementPlan.TargetRoles roles,
		Cell cell,
		int blockX,
		int blockY,
		int blockZ
	) {
		if (this.directSource) {
			return this.resolveAt(cell, blockX, blockY, blockZ);
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
		return biome;
	}

	@Nullable
	private BiomeLocateSearch.Match matchSurfaceAt(
		Predicate<Holder<Biome>> target,
		BlockPos origin,
		Cell cell,
		int blockX,
		int surfaceY,
		int blockZ
	) {
		Holder<Biome> biome = this.resolveAt(cell, blockX, surfaceY, blockZ);
		if (!target.test(biome)) {
			return null;
		}
		return this.findSurfaceAreaMatch(biome, origin, blockX, surfaceY, blockZ);
	}

	@Nullable
	private BiomeLocateSearch.Match findSurfaceAreaMatch(
		Holder<Biome> biome,
		BlockPos origin,
		int blockX,
		int centerSurfaceY,
		int blockZ
	) {
		int evaluatedMask = 1 << 4; // Center (1, 1) is bit 4
		int matchedMask = 1 << 4;
		int sY00 = centerSurfaceY;
		int sY10 = centerSurfaceY;
		int sY01 = centerSurfaceY;

		for (int[] anchorOffset : AREA_ANCHOR_OFFSETS) {
			int ax = anchorOffset[0];
			int az = anchorOffset[1];
			boolean allMatch = true;
			for (int oz = 0; oz < MIN_BIOME_AREA_QUARTS && allMatch; oz++) {
				for (int ox = 0; ox < MIN_BIOME_AREA_QUARTS; ox++) {
					int gx = ax + ox;
					int gz = az + oz;
					int bit = 1 << (gz * 3 + gx);
					if ((evaluatedMask & bit) == 0) {
						evaluatedMask |= bit;
						int nx = blockX + (gx - 1) * QuartPos.SIZE;
						int nz = blockZ + (gz - 1) * QuartPos.SIZE;
						if (inWorldBounds(nx, nz)) {
							try (AuthoritativeSample sample = AuthoritativeSample.openNeighbor(
								this.context,
								nx,
								nz,
								true,
								this.cellOptimization::canUseRaw
							)) {
								Cell neighborCell = sample.cell();
								int nSurfaceY = this.surfaceY(neighborCell);
								if (gx == 0 && gz == 0) {
									sY00 = nSurfaceY;
								} else if (gx == 1 && gz == 0) {
									sY10 = nSurfaceY;
								} else if (gx == 0 && gz == 1) {
									sY01 = nSurfaceY;
								}
								boolean matches = biome.equals(this.resolveAt(neighborCell, nx, nSurfaceY, nz));
								if (matches && QuartPos.fromBlock(nSurfaceY) != QuartPos.fromBlock(centerSurfaceY)) {
									matches = biome.equals(this.resolveAt(neighborCell, nx, centerSurfaceY, nz));
								}
								if (matches) {
									matchedMask |= bit;
								}
							}
						}
					}
					if ((matchedMask & bit) == 0) {
						allMatch = false;
						break;
					}
				}
			}
			if (allMatch) {
				int anchorX = blockX + (ax - 1) * QuartPos.SIZE;
				int anchorZ = blockZ + (az - 1) * QuartPos.SIZE;
				int anchorY = (ax == 1 && az == 1) ? centerSurfaceY
					: (ax == 0 && az == 1) ? sY01
					: (ax == 1 && az == 0) ? sY10
					: sY00;
				if (QuartPos.fromBlock(anchorY) != QuartPos.fromBlock(centerSurfaceY)
					&& !this.verifySurfaceSquareAtY(biome, anchorX, anchorY, anchorZ)) {
					continue;
				}
				return new BiomeLocateSearch.Match(
					new BlockPos(anchorX, anchorY, anchorZ),
					biome,
					distanceSq(origin, anchorX, anchorZ)
				);
			}
		}
		return null;
	}

	private boolean verifySurfaceSquareAtY(Holder<Biome> biome, int anchorX, int anchorY, int anchorZ) {
		for (int dz = 0; dz < MIN_BIOME_AREA_BLOCKS; dz += QuartPos.SIZE) {
			for (int dx = 0; dx < MIN_BIOME_AREA_BLOCKS; dx += QuartPos.SIZE) {
				int x = anchorX + dx;
				int z = anchorZ + dz;
				try (AuthoritativeSample sample = AuthoritativeSample.openNeighbor(
					this.context,
					x,
					z,
					true,
					this.cellOptimization::canUseRaw
				)) {
					if (!biome.equals(this.resolveAt(sample.cell(), x, anchorY, z))) {
						return false;
					}
				}
			}
		}
		return true;
	}

	@Nullable
	private BiomeLocateSearch.Match findUndergroundAreaMatch(
		Holder<Biome> biome,
		BiomePlacementPlan.TargetRoles roles,
		BlockPos origin,
		int blockX,
		int blockY,
		int blockZ
	) {
		int evaluatedMask = 1 << 4;
		int matchedMask = 1 << 4;

		for (int[] anchorOffset : AREA_ANCHOR_OFFSETS) {
			int ax = anchorOffset[0];
			int az = anchorOffset[1];
			boolean allMatch = true;
			for (int oz = 0; oz < MIN_BIOME_AREA_QUARTS && allMatch; oz++) {
				for (int ox = 0; ox < MIN_BIOME_AREA_QUARTS; ox++) {
					int gx = ax + ox;
					int gz = az + oz;
					int bit = 1 << (gz * 3 + gx);
					if ((evaluatedMask & bit) == 0) {
						evaluatedMask |= bit;
						int nx = blockX + (gx - 1) * QuartPos.SIZE;
						int nz = blockZ + (gz - 1) * QuartPos.SIZE;
						if (inWorldBounds(nx, nz)) {
							try (AuthoritativeSample sample = AuthoritativeSample.openNeighbor(
								this.context,
								nx,
								nz,
								true,
								this.cellOptimization::canUseRaw
							)) {
								Cell neighborCell = sample.cell();
								int neighborMaxY = alignDownToQuart(
									Math.min(this.surfaceY(neighborCell) - 1, this.context.levels.worldHeight - 1)
								);
								if (blockY <= neighborMaxY) {
									Holder<Biome> neighborBiome = this.resolveUndergroundCandidateAt(
										roles, neighborCell, nx, blockY, nz
									);
									if (biome.equals(neighborBiome)) {
										matchedMask |= bit;
									}
								}
							}
						}
					}
					if ((matchedMask & bit) == 0) {
						allMatch = false;
						break;
					}
				}
			}
			if (allMatch) {
				int anchorX = blockX + (ax - 1) * QuartPos.SIZE;
				int anchorZ = blockZ + (az - 1) * QuartPos.SIZE;
				return new BiomeLocateSearch.Match(
					new BlockPos(anchorX, blockY, anchorZ),
					biome,
					distanceSq(origin, anchorX, anchorZ)
				);
			}
		}
		return null;
	}

	private static boolean inWorldBounds(int blockX, int blockZ) {
		return blockX >= MIN_COORDINATE && blockX <= MAX_COORDINATE
			&& blockZ >= MIN_COORDINATE && blockZ <= MAX_COORDINATE;
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
		isolatedState.adoptClimateQuerySemantics(sourceState.climateQuerySemantics());
		FTFClimateSampler.SpawnSearch spawnSearch = sourceState.getSpawnSearch();
		if (spawnSearch != null) {
			isolatedState.setSpawnSearch(spawnSearch);
		}
		isolatedState.setWorldgenPlan(selection.plan());
		return isolated;
	}
}
