package raccoonman.reterraforged.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import raccoonman.reterraforged.world.worldgen.structure.OceanMonumentBuildingFix;

@Mixin(OceanMonumentStructure.class)
public class MixinOceanMonumentStructure {
	@Unique
	private static final int rtf$FOOTPRINT_SAMPLE_STEPS = 4;

	@Inject(method = "generatePieces", at = @At("TAIL"))
	private static void rtf$fitGeneratedMonumentToOceanFloor(
		StructurePiecesBuilder builder,
		Structure.GenerationContext context,
		CallbackInfo ci
	) {
		List<StructurePiece> pieces = ((StructurePiecesBuilderAccessor) builder).rtf$getPieces();
		if (pieces.isEmpty()) {
			return;
		}

		StructurePiece piece = pieces.getLast();
		if (piece instanceof OceanMonumentBuildingFix monumentBuilding && !monumentBuilding.rtf$isOceanDepthAdjusted()) {
			BoundingBox box = piece.getBoundingBox();
			int targetMinY = rtf$sampleHighestOceanFloor(context, box);
			int dy = targetMinY - box.minY();
			if (dy != 0) {
				monumentBuilding.rtf$moveBuilding(dy);
			}
			monumentBuilding.rtf$markOceanDepthAdjusted();
		}
	}

	@Inject(method = "regeneratePiecesAfterLoad", at = @At("RETURN"))
	private static void rtf$preserveAdjustedMonumentHeight(
		ChunkPos chunkPos,
		long seed,
		PiecesContainer originalPieces,
		CallbackInfoReturnable<PiecesContainer> callback
	) {
		if (originalPieces.isEmpty()) {
			return;
		}

		PiecesContainer regeneratedPieces = callback.getReturnValue();
		if (regeneratedPieces.isEmpty()) {
			return;
		}

		StructurePiece originalPiece = originalPieces.pieces().getFirst();
		StructurePiece regeneratedPiece = regeneratedPieces.pieces().getFirst();
		int dy = originalPiece.getBoundingBox().minY() - regeneratedPiece.getBoundingBox().minY();
		if (dy != 0 && regeneratedPiece instanceof OceanMonumentBuildingFix monumentBuilding) {
			monumentBuilding.rtf$moveBuilding(dy);
			monumentBuilding.rtf$markOceanDepthAdjusted();
		}
	}

	@Unique
	private static int rtf$sampleHighestOceanFloor(Structure.GenerationContext context, BoundingBox box) {
		int highest = context.heightAccessor().getMinBuildHeight();
		for (int ix = 0; ix <= rtf$FOOTPRINT_SAMPLE_STEPS; ix++) {
			int x = rtf$sampleCoord(box.minX(), box.maxX(), ix);
			for (int iz = 0; iz <= rtf$FOOTPRINT_SAMPLE_STEPS; iz++) {
				int z = rtf$sampleCoord(box.minZ(), box.maxZ(), iz);
				int height = context.chunkGenerator().getFirstOccupiedHeight(
					x, z, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState()
				);
				highest = Math.max(highest, height);
			}
		}
		return highest;
	}

	@Unique
	private static int rtf$sampleCoord(int min, int max, int index) {
		return min + Math.round((max - min) * (index / (float) rtf$FOOTPRINT_SAMPLE_STEPS));
	}
}
