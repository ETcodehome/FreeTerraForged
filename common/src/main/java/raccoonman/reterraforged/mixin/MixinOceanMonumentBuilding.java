package raccoonman.reterraforged.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import raccoonman.reterraforged.world.worldgen.structure.OceanMonumentBuildingFix;

@Mixin(OceanMonumentPieces.MonumentBuilding.class)
public class MixinOceanMonumentBuilding implements OceanMonumentBuildingFix {
	@Unique
	private static final int rtf$FOOTPRINT_SAMPLE_STEPS = 4;

	@Shadow
	@Final
	private List<StructurePiece> childPieces;

	@Unique
	private boolean rtf$oceanDepthAdjusted;

	@Inject(method = "postProcess", at = @At("HEAD"))
	private void rtf$fitToOceanFloor(
		WorldGenLevel level,
		StructureManager structureManager,
		ChunkGenerator chunkGenerator,
		RandomSource randomSource,
		BoundingBox chunkBox,
		ChunkPos chunkPos,
		BlockPos blockPos,
		CallbackInfo ci
	) {
		if (this.rtf$oceanDepthAdjusted) {
			return;
		}
		this.rtf$oceanDepthAdjusted = true;

		BoundingBox box = ((StructurePiece) (Object) this).getBoundingBox();
		int targetMinY = rtf$sampleHighestOceanFloor(level, box);
		int dy = targetMinY - box.minY();
		if (dy != 0) {
			this.rtf$moveBuilding(dy);
		}
	}

	@Override
	public void rtf$moveBuilding(int dy) {
		((StructurePiece) (Object) this).move(0, dy, 0);
		for (StructurePiece childPiece : this.childPieces) {
			childPiece.move(0, dy, 0);
		}
	}

	@Override
	public void rtf$markOceanDepthAdjusted() {
		this.rtf$oceanDepthAdjusted = true;
	}

	@Override
	public boolean rtf$isOceanDepthAdjusted() {
		return this.rtf$oceanDepthAdjusted;
	}

	@Unique
	private static int rtf$sampleHighestOceanFloor(WorldGenLevel level, BoundingBox box) {
		int highest = level.getMinBuildHeight();
		for (int ix = 0; ix <= rtf$FOOTPRINT_SAMPLE_STEPS; ix++) {
			int x = rtf$sampleCoord(box.minX(), box.maxX(), ix);
			for (int iz = 0; iz <= rtf$FOOTPRINT_SAMPLE_STEPS; iz++) {
				int z = rtf$sampleCoord(box.minZ(), box.maxZ(), iz);
				highest = Math.max(highest, level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z));
			}
		}
		return highest;
	}

	@Unique
	private static int rtf$sampleCoord(int min, int max, int index) {
		return min + Math.round((max - min) * (index / (float) rtf$FOOTPRINT_SAMPLE_STEPS));
	}
}
