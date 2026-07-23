package raccoonman.reterraforged.mixin;

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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

/**
 * Keeps Trial Chamber and Ancient City jigsaw starts within a terrain-bounded vertical window, then validates the
 * generated pieces against the dimension floor and the lowest surface over the resulting structure footprint.
 */
@Mixin(JigsawStructure.class)
public class MixinJigsawStructure {
	@Unique
	private static final int rtf$MARGIN = 10;
	@Unique
	private static final int rtf$BOUNDARY_TOLERANCE = 8;
	@Unique
	private static final int rtf$GRID_STEPS_PER_SIDE = 3;

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
	private void rtf$correctOrSkip(Structure.GenerationContext generationContext, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
		Structure self = (Structure) (Object) this;
		Structure trialChambers = generationContext.registryAccess().registryOrThrow(Registries.STRUCTURE).get(BuiltinStructures.TRIAL_CHAMBERS);
		Structure ancientCity = generationContext.registryAccess().registryOrThrow(Registries.STRUCTURE).get(BuiltinStructures.ANCIENT_CITY);
		if (self != trialChambers && self != ancientCity) {
			return;
		}

		int sampledY = this.startHeight.sample(generationContext.random(), new WorldGenerationContext(generationContext.chunkGenerator(), generationContext.heightAccessor()));

		ChunkPos chunkPos = generationContext.chunkPos();
		int originX = chunkPos.getMinBlockX();
		int originZ = chunkPos.getMinBlockZ();
		FloorRange floorRange = rtf$sampleFloorRange(generationContext, originX, originZ);

		int naiveTarget = Math.min(sampledY, floorRange.worst() - rtf$MARGIN);
		int minWorldY = generationContext.heightAccessor().getMinBuildHeight() + this.dimensionPadding.bottom() + rtf$BOUNDARY_TOLERANCE;
		int maxLocalY = floorRange.best() - rtf$MARGIN;

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

		// Building twice advances the structure RNG and can select different pieces, so reuse one materialized builder.
		Structure.GenerationStub stub = result.get();
		StructurePiecesBuilder builder = stub.getPiecesBuilder();
		BoundingBox realBbox = builder.getBoundingBox();

		// The initial search radius can include unrelated high terrain; derive the ceiling from the resulting footprint.
		int realMaxLocalY = rtf$sampleLocalCeiling(generationContext, realBbox) - rtf$MARGIN;
		if (realBbox.minY() <= minWorldY || realBbox.maxY() >= realMaxLocalY) {
			cir.setReturnValue(Optional.empty());
			cir.cancel();
			return;
		}

		cir.setReturnValue(Optional.of(new Structure.GenerationStub(stub.position(), Either.right(builder))));
		cir.cancel();
	}

	@Unique
	private FloorRange rtf$sampleFloorRange(Structure.GenerationContext generationContext, int originX, int originZ) {
		int radius = this.maxDistanceFromCenter;
		int worst = Integer.MAX_VALUE;
		int best = Integer.MIN_VALUE;
		for (int xi = -rtf$GRID_STEPS_PER_SIDE; xi <= rtf$GRID_STEPS_PER_SIDE; xi++) {
			for (int zi = -rtf$GRID_STEPS_PER_SIDE; zi <= rtf$GRID_STEPS_PER_SIDE; zi++) {
				int x = originX + radius * xi / rtf$GRID_STEPS_PER_SIDE;
				int z = originZ + radius * zi / rtf$GRID_STEPS_PER_SIDE;
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
	private int rtf$sampleLocalCeiling(Structure.GenerationContext generationContext, BoundingBox realBbox) {
		int steps = rtf$GRID_STEPS_PER_SIDE * 2;
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
