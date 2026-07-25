package raccoonman.reterraforged.mixin;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
import net.minecraft.world.level.levelgen.RandomState;
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
	private final AtomicBoolean rtf$oceanDepthAdjusted = new AtomicBoolean(false);

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
		// A monument spans many chunks; postProcess() runs once per intersecting chunk and those
		// can decorate concurrently on different worldgen worker threads. Only the thread that
		// wins this CAS may move the piece, so a monument reaching this fallback path (normally a
		// no-op - see MixinOceanMonumentStructure, which adjusts and marks during structure-start
		// creation before any postProcess call can happen) can't be shifted twice.
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
		// level is a WorldGenRegion scoped to a small radius around whichever chunk triggered this
		// postProcess() call; sampling level.getHeight() across the monument's full ~58x58
		// footprint can reach columns outside that radius and crash generation outright
		// (WorldGenRegion.getChunk() throws when asked for a chunk outside its accessible
		// dependency radius). getFirstOccupiedHeight() samples density functions directly, the
		// same globally-safe approach MixinOceanMonumentStructure already uses at structure-start
		// time, so it never touches chunk-loaded bounds.
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
