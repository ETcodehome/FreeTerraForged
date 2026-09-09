package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;
import raccoonman.reterraforged.world.worldgen.NoiseChunkHolder;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.NoiseChunkTileOwner;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess implements IFlowFieldHolder, NoiseChunkHolder {
	@Shadow @Nullable protected NoiseChunk noiseChunk;

	@Unique
	@Nullable
	private volatile ChunkFlowField reterraforged$flowField;
	@Unique
	private int reterraforged$tileStageDepth;
	@Unique
	private int reterraforged$attachedTileStageDepth;

	@Override
	public ChunkFlowField reterraforged$getFlowField() {
		return this.reterraforged$flowField;
	}

	@Override
	public ChunkFlowField reterraforged$getOrCreateFlowField() {
		ChunkFlowField current = this.reterraforged$flowField;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			current = this.reterraforged$flowField;
			if (current == null) {
				current = new ChunkFlowField();
				this.reterraforged$flowField = current;
			}
		}
		return current;
	}

	@Override
	public NoiseChunk reterraforged$getNoiseChunk() {
		return this.noiseChunk;
	}

	@Override
	public synchronized void reterraforged$beginNoiseChunkTileStage() {
		this.reterraforged$tileStageDepth++;
		try {
			this.reterraforged$attachPendingTileStages();
		} catch (RuntimeException | Error failure) {
			this.reterraforged$tileStageDepth--;
			throw failure;
		}
	}

	@Override
	public synchronized void reterraforged$endNoiseChunkTileStage() {
		if (this.reterraforged$tileStageDepth <= 0) {
			throw new IllegalStateException("NoiseChunk tile-stage ownership underflow");
		}
		this.reterraforged$tileStageDepth--;
		if (this.reterraforged$attachedTileStageDepth > this.reterraforged$tileStageDepth) {
			this.reterraforged$attachedTileStageDepth--;
			if (this.noiseChunk instanceof NoiseChunkTileOwner owner) {
				owner.reterraforged$endTileStage();
			} else {
				throw new IllegalStateException("Attached NoiseChunk does not expose tile ownership");
			}
		}
	}

	@Inject(method = "getOrCreateNoiseChunk", at = @At("RETURN"))
	private synchronized void reterraforged$attachCreatedNoiseChunk(
		java.util.function.Function<ChunkAccess, NoiseChunk> factory,
		CallbackInfoReturnable<NoiseChunk> callback
	) {
		this.reterraforged$attachPendingTileStages();
	}

	@Unique
	private void reterraforged$attachPendingTileStages() {
		if (!(this.noiseChunk instanceof NoiseChunkTileOwner owner)) {
			return;
		}
		int initialDepth = this.reterraforged$attachedTileStageDepth;
		try {
			while (this.reterraforged$attachedTileStageDepth < this.reterraforged$tileStageDepth) {
				owner.reterraforged$beginTileStage();
				this.reterraforged$attachedTileStageDepth++;
			}
		} catch (RuntimeException | Error failure) {
			while (this.reterraforged$attachedTileStageDepth > initialDepth) {
				this.reterraforged$attachedTileStageDepth--;
				try {
					owner.reterraforged$endTileStage();
				} catch (RuntimeException | Error cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
	}

}
