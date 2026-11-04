package etcodehome.freeterraforged.mixin;

import java.util.List;
import java.util.Optional;

import com.mojang.datafixers.util.Either;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.StructureAdaptation;

@Mixin(JigsawStructure.class)
public class MixinJigsawStructure {
	@Unique
	private static final int ftf$MARGIN = 10;
	@Unique
	private static final int ftf$BOUNDARY_TOLERANCE = 8;
	@Unique
	private static final int ftf$GRID_STEPS_PER_SIDE = 3;
	@Unique
	private static final int ftf$BURY_RADIUS = 6;
	@Unique
	private static final int ftf$TRAIL_RUINS_MAX_ATTEMPTS = 16;

	@Shadow
	@Final
	private Holder<StructureTemplatePool> startPool;
	@Shadow
	@Final
	private Optional<ResourceLocation> startJigsawName;
	@Shadow
	@Final
	private int maxDepth;
	@Shadow
	@Final
	private HeightProvider startHeight;
	@Shadow
	@Final
	private boolean useExpansionHack;
	@Shadow
	@Final
	private Optional<Heightmap.Types> projectStartToHeightmap;
	@Shadow
	@Final
	private int maxDistanceFromCenter;
	@Shadow
	@Final
	private List<PoolAliasBinding> poolAliases;
	@Shadow
	@Final
	private DimensionPadding dimensionPadding;
	@Shadow
	@Final
	private LiquidSettings liquidSettings;

	@Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
	private void ftf$correctOrSkip(Structure.GenerationContext generationContext, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
		if (!(generationContext.chunkGenerator() instanceof TerraForgedChunkGenerator generator)
			|| !((Object) generationContext.randomState() instanceof FTFRandomState randomState)
			|| !randomState.isTerraForged()
			|| randomState.generatorContext() == null) {
			return;
		}
		StructureAdaptation adaptation = generator.activeStructurePlan().adaptation(
			(Structure)(Object)this
		);
		if (adaptation == StructureAdaptation.NONE) {
			return;
		}

		if (adaptation == StructureAdaptation.TRAIL_RUINS) {
			ftf$handleTrailRuinsPlacement(generationContext, randomState.generatorContext(), cir);
			return;
		}

		if (adaptation == StructureAdaptation.VILLAGE) {
			ftf$handleVillageRetryPlacement(generationContext, cir);
			return;
		}

		ftf$handleSubterraneanPlacement(generationContext, cir);
	}

	@Unique
	private void ftf$handleTrailRuinsPlacement(
		Structure.GenerationContext generationContext,
		GeneratorContext generatorContext,
		CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir
	) {
		ChunkPos chunkPos = generationContext.chunkPos();
		int originX = chunkPos.getMinBlockX();
		int originZ = chunkPos.getMinBlockZ();
		RandomSource random = generationContext.random();
		WorldGenerationContext heightContext = new WorldGenerationContext(
			generationContext.chunkGenerator(),
			generationContext.heightAccessor()
		);

		for (int attempt = 0; attempt < ftf$TRAIL_RUINS_MAX_ATTEMPTS; attempt++) {
			int offsetX = attempt == 0 ? 0 : random.nextIntBetweenInclusive(-32, 32);
			int offsetZ = attempt == 0 ? 0 : random.nextIntBetweenInclusive(-32, 32);
			int sampledY = this.startHeight.sample(random, heightContext);
			BlockPos placementPos = new BlockPos(originX + offsetX, sampledY, originZ + offsetZ);

			Optional<Structure.GenerationStub> result = JigsawPlacement.addPieces(
				generationContext, this.startPool, this.startJigsawName, this.maxDepth, placementPos,
				this.useExpansionHack, this.projectStartToHeightmap, this.maxDistanceFromCenter,
				PoolAliasLookup.create(this.poolAliases, placementPos, generationContext.seed()),
				this.dimensionPadding, this.liquidSettings
			);
			if (result.isEmpty()) {
				continue;
			}

			Structure.GenerationStub stub = result.get();
			if (!ftf$isValidBiome(generationContext, stub.position())) {
				continue;
			}
			StructurePiecesBuilder builder = stub.getPiecesBuilder();
			int maxSuspension = ftf$maxBuryPlaneSuspension(builder, generatorContext);
			if (maxSuspension > 0) {
				continue;
			}

			cir.setReturnValue(Optional.of(new Structure.GenerationStub(stub.position(), Either.right(builder))));
			cir.cancel();
			return;
		}

		cir.setReturnValue(Optional.empty());
		cir.cancel();
	}

	@Unique
	private boolean ftf$isValidBiome(Structure.GenerationContext generationContext, BlockPos position) {
		Holder<Biome> biome = generationContext.chunkGenerator()
			.getBiomeSource()
			.getNoiseBiome(
				QuartPos.fromBlock(position.getX()),
				QuartPos.fromBlock(position.getY()),
				QuartPos.fromBlock(position.getZ()),
				generationContext.randomState().sampler()
			);
		return generationContext.validBiome().test(biome);
	}

	@Unique
	private int ftf$maxBuryPlaneSuspension(
		StructurePiecesBuilder builder,
		GeneratorContext generatorContext
	) {
		int maxSuspension = Integer.MIN_VALUE;
		WorldgenPerfTracker.ApplyCellCaller prevCaller = null;
		WorldgenPerfTracker.ThreadStats ts = null;
		if (WorldgenPerfTracker.ACTIVE) {
			ts = WorldgenPerfTracker.stats();
			prevCaller = ts.currentCaller;
			ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.STRUCTURES;
		}
		try {
			for (var piece : builder.build().pieces()) {
				if (!(piece instanceof PoolElementStructurePiece poolPiece)) {
					continue;
				}
				if (poolPiece.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
					continue;
				}

				BoundingBox box = poolPiece.getBoundingBox();
				int groundPlane = box.minY() + poolPiece.getGroundLevelDelta();
				int minX = box.minX() - ftf$BURY_RADIUS + 1;
				int maxX = box.maxX() + ftf$BURY_RADIUS - 1;
				int minZ = box.minZ() - ftf$BURY_RADIUS + 1;
				int maxZ = box.maxZ() + ftf$BURY_RADIUS - 1;
				for (int x = minX; x <= maxX; x += 2) {
					for (int z = minZ; z <= maxZ; z += 2) {
						if (!ftf$isInsideBurySupport(box, x, z)) {
							continue;
						}
						Cell cell = CellSampler.getCachedOrLookup(generatorContext.lookup, x, z, false);
						maxSuspension = Math.max(
							maxSuspension,
							groundPlane - generatorContext.levels.scale(cell.height)
						);
					}
				}
			}
			return maxSuspension == Integer.MIN_VALUE ? 0 : maxSuspension;
		} finally {
			if (ts != null) {
				ts.currentCaller = prevCaller;
			}
		}
	}

	@Unique
	private static boolean ftf$isInsideBurySupport(BoundingBox box, int x, int z) {
		int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
		int dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
		return dx * dx + dz * dz < ftf$BURY_RADIUS * ftf$BURY_RADIUS;
	}

	@Unique
	private void ftf$handleVillageRetryPlacement(Structure.GenerationContext generationContext, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
		ChunkPos chunkPos = generationContext.chunkPos();
		int originX = chunkPos.getMinBlockX();
		int originZ = chunkPos.getMinBlockZ();

		RandomSource random = generationContext.random();
		int maxAttempts = 8;
		int minRequiredPieces = 2;

		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			int offsetX = (attempt == 0) ? 0 : random.nextIntBetweenInclusive(-32, 32);
			int offsetZ = (attempt == 0) ? 0 : random.nextIntBetweenInclusive(-32, 32);

			int candidateX = originX + offsetX;
			int candidateZ = originZ + offsetZ;

			if (ftf$isRiverCell(candidateX, candidateZ, generationContext.randomState())) {
				continue;
			}

			int sampledY = this.startHeight.sample(random, new WorldGenerationContext(generationContext.chunkGenerator(), generationContext.heightAccessor()));

			int surfaceY = sampledY;
			if (this.projectStartToHeightmap.isPresent()) {
				surfaceY += generationContext.chunkGenerator().getFirstOccupiedHeight(
						candidateX, candidateZ, this.projectStartToHeightmap.get(),
						generationContext.heightAccessor(), generationContext.randomState()
				);
			}

			Holder<Biome> biome = generationContext.chunkGenerator()
					.getBiomeSource()
					.getNoiseBiome(
							QuartPos.fromBlock(candidateX),
							QuartPos.fromBlock(surfaceY),
							QuartPos.fromBlock(candidateZ),
							generationContext.randomState().sampler()
					);

			if (!generationContext.validBiome().test(biome)) {
				continue;
			}

			BlockPos placementPos = new BlockPos(candidateX, sampledY, candidateZ);

			Optional<Structure.GenerationStub> result = JigsawPlacement.addPieces(
					generationContext, this.startPool, this.startJigsawName, this.maxDepth, placementPos, this.useExpansionHack,
					this.projectStartToHeightmap, this.maxDistanceFromCenter,
					PoolAliasLookup.create(this.poolAliases, placementPos, generationContext.seed()),
					this.dimensionPadding, this.liquidSettings
			);

			if (result.isEmpty()) {
				continue;
			}

			Structure.GenerationStub stub = result.get();
			StructurePiecesBuilder builder = stub.getPiecesBuilder();

			if (builder.build().pieces().size() < minRequiredPieces) {
				continue;
			}

			if (ftf$footprintIntersectsRiver(builder, generationContext.randomState())) {
				continue;
			}

			cir.setReturnValue(Optional.of(new Structure.GenerationStub(stub.position(), Either.right(builder))));
			cir.cancel();
			return;
		}

		cir.setReturnValue(Optional.empty());
		cir.cancel();
	}

	@Unique
	private void ftf$handleSubterraneanPlacement(Structure.GenerationContext generationContext, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
		int sampledY = this.startHeight.sample(generationContext.random(), new WorldGenerationContext(generationContext.chunkGenerator(), generationContext.heightAccessor()));

		ChunkPos chunkPos = generationContext.chunkPos();
		int originX = chunkPos.getMinBlockX();
		int originZ = chunkPos.getMinBlockZ();
		FloorRange floorRange = ftf$sampleFloorRange(generationContext, originX, originZ);

		int naiveTarget = Math.min(sampledY, floorRange.worst() - ftf$MARGIN);
		int minWorldY = generationContext.heightAccessor().getMinBuildHeight() + this.dimensionPadding.bottom() + ftf$BOUNDARY_TOLERANCE;
		int maxLocalY = floorRange.best() - ftf$MARGIN;

		int target = naiveTarget;
		if (naiveTarget < minWorldY || naiveTarget > maxLocalY) {
			if (minWorldY > maxLocalY) {
				cir.setReturnValue(Optional.empty());
				cir.cancel();
				return;
			}
			target = (minWorldY + maxLocalY) / 2;
		}

		BlockPos blockPos = new BlockPos(originX, target, originZ);
		Holder<Biome> biome = generationContext.chunkGenerator()
				.getBiomeSource()
				.getNoiseBiome(QuartPos.fromBlock(blockPos.getX()), QuartPos.fromBlock(blockPos.getY()), QuartPos.fromBlock(blockPos.getZ()), generationContext.randomState().sampler());
		if (!generationContext.validBiome().test(biome)) {
			cir.setReturnValue(Optional.empty());
			cir.cancel();
			return;
		}

		Optional<Structure.GenerationStub> result = JigsawPlacement.addPieces(
				generationContext, this.startPool, this.startJigsawName, this.maxDepth, blockPos, this.useExpansionHack,
				this.projectStartToHeightmap, this.maxDistanceFromCenter,
				PoolAliasLookup.create(this.poolAliases, blockPos, generationContext.seed()),
				this.dimensionPadding, this.liquidSettings
		);
		if (result.isEmpty()) {
			cir.setReturnValue(result);
			cir.cancel();
			return;
		}

		Structure.GenerationStub stub = result.get();
		StructurePiecesBuilder builder = stub.getPiecesBuilder();
		BoundingBox realBbox = builder.getBoundingBox();

		int realMaxLocalY = ftf$sampleLocalCeiling(generationContext, realBbox) - ftf$MARGIN;
		if (realBbox.minY() <= minWorldY || realBbox.maxY() >= realMaxLocalY) {
			cir.setReturnValue(Optional.empty());
			cir.cancel();
			return;
		}

		cir.setReturnValue(Optional.of(new Structure.GenerationStub(stub.position(), Either.right(builder))));
		cir.cancel();
	}

	@Unique
	private boolean ftf$footprintIntersectsRiver(StructurePiecesBuilder builder, RandomState randomState) {
		if (!((Object) randomState instanceof FTFRandomState rtfRandomState)
			|| rtfRandomState.generatorContext() == null) {
			return false;
		}
		GeneratorContext context = rtfRandomState.generatorContext();
		WorldgenPerfTracker.ApplyCellCaller prevCaller = null;
		WorldgenPerfTracker.ThreadStats ts = null;
		if (WorldgenPerfTracker.ACTIVE) {
			ts = WorldgenPerfTracker.stats();
			prevCaller = ts.currentCaller;
			ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.STRUCTURES;
		}
		try {
			for (var piece : builder.build().pieces()) {
				BoundingBox box = piece.getBoundingBox();
				int minX = box.minX();
				int maxX = box.maxX();
				int minZ = box.minZ();
				int maxZ = box.maxZ();
				for (int x = minX; x <= maxX; x += 4) {
					for (int z = minZ; z <= maxZ; z += 4) {
						Cell cell = CellSampler.getCachedOrLookup(context.lookup, x, z, false);
						if (cell.terrain.isRiver()) {
							return true;
						}
					}
					if ((maxZ - minZ) % 4 != 0) {
						Cell cell = CellSampler.getCachedOrLookup(context.lookup, x, maxZ, false);
						if (cell.terrain.isRiver()) {
							return true;
						}
					}
				}
				if ((maxX - minX) % 4 != 0) {
					for (int z = minZ; z <= maxZ; z += 4) {
						Cell cell = CellSampler.getCachedOrLookup(context.lookup, maxX, z, false);
						if (cell.terrain.isRiver()) {
							return true;
						}
					}
					if ((maxZ - minZ) % 4 != 0) {
						Cell cell = CellSampler.getCachedOrLookup(context.lookup, maxX, maxZ, false);
						if (cell.terrain.isRiver()) {
							return true;
						}
					}
				}
			}
			return false;
		} finally {
			if (ts != null) {
				ts.currentCaller = prevCaller;
			}
		}
	}

	@Unique
	private boolean ftf$isRiverCell(int x, int z, RandomState randomState) {
		if (!((Object) randomState instanceof FTFRandomState rtfRandomState)) {
			return false;
		}
		GeneratorContext generatorContext = rtfRandomState.generatorContext();
		if (generatorContext == null) {
			return false;
		}

		WorldgenPerfTracker.ApplyCellCaller prevCaller = null;
		WorldgenPerfTracker.ThreadStats ts = null;
		if (WorldgenPerfTracker.ACTIVE) {
			ts = WorldgenPerfTracker.stats();
			prevCaller = ts.currentCaller;
			ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.STRUCTURES;
		}
		try {
			Cell cell = CellSampler.getCachedOrLookup(generatorContext.lookup, x, z, false);
			return cell.terrain.isRiver();
		} finally {
			if (ts != null) {
				ts.currentCaller = prevCaller;
			}
		}
	}

	@Unique
	private FloorRange ftf$sampleFloorRange(Structure.GenerationContext generationContext, int originX, int originZ) {
		int radius = this.maxDistanceFromCenter;
		int worst = Integer.MAX_VALUE;
		int best = Integer.MIN_VALUE;
		for (int xi = -ftf$GRID_STEPS_PER_SIDE; xi <= ftf$GRID_STEPS_PER_SIDE; xi++) {
			for (int zi = -ftf$GRID_STEPS_PER_SIDE; zi <= ftf$GRID_STEPS_PER_SIDE; zi++) {
				int x = originX + radius * xi / ftf$GRID_STEPS_PER_SIDE;
				int z = originZ + radius * zi / ftf$GRID_STEPS_PER_SIDE;
				int floor = generationContext.chunkGenerator()
						.getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, generationContext.heightAccessor(), generationContext.randomState());
				if (floor < worst) {
					worst = floor;
				}
				if (floor > best) {
					best = floor;
				}
			}
		}
		return new FloorRange(worst, best);
	}

	@Unique
	private int ftf$sampleLocalCeiling(Structure.GenerationContext generationContext, BoundingBox realBbox) {
		int steps = ftf$GRID_STEPS_PER_SIDE * 2;
		int lowest = Integer.MAX_VALUE;
		for (int xi = 0; xi <= steps; xi++) {
			int x = realBbox.minX() + (realBbox.maxX() - realBbox.minX()) * xi / steps;
			for (int zi = 0; zi <= steps; zi++) {
				int z = realBbox.minZ() + (realBbox.maxZ() - realBbox.minZ()) * zi / steps;
				int floor = generationContext.chunkGenerator()
						.getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, generationContext.heightAccessor(), generationContext.randomState());
				if (floor < lowest) {
					lowest = floor;
				}
			}
		}
		return lowest;
	}

	private record FloorRange(int worst, int best) {}
}
