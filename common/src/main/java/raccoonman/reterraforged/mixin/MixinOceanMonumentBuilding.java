package raccoonman.reterraforged.mixin;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import raccoonman.reterraforged.world.worldgen.structure.OceanMonumentBuildingFix;
import raccoonman.reterraforged.world.worldgen.structure.OceanMonumentSeaLevel;

@Mixin(OceanMonumentPieces.MonumentBuilding.class)
public class MixinOceanMonumentBuilding implements OceanMonumentBuildingFix {
	@Unique
	private static final int rtf$FOOTPRINT_SAMPLE_STEPS = 4;

	@Shadow
	@Final
	private List<StructurePiece> childPieces;

	@Unique
	private final AtomicBoolean rtf$oceanDepthAdjusted = new AtomicBoolean(false);

	@Unique
	private volatile int rtf$configuredSeaLevel = Integer.MIN_VALUE;

	@Redirect(
		method = "postProcess",
		at = @At(
			value = "INVOKE",
			target = "Ljava/lang/Math;max(II)I"
		)
	)
	private int rtf$useConfiguredSeaLevel(int seaLevel, int vanillaMinimum) {
		return this.rtf$configuredSeaLevel == Integer.MIN_VALUE
			? Math.max(seaLevel, vanillaMinimum)
			: this.rtf$configuredSeaLevel;
	}

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
		this.rtf$configuredSeaLevel = OceanMonumentSeaLevel.configured(level);
		if(this.rtf$configuredSeaLevel == Integer.MIN_VALUE) {
			return;
		}

		// CAS guards against concurrent postProcess() calls across this monument's chunks double-moving the piece.
		if (!this.rtf$oceanDepthAdjusted.compareAndSet(false, true)) {
			return;
		}

		BoundingBox box = ((StructurePiece) (Object) this).getBoundingBox();
		int targetMinY = rtf$sampleHighestOceanFloor(level, chunkGenerator, box);
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
		this.rtf$oceanDepthAdjusted.set(true);
	}

	@Override
	public boolean rtf$isOceanDepthAdjusted() {
		return this.rtf$oceanDepthAdjusted.get();
	}

	@Unique
	private static int rtf$sampleHighestOceanFloor(WorldGenLevel level, ChunkGenerator chunkGenerator, BoundingBox box) {
		// getFirstOccupiedHeight samples density functions directly, avoiding the chunk-loaded-radius
		// bound that level.getHeight() would hit sampling this far across the monument's footprint.
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		int highest = level.getMinBuildHeight();
		for (int ix = 0; ix <= rtf$FOOTPRINT_SAMPLE_STEPS; ix++) {
			int x = rtf$sampleCoord(box.minX(), box.maxX(), ix);
			for (int iz = 0; iz <= rtf$FOOTPRINT_SAMPLE_STEPS; iz++) {
				int z = rtf$sampleCoord(box.minZ(), box.maxZ(), iz);
				int floor = chunkGenerator.getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
				highest = Math.max(highest, floor);
			}
		}
		return highest;
	}

	@Unique
	private static int rtf$sampleCoord(int min, int max, int index) {
		return min + Math.round((max - min) * (index / (float) rtf$FOOTPRINT_SAMPLE_STEPS));
	}
}
