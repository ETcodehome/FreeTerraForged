package etcodehome.freeterraforged.mixin;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import etcodehome.freeterraforged.world.worldgen.structure.OceanMonumentBuildingFix;
import etcodehome.freeterraforged.world.worldgen.structure.OceanMonumentSeaLevel;
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

@Mixin(OceanMonumentPieces.MonumentBuilding.class)
public class MixinOceanMonumentBuilding implements OceanMonumentBuildingFix {
	@Unique
	private static final int ftf$FOOTPRINT_SAMPLE_STEPS = 4;

	@Shadow
	@Final
	private List<StructurePiece> childPieces;

	@Unique
	private final AtomicBoolean ftf$oceanDepthAdjusted = new AtomicBoolean(false);

	@Unique
	private volatile int ftf$configuredSeaLevel = Integer.MIN_VALUE;

	@Redirect(
		method = "postProcess",
		at = @At(
			value = "INVOKE",
			target = "Ljava/lang/Math;max(II)I"
		)
	)
	private int ftf$useConfiguredSeaLevel(int seaLevel, int vanillaMinimum) {
		return this.ftf$configuredSeaLevel == Integer.MIN_VALUE
			? Math.max(seaLevel, vanillaMinimum)
			: this.ftf$configuredSeaLevel;
	}

	@Inject(method = "postProcess", at = @At("HEAD"))
	private void ftf$fitToOceanFloor(
		WorldGenLevel level,
		StructureManager structureManager,
		ChunkGenerator chunkGenerator,
		RandomSource randomSource,
		BoundingBox chunkBox,
		ChunkPos chunkPos,
		BlockPos blockPos,
		CallbackInfo ci
	) {
		this.ftf$configuredSeaLevel = OceanMonumentSeaLevel.configured(level);

		// CAS guards against concurrent postProcess() calls across this monument's chunks double-moving the piece.
		if (!this.ftf$oceanDepthAdjusted.compareAndSet(false, true)) {
			return;
		}

		BoundingBox box = ((StructurePiece) (Object) this).getBoundingBox();
		int targetMinY = ftf$sampleHighestOceanFloor(level, chunkGenerator, box);
		int dy = targetMinY - box.minY();
		if (dy != 0) {
			this.ftf$moveBuilding(dy);
		}
	}

	@Override
	public void ftf$moveBuilding(int dy) {
		((StructurePiece) (Object) this).move(0, dy, 0);
		for (StructurePiece childPiece : this.childPieces) {
			childPiece.move(0, dy, 0);
		}
	}

	@Override
	public void ftf$markOceanDepthAdjusted() {
		this.ftf$oceanDepthAdjusted.set(true);
	}

	@Override
	public boolean ftf$isOceanDepthAdjusted() {
		return this.ftf$oceanDepthAdjusted.get();
	}

	@Unique
	private static int ftf$sampleHighestOceanFloor(WorldGenLevel level, ChunkGenerator chunkGenerator, BoundingBox box) {
		// getFirstOccupiedHeight samples density functions directly, avoiding the chunk-loaded-radius
		// bound that level.getHeight() would hit sampling this far across the monument's footprint.
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		int highest = level.getMinBuildHeight();
		for (int ix = 0; ix <= ftf$FOOTPRINT_SAMPLE_STEPS; ix++) {
			int x = ftf$sampleCoord(box.minX(), box.maxX(), ix);
			for (int iz = 0; iz <= ftf$FOOTPRINT_SAMPLE_STEPS; iz++) {
				int z = ftf$sampleCoord(box.minZ(), box.maxZ(), iz);
				int floor = chunkGenerator.getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
				highest = Math.max(highest, floor);
			}
		}
		return highest;
	}

	@Unique
	private static int ftf$sampleCoord(int min, int max, int index) {
		return min + Math.round((max - min) * (index / (float) ftf$FOOTPRINT_SAMPLE_STEPS));
	}
}
